package app.twodo.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import app.twodo.data.Identity
import app.twodo.data.ListRepository
import app.twodo.model.AuditEntry
import app.twodo.model.Item
import app.twodo.model.ListKeys
import app.twodo.model.describeChange
import app.twodo.model.isPhotoPart
import app.twodo.net.ListSwarm
import app.twodo.net.Peer
import app.twodo.net.SwarmEvents
import app.twodo.net.SyncLog
import app.twodo.net.NostrEvent
import app.twodo.net.NostrIdentity
import app.twodo.net.RELAY_EXPIRY_DAYS
import app.twodo.net.RelayPool
import app.twodo.net.TWODO_KIND
import app.twodo.net.TWODO_LIVE_KIND
import app.twodo.net.TrackerPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.TimeUnit

/** Messages exchanged over a peer's data channel (encrypted with the list key). */
@Serializable
sealed class SyncMessage {
    /** [wantFull]: the sender (e.g. just joined) asks those online to send the whole list via the relays. */
    @Serializable
    @SerialName("hello")
    data class Hello(val deviceId: String, val deviceName: String, val wantFull: Boolean = false) : SyncMessage()

    /**
     * [full] is the whole list, sent when a connection opens; otherwise just changed items. [audit]
     * carries change-log entries. Big updates are split into chunks (data channel messages are
     * capped at ~256 KB); [last] marks the final chunk. Both are extra fields older versions ignore.
     */
    @Serializable
    @SerialName("items")
    data class Items(
        val items: List<Item>,
        val full: Boolean = false,
        val audit: List<AuditEntry> = emptyList(),
        val last: Boolean = true,
        /** Who sent it, on relay messages (which aren't tied to a connection). */
        val from: String? = null,
    ) : SyncMessage()

    /** The sender removed the list from their device. */
    @Serializable
    @SerialName("leave")
    data class Leave(val deviceId: String) : SyncMessage()
}

/**
 * [online]: the phone has a network connection. [receiving]: a first sync with someone is under way
 * (e.g. just after joining).
 */
data class SyncStatus(
    val trackersOnline: Int = 0,
    val peerNames: List<String> = emptyList(),
    val receiving: Boolean = false,
    val online: Boolean = true,
    val relaysOnline: Int = 0,
    /** Others seen recently through the relays but not connected directly. */
    val relayPeerNames: List<String> = emptyList(),
)

/**
 * Keeps one [ListSwarm] per list while anything needs syncing (the app is visible, the background
 * service runs, or the periodic worker runs). Reference counted through [acquire]/[release].
 */
class SyncManager(context: Context, private val repo: ListRepository, private val identity: Identity) : SwarmEvents {
    private val appContext = context.applicationContext
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val peerId = ListSwarm.randomId()
    private val factory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private val swarms = mutableMapOf<String, ListSwarm>()
    /** Shared tracker connections, open while anything is syncing. */
    private var pool: TrackerPool? = null
    /** Shared Nostr relay connections: fallback route and offline delivery. */
    private var relays: RelayPool? = null
    private val relayLists = mutableMapOf<String, RelayList>()
    private val keys = mutableMapOf<String, ListKeys>()
    private var users = 0
    private var online = true
    /** Peers whose first full sync is just us joining, not changes worth announcing. */
    private val firstSyncPeers = mutableSetOf<Peer>()

    private val _events = MutableSharedFlow<ListEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ListEvent> = _events

    private val _names = MutableStateFlow(identity.knownNames)

