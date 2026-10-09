package app.intack.net

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.webrtc.PeerConnectionFactory

/**
 * Public WebTorrent trackers used as a meeting point. Several, so any one going down doesn't matter.
 * Checked October 2026 that each relays offers for arbitrary rooms (btorrent.xyz and files.fm had died).
 */
val TRACKERS = listOf(
    "wss://tracker.webtorrent.dev",
    "wss://tracker.openwebtorrent.com",
    "wss://tracker.novage.com.ua/announce",
)

interface SwarmEvents {
    suspend fun onPeerOpen(swarm: ListSwarm, peer: Peer)
    suspend fun onPeerMessage(swarm: ListSwarm, peer: Peer, text: String)
    suspend fun onChanged(swarm: ListSwarm)
    suspend fun onPeerClosed(swarm: ListSwarm, peer: Peer)
}

/**
 * Finds and connects to the other devices sharing one list. Each device announces a few WebRTC offers
 * to every tracker under the list's room id; trackers pass them to other devices in the room, which
 * answer straight away through the tracker. Offers are prepared ahead of time so an announcement goes
 * out as soon as a tracker connects. If two devices answer each other's offers at the same moment,
 * both keep the connection offered by the device with the lower tracker peer id.
 * All methods must be called in [scope], which must be single-threaded.
 */
