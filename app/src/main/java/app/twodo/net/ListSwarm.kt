package app.twodo.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import okhttp3.OkHttpClient
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
 * answer through the tracker. To avoid two connections per pair, only the device with the higher
 * tracker peer id answers. All methods must be called in [scope], which must be single-threaded.
 */
class ListSwarm(
    val listId: String,
    private val topic: String,
    private val myPeerId: String,
    private val factory: PeerConnectionFactory,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val events: SwarmEvents,
) : PeerEvents {
    private class PendingOffer(val id: String, val peer: Peer, val sdp: String)

    private val trackers = TRACKERS.map { TrackerClient(it, http, scope, ::onTrackerOpen, ::onTrackerMessage) }
    private val pending = mutableMapOf<String, PendingOffer>()
    private val peers = mutableMapOf<String, Peer>()
    private var announceJob: Job? = null
    private var lastEagerAnnounce = 0L
    private var stopped = false

    val openPeers: List<Peer> get() = peers.values.filter { it.isOpen }
    val trackersOnline: Int get() = trackers.count { it.isOpen }

    fun start() {
        trackers.forEach { it.connect() }
        announceJob = scope.launch {
            while (isActive) {
                delay(ANNOUNCE_INTERVAL_MS)
                announce()
            }
        }
    }

    fun stop() {
        stopped = true
        announceJob?.cancel()
        trackers.forEach { it.close() }
        pending.values.forEach { it.peer.close() }
        pending.clear()
        peers.values.forEach { it.close() }
        peers.clear()
    }

    /** The network changed: reconnect trackers now and look for peers again. */
    fun kick() {
        trackers.forEach { it.kick() }
        scope.launch { announce() }
    }

    fun drop(peer: Peer) {
        peer.close()
        peer.remotePeerId?.let { if (peers[it] === peer) peers.remove(it) }
        scope.launch {
            events.onPeerClosed(this@ListSwarm, peer)
            events.onChanged(this@ListSwarm)
        }
    }

    private suspend fun onTrackerOpen(tracker: TrackerClient) {
        events.onChanged(this)
        announce(listOf(tracker))
    }

    /** Sends fresh offers to [to]; offers nobody answered by the next round are discarded. */
    private suspend fun announce(to: List<TrackerClient> = trackers) {
        val online = to.filter { it.isOpen }
        if (online.isEmpty() || stopped) return
        expireOffers()
        val offers = (1..OFFERS_PER_ANNOUNCE).mapNotNull {
            val peer = Peer(factory, scope, this)
            runCatching { PendingOffer(randomId(), peer, peer.createOffer()) }
                .onFailure { e -> Log.w(TAG, "Offer failed", e); peer.close() }
                .getOrNull()
        }
        if (stopped) return offers.forEach { it.peer.close() }
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
    }

    private fun expireOffers() {
        val cutoff = System.currentTimeMillis() - OFFER_TTL_MS
        pending.values.filter { it.peer.createdAt < cutoff }.forEach { pending.remove(it.id); it.peer.close() }
        // Connections that never opened.
        peers.values.filter { !it.isOpen && it.createdAt < cutoff }.forEach { drop(it) }
    }

    private suspend fun onTrackerMessage(tracker: TrackerClient, message: JsonObject) {
        if (message.string("info_hash") != topic) return
        val from = message.string("peer_id") ?: return
        val offerId = message.string("offer_id") ?: return
        if (from == myPeerId || stopped) return
        message["offer"]?.jsonObject?.string("sdp")?.let { handleOffer(tracker, from, offerId, it) }
        message["answer"]?.jsonObject?.string("sdp")?.let { handleAnswer(from, offerId, it) }
    }

    private suspend fun handleOffer(tracker: TrackerClient, from: String, offerId: String, sdp: String) {
        if (peers.containsKey(from)) return
        if (from > myPeerId) {
            // They're waiting for us to answer them; send our offers now rather than at the next round.
            if (System.currentTimeMillis() - lastEagerAnnounce > ANNOUNCE_INTERVAL_MS) {
                lastEagerAnnounce = System.currentTimeMillis()
                announce()
            }
            return
        }
        val peer = Peer(factory, scope, this).also { it.remotePeerId = from }
        peers[from] = peer
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
    }

    private suspend fun handleAnswer(from: String, offerId: String, sdp: String) {
        val offer = pending.remove(offerId) ?: return
        if (peers.containsKey(from)) return offer.peer.close()
        offer.peer.remotePeerId = from
        peers[from] = offer.peer
        runCatching { offer.peer.acceptAnswer(sdp) }.onFailure { e ->
            Log.w(TAG, "Accepting answer failed", e)
            drop(offer.peer)
        }
    }

    override suspend fun onOpen(peer: Peer) {
        if (peer.remotePeerId?.let { peers[it] } !== peer) return peer.close()
        events.onPeerOpen(this, peer)
        events.onChanged(this)
    }

    override suspend fun onMessage(peer: Peer, text: String) = events.onPeerMessage(this, peer, text)

    override suspend fun onClosed(peer: Peer) {
        val id = peer.remotePeerId ?: return
        if (peers[id] === peer) drop(peer)
    }

    companion object {
        private const val TAG = "TwoDo"
        private const val ANNOUNCE_INTERVAL_MS = 30_000L
        private const val OFFER_TTL_MS = 60_000L
        private const val OFFERS_PER_ANNOUNCE = 3
        private const val ID_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        /** 20 characters, the length trackers expect for peer and offer ids. */
        fun randomId(): String = (1..20).map { ID_CHARS.random() }.joinToString("")
    }
}

private fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