    /** Other devices' current names, by device id. */
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    private val _status = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())
    val status: StateFlow<Map<String, SyncStatus>> = _status.asStateFlow()

    init {
        scope.launch { repo.lists.collect { reconcile() } }
        // Tracker connections come and go without telling the swarm; refresh the status line regularly.
        scope.launch {
            while (true) {
                delay(5_000)
                publishStatus()
            }
        }
        scope.launch {
            repo.localEdits.collect { edit ->
                publishToRelays(edit.listId, SyncMessage.Items(listOf(edit.item), audit = listOfNotNull(edit.audit)))
                val swarm = swarms[edit.listId] ?: return@collect
                swarm.openPeers.forEach { sendItems(swarm, it, listOf(edit.item), listOfNotNull(edit.audit)) }
            }
        }
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        online = connectivity.activeNetwork != null
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        online = true
                        val caps = connectivity.getNetworkCapabilities(network)
                        val kind = when {
                            caps == null -> "unknown"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                            else -> "other"
                        }
                        SyncLog.add("network available: $kind")
                        pool?.kick()
                        relays?.kick()
                        swarms.values.forEach { it.kick() }
                        publishStatus()
                    }
                }

                override fun onLost(network: Network) {
                    scope.launch {
                        online = connectivity.activeNetwork != null
                        publishStatus()
                    }
                }
            },
        )
    }

    fun acquire() {
        scope.launch {
            users++
            reconcile()
            // Opening the app is a good moment to look for the others again, quickly.
            swarms.values.forEach { it.kick() }
        }
    }

    fun release() {
        scope.launch {
            users = maxOf(0, users - 1)
            reconcile()
        }
    }

    /** Runs one swarm (direct connections) and one relay subscription per list while in use; none otherwise. */
    private suspend fun reconcile() {
        val wanted = if (users > 0) repo.lists.value else emptyMap()
        val direct = if (identity.directConnections) wanted else emptyMap()
        (swarms.keys - direct.keys).forEach { id -> swarms.remove(id)?.stop() }
        (relayLists.keys - wanted.keys).forEach { id -> relayLists.remove(id)?.let { relays?.unsubscribe(it.subscriptionId) } }
        keys.keys.retainAll(wanted.keys)
        if (users == 0) {
            pool?.close()
            pool = null
            relays?.close()
            relays = null
            return publishStatus()
        }
        // Connect as soon as the app is in use, even before any list needs it.
        val pool = pool ?: TrackerPool(http, scope).also { pool = it; it.start() }
        val relays = relays ?: RelayPool(http, scope).also { relays = it; it.start() }
        for ((id, list) in wanted) {
            val listKeys = keys.getOrPut(id) { ListKeys(list.secret) }
            if (id in direct && id !in swarms) {
                swarms[id] = ListSwarm(id, listKeys.topic, peerId, factory, pool, scope, this).also { it.start() }
            }
            if (id !in relayLists) relayLists[id] = RelayList(id, list.secret).also { it.subscribe(relays, list.relaySince) }
        }
        publishStatus()
    }

    /** Re-applies settings that change which routes are used (debug: direct connections on/off). */
    fun refresh() {
        scope.launch { reconcile() }
    }

    // ---- Relays (Nostr) ------------------------------------------------------------------------------------

    /** A list's presence on the relays: its own Nostr identity and subscription. */
    private inner class RelayList(val listId: String, secret: String) {
        val nostr = NostrIdentity(secret)
        val subscriptionId = "twodo-" + nostr.publicKey.take(12)
        var caughtUp = false
        /** Asked for the whole list via the relays and waiting for it (e.g. just joined). */
        var awaitingFull = false
        var lastFullSentAt = 0L
        var lastAskedAt = 0L
        /** Others seen through the relays: device id -> (name, when). */
        val seen = mutableMapOf<String, Pair<String, Long>>()
        private val startedAt = SystemClock.elapsedRealtime()

        fun trace(message: String) = SyncLog.add("[relay ${listId.take(4)}] +${SystemClock.elapsedRealtime() - startedAt}ms $message")

        fun subscribe(relays: RelayPool, since: Long) {
            val filter = buildJsonObject {
                putJsonArray("kinds") { add(TWODO_KIND); add(TWODO_LIVE_KIND) }
                putJsonArray("authors") { add(nostr.publicKey) }
                // Everything since we were last here (stored events expire after RELAY_EXPIRY_DAYS).
                if (since > 0) put("since", since - SINCE_MARGIN_S)
            }
            relays.subscribe(subscriptionId, filter, onEvent = { onRelayEvent(this, it) }, onCaughtUp = { onRelayCaughtUp(this) })
        }
    }

    private suspend fun onRelayCaughtUp(relayList: RelayList) {
        if (relayList.caughtUp) return
        relayList.caughtUp = true
        val list = repo.lists.value[relayList.listId] ?: return
        val stale = list.relaySince > 0 && System.currentTimeMillis() / 1000 - list.relaySince > (RELAY_EXPIRY_DAYS - 1) * 86_400L
        // Stored edits don't include items nobody has touched lately, so ask until we've had a full copy.
        val wantFull = (!list.createdHere && !list.fullSynced) || stale
        relayList.awaitingFull = wantFull
        relayList.trace("caught up; saying hello${if (wantFull) " and asking for the whole list" else ""}")
        askViaRelays(relayList)
        publishStatus()
    }

    private suspend fun onRelayEvent(relayList: RelayList, event: NostrEvent) {
        val listId = relayList.listId
        // Live messages (hello, whole-list replies) only mean something right away, but some relays keep
        // them for a while anyway; an old one could make us think we'd already had the whole list.
        val age = System.currentTimeMillis() / 1000 - event.created_at
        if (event.kind == TWODO_LIVE_KIND && age > FRESH_HELLO_S) return
        val plain = keys[listId]?.decryptCompressed(event.content) ?: return
        val message = runCatching { json.decodeFromString<SyncMessage>(plain) }.getOrNull() ?: return
        if (event.kind == TWODO_KIND) repo.setRelaySince(listId, event.created_at)
        when (message) {
            is SyncMessage.Hello -> {
                if (message.deviceId == identity.deviceId) return
                relayList.seen[message.deviceId] = message.deviceName to SystemClock.elapsedRealtime()
                identity.rememberName(message.deviceId, message.deviceName)
                _names.value = identity.knownNames
                val member = repo.recordMember(listId, message.deviceId, message.deviceName)
                if (member.worthAnnouncing && !inGroup(listId)) _events.tryEmit(ListEvent.Joined(listId, listName(listId), message.deviceName))
                val fresh = age < FRESH_HELLO_S
                if (message.wantFull && fresh) sendFullViaRelays(relayList, message.deviceName)
                // Still waiting for the whole list and someone new is around: ask them.
                val askedLongAgo = SystemClock.elapsedRealtime() - relayList.lastAskedAt > FULL_RESEND_MS
                if (fresh && relayList.awaitingFull && askedLongAgo) askViaRelays(relayList)
                publishStatus()
            }
            is SyncMessage.Items -> {
                val result = repo.applyRemote(listId, message.items, message.audit)
                relayList.trace("applied ${message.items.size} items + ${message.audit.size} audit from relay (full=${message.full}, last=${message.last})")
                // Pass on to directly connected devices, which may be on a version without relays.
                if (result.changes.isNotEmpty() || result.newAudit.isNotEmpty()) {
                    swarms[listId]?.let { swarm ->
                        swarm.openPeers.forEach { sendItems(swarm, it, result.changes.map { c -> c.second }, result.newAudit) }
                    }
                }
                val quiet = message.full && relayList.awaitingFull
                if (message.full && message.last) repo.markFullSynced(listId)
                if (message.full && message.last && relayList.awaitingFull) {
                    relayList.awaitingFull = false
                    relayList.trace("received the whole list")
                    val list = repo.lists.value[listId]
                    if (list != null && !list.createdHere && list.groupId == null) {
                        _events.tryEmit(ListEvent.JoinedList(listId, list.name, message.from ?: "the others"))
                    }
                    publishStatus()
                }
                if (!quiet) announceChanges(listId, result.changes)
            }
            is SyncMessage.Leave -> {
                if (message.deviceId == identity.deviceId) return
                relayList.seen.remove(message.deviceId)
                val name = repo.removeMember(listId, message.deviceId) ?: return
                if (!inGroup(listId)) _events.tryEmit(ListEvent.Left(listId, listName(listId), name))
            }
        }
    }

    /** Says hello on the relays, asking for the whole list if we're still waiting for it. */
    private fun askViaRelays(relayList: RelayList) {
        relayList.lastAskedAt = SystemClock.elapsedRealtime()
        publishToRelays(relayList.listId, SyncMessage.Hello(identity.deviceId, identity.deviceName, relayList.awaitingFull), live = true)
    }

    /** Someone asked for the whole list through the relays; send it (at most every so often). */
    private fun sendFullViaRelays(relayList: RelayList, forName: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - relayList.lastFullSentAt < FULL_RESEND_MS) return
        relayList.lastFullSentAt = now
        val list = repo.lists.value[relayList.listId] ?: return
        // Photo pieces are base64, which hardly compresses, so they count three times towards the limit.
        val itemChunks = chunkBySize(list.items.values.toList()) {
            json.encodeToString(Item.serializer(), it).length * (if (it.isPhotoPart) 3 else 1)
        }
        val auditChunks = chunkBySize(list.audit.values.toList()) { json.encodeToString(AuditEntry.serializer(), it).length }
        val count = maxOf(itemChunks.size, auditChunks.size, 1)
        relayList.trace("sending the whole list to $forName via relays ($count message${if (count == 1) "" else "s"})")
        // Each relay paces its own sends, so these can all be queued at once.
        for (i in 0 until count) {
            val chunk = SyncMessage.Items(
                items = itemChunks.getOrElse(i) { emptyList() },
                full = true,
                audit = auditChunks.getOrElse(i) { emptyList() },
                last = i == count - 1,
                from = identity.deviceName,
            )
            publishToRelays(relayList.listId, chunk, live = true)
        }
    }

    /**
     * Splits [values] into chunks of at most [RELAY_CHUNK_BYTES] of JSON; compressed, that stays well
     * under the relays' event size limits.
     */
    private fun <T> chunkBySize(values: List<T>, limit: Int = RELAY_CHUNK_BYTES, size: (T) -> Int): List<List<T>> {
        val chunks = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        var bytes = 0
        for (value in values) {
            val n = size(value)
            if (current.isNotEmpty() && bytes + n > limit) {
                chunks += current
                current = mutableListOf()
                bytes = 0
            }
            current += value
            bytes += n
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    /**
     * Publishes [message] for [listId]'s members on every relay. Stored messages (edits, leaving) reach
     * phones that are offline when they come back; [live] ones (hello, full-list replies) only matter to
     * phones online right now.
     */
    private fun publishToRelays(listId: String, message: SyncMessage, live: Boolean = false) {
        val relayList = relayLists[listId] ?: return
        val relays = relays ?: return
        val content = keys[listId]?.encryptCompressed(json.encodeToString(SyncMessage.serializer(), message)) ?: return
        val expiry = System.currentTimeMillis() / 1000 + RELAY_EXPIRY_DAYS * 86_400L
        val kind = if (live) TWODO_LIVE_KIND else TWODO_KIND
        relays.publish(relayList.nostr.sign(kind, listOf(listOf("expiration", expiry.toString())), content))
    }

    override suspend fun onPeerOpen(swarm: ListSwarm, peer: Peer) {
        val list = repo.lists.value[swarm.listId] ?: return
        send(swarm, peer, SyncMessage.Hello(identity.deviceId, identity.deviceName))
        sendItems(swarm, peer, list.items.values.toList(), list.audit.values.toList(), full = true)
    }

    override suspend fun onPeerMessage(swarm: ListSwarm, peer: Peer, text: String) {
        val plain = keys[swarm.listId]?.decrypt(text)
        val message = plain?.let { runCatching { json.decodeFromString<SyncMessage>(it) }.getOrNull() }
        if (message == null) {
            Log.w(TAG, "Dropping peer that sent an unreadable message")
            return swarm.drop(peer)
        }
        when (message) {
            is SyncMessage.Hello -> {
                peer.deviceName = message.deviceName
                // Seen through the relays under an older name? Keep one name per person.
                relayLists[swarm.listId]?.seen?.computeIfPresent(message.deviceId) { _, (_, at) -> message.deviceName to at }
                identity.rememberName(message.deviceId, message.deviceName)
                _names.value = identity.knownNames
                val member = repo.recordMember(swarm.listId, message.deviceId, message.deviceName)
                if (member.firstContact) firstSyncPeers += peer
                if (member.worthAnnouncing && !inGroup(swarm.listId)) {
                    _events.tryEmit(ListEvent.Joined(swarm.listId, listName(swarm.listId), message.deviceName))
                }
                publishStatus()
            }
            is SyncMessage.Items -> {
                val applyStarted = SystemClock.elapsedRealtime()
                val result = repo.applyRemote(swarm.listId, message.items, message.audit)
                swarm.trace(
                    "applied ${message.items.size} items + ${message.audit.size} audit (full=${message.full}, last=${message.last}) " +
                        "in ${SystemClock.elapsedRealtime() - applyStarted}ms",
                )
                // Pass changes on so devices that aren't directly connected still converge.
                if (result.changes.isNotEmpty() || result.newAudit.isNotEmpty()) {
                    val accepted = result.changes.map { it.second }
                    swarm.openPeers.filter { it !== peer }.forEach { sendItems(swarm, it, accepted, result.newAudit) }
                }
                // A device's first full sync (possibly several chunks) is just us joining, not news.
                if (message.full && message.last) {
                    repo.markFullSynced(swarm.listId)
                    relayLists[swarm.listId]?.awaitingFull = false
                }
                val quiet = message.full && peer in firstSyncPeers
                if (message.full && message.last && firstSyncPeers.remove(peer)) {
                    publishStatus()
                    val list = repo.lists.value[swarm.listId]
                    if (list != null && !list.createdHere && list.groupId == null) {
                        _events.tryEmit(ListEvent.JoinedList(list.id, list.name, peer.deviceName ?: "the other phone"))
                    }
                }
                if (!quiet) announceChanges(swarm.listId, result.changes)
            }
            is SyncMessage.Leave -> {
                val name = repo.removeMember(swarm.listId, message.deviceId) ?: peer.deviceName ?: return
                if (!inGroup(swarm.listId)) _events.tryEmit(ListEvent.Left(swarm.listId, listName(swarm.listId), name))
            }
        }
    }

    private fun announceChanges(listId: String, changes: List<Pair<Item?, Item>>) {
        changes.mapNotNull { (before, after) -> describeChange(before, after)?.let { after to it } }
            .groupBy { (item, _) -> _names.value[item.version.by] ?: item.editor }
            .forEach { (who, described) ->
                _events.tryEmit(
                    ListEvent.Changed(listId, listName(listId), who, described.map { it.second }, described.map { it.first.id }),
                )
            }
    }

    private fun listName(listId: String) = repo.lists.value[listId]?.name ?: "a list"

    /** Spaces in a group: people joining and leaving are announced once, for the group. */
    private fun inGroup(listId: String) = repo.lists.value[listId]?.groupId != null

    /**
     * Tells the others this device is leaving the list, then removes it from this device. Runs in the
     * manager's own scope so it completes even if the screen that asked for it goes away.
     */
    fun leave(listId: String) {
        scope.launch {
            // Leaving a group leaves everything in it.
            val ids = repo.lists.value.values.filter { it.groupId == listId }.map { it.id } + listId
            for (id in ids) {
                broadcast(id, SyncMessage.Leave(identity.deviceId))
                publishToRelays(id, SyncMessage.Leave(identity.deviceId))
            }
            delay(500) // let the messages go out before the connections close
            repo.removeList(listId)
        }
    }

    /** While a list's share dialog is open, that list looks for newcomers every few seconds. */
    fun setSharing(listId: String, sharing: Boolean) {
        scope.launch {
            swarms[listId]?.eager = sharing
            // Let anyone online via the relays see us straight away too.
            if (sharing) publishToRelays(listId, SyncMessage.Hello(identity.deviceId, identity.deviceName), live = true)
        }
    }

    /** Tells connected devices about this user's new name. */
    fun nameChanged() {
        scope.launch {
            val hello = SyncMessage.Hello(identity.deviceId, identity.deviceName)
            swarms.values.forEach { swarm -> swarm.openPeers.forEach { send(swarm, it, hello) } }
            relayLists.keys.forEach { publishToRelays(it, hello, live = true) }
        }
    }

    override suspend fun onChanged(swarm: ListSwarm) = publishStatus()

    private fun broadcast(listId: String, message: SyncMessage) {
        val swarm = swarms[listId] ?: return
        swarm.openPeers.forEach { send(swarm, it, message) }
    }

    /** Sends items and audit entries, split into chunks that fit in a data channel message. */
    private fun sendItems(swarm: ListSwarm, peer: Peer, items: List<Item>, audit: List<AuditEntry>, full: Boolean = false) {
        // By size rather than count: a group's photo pieces are much bigger than ordinary items.
        val itemChunks = chunkBySize(items, DIRECT_CHUNK_BYTES) { json.encodeToString(Item.serializer(), it).length }
        val auditChunks = audit.chunked(AUDIT_PER_MESSAGE)
        val count = maxOf(itemChunks.size, auditChunks.size, 1)
        for (i in 0 until count) {
            val chunk = SyncMessage.Items(
                items = itemChunks.getOrElse(i) { emptyList() },
                full = full,
                audit = auditChunks.getOrElse(i) { emptyList() },
                last = i == count - 1,
            )
            send(swarm, peer, chunk)
        }
    }

    private fun send(swarm: ListSwarm, peer: Peer, message: SyncMessage) {
        val text = keys[swarm.listId]?.encrypt(json.encodeToString(SyncMessage.serializer(), message)) ?: return
        if (!peer.send(text)) Log.w(TAG, "Send to ${peer.deviceName} failed")
    }

    override suspend fun onPeerClosed(swarm: ListSwarm, peer: Peer) {
        firstSyncPeers -= peer
    }

    private fun publishStatus() {
        val recent = SystemClock.elapsedRealtime() - PRESENCE_WINDOW_MS
        _status.value = keys.keys.associateWith { id ->
            val open = swarms[id]?.openPeers.orEmpty()
            val direct = open.map { it.deviceName ?: "Unknown device" }
            val viaRelay = relayLists[id]?.seen?.values.orEmpty().filter { it.second > recent }.map { it.first } - direct.toSet()
            SyncStatus(
                trackersOnline = pool?.online ?: 0,
                peerNames = direct,
                receiving = open.any { it in firstSyncPeers } || relayLists[id]?.awaitingFull == true,
                online = online,
                relaysOnline = relays?.online ?: 0,
                relayPeerNames = viaRelay,
            )
        }
    }

    private companion object {
        const val TAG = "TwoDo"
        // Items up to ~100 KB of JSON and ~150 bytes per audit entry keep each chunk well under the ~256 KB limit.
        const val DIRECT_CHUNK_BYTES = 100_000
        const val AUDIT_PER_MESSAGE = 200
        // Relay messages are compressed (~5x): ~120 KB of JSON becomes ~25-30 KB, under relays' limits.
        const val RELAY_CHUNK_BYTES = 120_000
        const val SINCE_MARGIN_S = 300L
        const val FRESH_HELLO_S = 120L
        const val FULL_RESEND_MS = 30_000L
        const val PRESENCE_WINDOW_MS = 30 * 60_000L
    }
}