class ListSwarm(
    val listId: String,
    val topic: String,
    private val myPeerId: String,
    private val factory: PeerConnectionFactory,
    private val pool: TrackerPool,
    private val scope: CoroutineScope,
    private val events: SwarmEvents,
) : PeerEvents {
    private class PendingOffer(val id: String, val peer: Peer, val sdp: String)

    private val trackers get() = pool.clients
    private val pending = mutableMapOf<String, PendingOffer>()
    /** Offers prepared in advance, ready to announce. */
    private val ready = ArrayDeque<PendingOffer>()
    private var preparing: Job? = null
    private val peers = mutableMapOf<String, Peer>()
    private var announceJob: Job? = null
    private var stopped = false
    /** How long to wait before looking again while nobody is connected; grows while nobody answers. */
    private var searchInterval = SEARCH_INTERVAL_MS
    private var lastRetry = 0L
    private val startedAt = SystemClock.elapsedRealtime()

    /** Timing log for diagnosing how long finding and syncing with peers takes (`adb logcat -s IntackSync`). */
    fun trace(message: String) = SyncLog.add("[${topic.take(4)}] +${SystemClock.elapsedRealtime() - startedAt}ms $message")

    val openPeers: List<Peer> get() = peers.values.filter { it.isOpen }
    val trackersOnline: Int get() = pool.online

    /**
     * Someone is being invited right now (the share dialog is open): announce every few seconds so
     * their phone finds this one straight away.
     */
    var eager = false
        set(value) {
            if (field == value) return
            field = value
            trace(if (value) "sharing: announcing often" else "sharing ended")
            searchInterval = SEARCH_INTERVAL_MS
            startAnnouncing(now = value)
        }

    fun start() {
        trace("started")
        prepareOffers()
        pool.register(this)
        startAnnouncing(now = true)
    }

    private fun startAnnouncing(now: Boolean) {
        announceJob?.cancel()
        announceJob = scope.launch {
            if (now) announce()
            while (isActive) {
                // Look often at first, then back off while nobody answers (the other phone may simply be
                // off); just stay discoverable once someone is connected.
                delay(nextAnnounceDelay())
                announce()
            }
        }
    }

    fun stop() {
        stopped = true
        announceJob?.cancel()
        pool.unregister(this)
        // Leave the tracker's room for this list (the shared connections stay open for other lists).
        val leave = buildJsonObject {
            put("action", "announce")
            put("info_hash", topic)
            put("peer_id", myPeerId)
            put("event", "stopped")
            put("numwant", 0)
            putJsonArray("offers") {}
        }
        trackers.forEach { it.send(leave) }
        preparing?.cancel()
        (pending.values + ready).forEach { it.peer.close() }
        pending.clear()
        ready.clear()
        peers.values.forEach { it.close() }
        peers.clear()
    }

    private fun nextAnnounceDelay(): Long = when {
        eager -> EAGER_INTERVAL_MS
        openPeers.isNotEmpty() -> ANNOUNCE_INTERVAL_MS.also { searchInterval = SEARCH_INTERVAL_MS }
        else -> searchInterval.also { searchInterval = minOf(it * 2, MAX_SEARCH_INTERVAL_MS) }
    }

    private fun nextDelayIsShort() = eager || (openPeers.isEmpty() && searchInterval <= OFFER_TTL_MS / 2)

    /** The network changed or the app came back: look for peers again now, and often. */
    fun kick() {
        searchInterval = SEARCH_INTERVAL_MS
        // Just started: it's already announcing.
        if (announceJob == null || SystemClock.elapsedRealtime() - startedAt < 3_000) return
        startAnnouncing(now = true)
    }

    /** Re-announce after a failed attempt, but at most every [RETRY_INTERVAL_MS]. */
    private suspend fun retrySoon() {
        if (openPeers.isNotEmpty() || stopped) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRetry < RETRY_INTERVAL_MS) return
        lastRetry = now
        announce()
    }

    fun drop(peer: Peer) {
        peer.close()
        peer.remotePeerId?.let { if (peers[it] === peer) peers.remove(it) }
        scope.launch {
            events.onPeerClosed(this@ListSwarm, peer)
            events.onChanged(this@ListSwarm)
        }
    }

    suspend fun onTrackerOpen(tracker: TrackerClient) {
        trace("tracker open ${tracker.url}")
        events.onChanged(this)
        announce(listOf(tracker))
    }

    /** Keeps [OFFERS_PER_ANNOUNCE] offers ready, creating any missing ones in parallel. */
    private fun prepareOffers() {
        if (preparing?.isActive == true || stopped) return
        preparing = scope.launch {
            val missing = OFFERS_PER_ANNOUNCE - ready.size
            (1..missing).map { async { createOffer() } }.awaitAll().filterNotNull().forEach { ready += it }
        }
    }

    private suspend fun createOffer(): PendingOffer? {
        val peer = Peer(factory, scope, this)
        return runCatching { PendingOffer(randomId(), peer, peer.createOffer()) }
            .onFailure { e -> Log.w(TAG, "Offer failed", e); peer.close() }
            .getOrNull()
    }

    /** Takes the prepared offers (making them now if none are ready yet). */
    private suspend fun takeOffers(): List<PendingOffer> {
        val cutoff = System.currentTimeMillis() - OFFER_TTL_MS / 2
        ready.filter { it.peer.createdAt < cutoff }.forEach { ready.remove(it); it.peer.close() }
        if (ready.isEmpty()) preparing?.join()
        if (ready.isEmpty()) prepareOffers().also { preparing?.join() }
        return ready.toList().also { ready.clear() }
    }

    /** Sends offers to [to]; offers nobody answered by the next round are discarded. */
    private suspend fun announce(to: List<TrackerClient> = trackers) {
        if (to.none { it.isOpen } || stopped) return
        expireOffers()
        val offersStarted = SystemClock.elapsedRealtime()
        val offers = takeOffers()
        // Have the next ones ready only if they'll be used before going stale.
        if (nextDelayIsShort()) prepareOffers()
        val online = to.filter { it.isOpen }
        if (stopped || online.isEmpty()) return offers.forEach { it.peer.close() }
        offers.forEach { pending[it.id] = it }
        val message = buildJsonObject {
            put("action", "announce")
            put("info_hash", topic)
            put("peer_id", myPeerId)
            put("numwant", offers.size)
            putJsonArray("offers") {
                offers.forEach { o ->
                    addJsonObject {
                        put("offer_id", o.id)
                        putJsonObject("offer") { put("type", "offer"); put("sdp", o.sdp) }
                    }
                }
            }
        }
        online.forEach { it.send(message) }
        trace("announced ${offers.size} offers to ${online.size} tracker(s); waited ${SystemClock.elapsedRealtime() - offersStarted}ms for offers")
    }

    private fun expireOffers() {
        val cutoff = System.currentTimeMillis() - OFFER_TTL_MS
        pending.values.filter { it.peer.createdAt < cutoff }.forEach { pending.remove(it.id); it.peer.close() }
        // Connections that never opened.
        peers.values.filter { !it.isOpen && it.createdAt < cutoff }.forEach { drop(it) }
    }

    suspend fun onTrackerMessage(tracker: TrackerClient, message: JsonObject) {
        if (message.string("info_hash") != topic) return
        val from = message.string("peer_id") ?: return
        val offerId = message.string("offer_id") ?: return
        if (from == myPeerId || stopped) return
        message["offer"]?.jsonObject?.string("sdp")?.let { handleOffer(tracker, from, offerId, it) }
        message["answer"]?.jsonObject?.string("sdp")?.let { handleAnswer(from, offerId, it) }
    }

    private suspend fun handleOffer(tracker: TrackerClient, from: String, offerId: String, sdp: String) {
        if (peers.containsKey(from)) return
        trace("offer from ${from.take(4)}")
        val peer = Peer(factory, scope, this).also { it.remotePeerId = from }
        peers[from] = peer
        val answerStarted = SystemClock.elapsedRealtime()
        val answer = runCatching { peer.acceptOffer(sdp) }.getOrElse { e ->
            Log.w(TAG, "Answer failed", e)
            drop(peer)
            return
        }
        tracker.send(buildJsonObject {
            put("action", "announce")
            put("info_hash", topic)
            put("peer_id", myPeerId)
            put("to_peer_id", from)
            put("offer_id", offerId)
            putJsonObject("answer") { put("type", "answer"); put("sdp", answer) }
        })
        trace("answered ${from.take(4)}; answer took ${SystemClock.elapsedRealtime() - answerStarted}ms")
        retryIfNotConnected(peer)
    }

    private suspend fun handleAnswer(from: String, offerId: String, sdp: String) {
        val offer = pending.remove(offerId) ?: return
        trace("answer from ${from.take(4)}")
        peers[from]?.let { existing ->
            // We also answered one of their offers. Both sides keep the lower peer id's offer.
            if (myPeerId > from) return offer.peer.close()
            peers.remove(from)
            existing.close()
        }
        offer.peer.remotePeerId = from
        peers[from] = offer.peer
        runCatching { offer.peer.acceptAnswer(sdp) }.onFailure { e ->
            Log.w(TAG, "Accepting answer failed", e)
            drop(offer.peer)
        }
        retryIfNotConnected(offer.peer)
    }

    /**
     * A direct connection opens within a few hundred ms when it's going to work, while WebRTC takes
     * ~15 s to give up. If [peer] isn't open soon, drop it and try again with fresh ports, which often
     * gets through a NAT that the first attempt didn't.
     */
    private fun retryIfNotConnected(peer: Peer) {
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val id = peer.remotePeerId ?: return@launch
            if (stopped || peer.isOpen || peers[id] !== peer) return@launch
            trace("connection to ${id.take(4)} didn't open in ${CONNECT_TIMEOUT_MS}ms; retrying")
            drop(peer)
            retrySoon()
        }
    }

    override suspend fun onOpen(peer: Peer) {
        if (peer.remotePeerId?.let { peers[it] } !== peer) return peer.close()
        trace("connected to ${peer.remotePeerId?.take(4)}")
        events.onPeerOpen(this, peer)
        events.onChanged(this)
    }

    override suspend fun onMessage(peer: Peer, text: String) = events.onPeerMessage(this, peer, text)

    override suspend fun onClosed(peer: Peer) {
        val id = peer.remotePeerId ?: return
        if (peers[id] !== peer) return
        val wasOpen = peer.isOpen
        drop(peer)
        // A connection attempt that failed: try again soon rather than at the next round.
        if (!wasOpen) retrySoon()
    }

    companion object {
        private const val TAG = "Intack"
        private const val ANNOUNCE_INTERVAL_MS = 30_000L
        private const val SEARCH_INTERVAL_MS = 10_000L
        private const val EAGER_INTERVAL_MS = 3_000L
        private const val MAX_SEARCH_INTERVAL_MS = 120_000L
        private const val RETRY_INTERVAL_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val OFFER_TTL_MS = 60_000L
        private const val OFFERS_PER_ANNOUNCE = 3
        private const val ID_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        /** 20 characters, the length trackers expect for peer and offer ids. */
        fun randomId(): String = (1..20).map { ID_CHARS.random() }.joinToString("")
    }
}

private fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
