package app.twodo.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import app.twodo.data.Identity
import app.twodo.data.ListRepository
import app.twodo.model.Item
import app.twodo.model.ListKeys
import app.twodo.net.ListSwarm
import app.twodo.net.Peer
import app.twodo.net.SwarmEvents
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
import okhttp3.OkHttpClient
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.TimeUnit

/** Messages exchanged over a peer's data channel (encrypted with the list key). */
@Serializable
sealed class SyncMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(val deviceId: String, val deviceName: String) : SyncMessage()

    /** [full] is the whole list, sent when a connection opens; otherwise just changed items. */
    @Serializable
    @SerialName("items")
    data class Items(val items: List<Item>, val full: Boolean = false) : SyncMessage()

    /** The sender removed the list from their device. */
    @Serializable
    @SerialName("leave")
    data class Leave(val deviceId: String) : SyncMessage()
}

data class SyncStatus(val trackersOnline: Int = 0, val peerNames: List<String> = emptyList())

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
    private val keys = mutableMapOf<String, ListKeys>()
    private var users = 0
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
            repo.localEdits.collect { edit -> broadcast(edit.listId, SyncMessage.Items(listOf(edit.item))) }
        }
        appContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { swarms.values.forEach { it.kick() } }
                }
            },
        )
    }

    fun acquire() {
        scope.launch {
            users++
            reconcile()
        }
    }

    fun release() {
        scope.launch {
            users = maxOf(0, users - 1)
            reconcile()
        }
    }

    /** Runs one swarm per list while in use; none otherwise. */
    private suspend fun reconcile() {
        val wanted = if (users > 0) repo.lists.value else emptyMap()
        (swarms.keys - wanted.keys).forEach { id ->
            swarms.remove(id)?.stop()
            keys.remove(id)
        }
        for ((id, list) in wanted) {
            if (id in swarms) continue
            val listKeys = ListKeys(list.secret).also { keys[id] = it }
            swarms[id] = ListSwarm(id, listKeys.topic, peerId, factory, http, scope, this).also { it.start() }
        }
        publishStatus()
    }

    override suspend fun onPeerOpen(swarm: ListSwarm, peer: Peer) {
        val list = repo.lists.value[swarm.listId] ?: return
        send(swarm, peer, SyncMessage.Hello(identity.deviceId, identity.deviceName))
        send(swarm, peer, SyncMessage.Items(list.items.values.toList(), full = true))
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
                identity.rememberName(message.deviceId, message.deviceName)
                _names.value = identity.knownNames
                val member = repo.recordMember(swarm.listId, message.deviceId, message.deviceName)
                if (member.firstContact) firstSyncPeers += peer
                if (member.worthAnnouncing) {
                    _events.tryEmit(ListEvent.Joined(swarm.listId, listName(swarm.listId), message.deviceName))
                }
                publishStatus()
            }
            is SyncMessage.Items -> {
                val changes = repo.applyRemote(swarm.listId, message.items)
                // Pass changes on so devices that aren't directly connected still converge.
                if (changes.isNotEmpty()) {
                    val accepted = changes.map { it.second }
                    swarm.openPeers.filter { it !== peer }.forEach { send(swarm, it, SyncMessage.Items(accepted)) }
                }
                val quiet = message.full && firstSyncPeers.remove(peer)
                if (!quiet) announceChanges(swarm.listId, changes)
            }
            is SyncMessage.Leave -> {
                val name = repo.removeMember(swarm.listId, message.deviceId) ?: peer.deviceName ?: return
                _events.tryEmit(ListEvent.Left(swarm.listId, listName(swarm.listId), name))
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

    /**
     * Tells the others this device is leaving the list, then removes it from this device. Runs in the
     * manager's own scope so it completes even if the screen that asked for it goes away.
     */
    fun leave(listId: String) {
        scope.launch {
            broadcast(listId, SyncMessage.Leave(identity.deviceId))
            delay(500) // let the message go out before the swarm closes
            repo.removeList(listId)
        }
    }

    /** Tells connected devices about this user's new name. */
    fun nameChanged() {
        scope.launch {
            val hello = SyncMessage.Hello(identity.deviceId, identity.deviceName)
            swarms.values.forEach { swarm -> swarm.openPeers.forEach { send(swarm, it, hello) } }
        }
    }

    override suspend fun onChanged(swarm: ListSwarm) = publishStatus()

    private fun broadcast(listId: String, message: SyncMessage) {
        val swarm = swarms[listId] ?: return
        swarm.openPeers.forEach { send(swarm, it, message) }
    }

    private fun send(swarm: ListSwarm, peer: Peer, message: SyncMessage) {
        val text = keys[swarm.listId]?.encrypt(json.encodeToString(SyncMessage.serializer(), message)) ?: return
        if (!peer.send(text)) Log.w(TAG, "Send to ${peer.deviceName} failed")
    }

    override suspend fun onPeerClosed(swarm: ListSwarm, peer: Peer) {
        firstSyncPeers -= peer
    }

    private fun publishStatus() {
        _status.value = swarms.mapValues { (_, swarm) ->
            SyncStatus(swarm.trackersOnline, swarm.openPeers.map { it.deviceName ?: "Unknown device" })
        }
    }

    private companion object {
        const val TAG = "TwoDo"
    }
}
