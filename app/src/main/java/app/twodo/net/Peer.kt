package app.twodo.net

import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface PeerEvents {
    suspend fun onOpen(peer: Peer)
    suspend fun onMessage(peer: Peer, text: String)
    suspend fun onClosed(peer: Peer)
}

/**
 * One WebRTC connection carrying a single ordered data channel. Signalling is non-trickle: offers and
 * answers carry their ICE candidates, so a tracker only needs to relay one message each way. They're
 * sent once a public (server-reflexive) address is known rather than when gathering fully finishes,
 * which on phones can take many seconds. Events are delivered in [scope].
 *
 * Closing frees WebRTC's native objects, and touching them after that kills the whole app (it's not
 * an exception that can be caught). So every use goes through [live], they're freed only once no use
 * is in progress, and anything after closing is skipped or fails as an ordinary exception.
 */
class Peer(factory: PeerConnectionFactory, private val scope: CoroutineScope, private val events: PeerEvents) {
    /** The remote side's tracker peer id, once known. */
    var remotePeerId: String? = null
    var deviceName: String? = null
    val createdAt = System.currentTimeMillis()

    private val gathered = CompletableDeferred<Unit>()
    private val gotPublicAddress = CompletableDeferred<Unit>()
    @Volatile private var channel: DataChannel? = null
    @Volatile private var closed = false
    private var openNotified = false

    private val lock = Object()
    /** Uses of [pc] or [channel] in progress (guarded by [lock]). */
    private var inUse = 0

    val isOpen: Boolean get() = live { channel?.state() == DataChannel.State.OPEN } == true

    private val pc: PeerConnection = factory.createPeerConnection(rtcConfig(), Observer())
        ?: error("Could not create PeerConnection")

    /** Runs [block], which uses the native objects, unless closed (then null). */
    private inline fun <T> live(block: () -> T): T? {
        synchronized(lock) {
            if (closed) return null
            inUse++
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                inUse--
                lock.notifyAll()
            }
        }
    }

    private fun closedError() = IllegalStateException("Connection closed")

    suspend fun createOffer(): String {
        attach(live { pc.createDataChannel("sync", DataChannel.Init().apply { ordered = true }) } ?: throw closedError())
        val offer = awaitCreate(offer = true)
        awaitSet(local = true, offer)
        return gatheredSdp()
    }

    suspend fun acceptOffer(sdp: String): String {
        awaitSet(local = false, SessionDescription(SessionDescription.Type.OFFER, sdp))
        val answer = awaitCreate(offer = false)
        awaitSet(local = true, answer)
        return gatheredSdp()
    }

    suspend fun acceptAnswer(sdp: String) =
        awaitSet(local = false, SessionDescription(SessionDescription.Type.ANSWER, sdp))

    fun send(text: String): Boolean = live {
        val dc = channel
        dc != null && dc.state() == DataChannel.State.OPEN && dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false))
    } == true

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        // Disposing blocks for a while (it waits on WebRTC's threads), so keep it off the sync thread.
        disposer.execute {
            synchronized(lock) { while (inUse > 0) lock.wait() }
            channel?.run { unregisterObserver(); close(); dispose() }
            pc.dispose()
        }
    }

    private suspend fun awaitCreate(offer: Boolean): SessionDescription = awaitSdp { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        }
        live { if (offer) pc.createOffer(observer, MediaConstraints()) else pc.createAnswer(observer, MediaConstraints()) }
    }

    private suspend fun awaitSet(local: Boolean, sdp: SessionDescription): Unit = awaitSdp { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
        }
        live { if (local) pc.setLocalDescription(observer, sdp) else pc.setRemoteDescription(observer, sdp) }
    }

    /**
     * Starts creating or setting a description with [start] (which returns null if already closed) and
     * waits for WebRTC's answer. One closed part-way may never answer, hence the time limit.
     */
    private suspend fun <T> awaitSdp(start: (Continuation<T>) -> Unit?): T =
        withTimeoutOrNull(SDP_TIMEOUT_MS) {
            suspendCancellableCoroutine<T> { cont -> if (start(cont) == null) cont.resumeWithException(closedError()) }
        } ?: throw if (closed) closedError() else IllegalStateException("No answer from WebRTC")

    /**
     * The local description once it's worth sending: when gathering completes, shortly after the first
     * public address arrives, or at [GATHER_CAP_MS] with whatever was found (e.g. if STUN is blocked).
     */
    private suspend fun gatheredSdp(): String {
        val started = SystemClock.elapsedRealtime()
        coroutineScope {
            val afterPublic = async { gotPublicAddress.await(); delay(PUBLIC_ADDRESS_GRACE_MS) }
            withTimeoutOrNull(GATHER_CAP_MS) {
                select {
                    gathered.onAwait { }
                    afterPublic.onAwait { }
                }
            }
            afterPublic.cancel()
        }
        // It may have been closed while waiting.
        val sdp = live { pc.localDescription?.description } ?: throw if (closed) closedError() else IllegalStateException("No local description")
        val types = Regex("""typ (\w+)""").findAll(sdp).map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val took = SystemClock.elapsedRealtime() - started
        // Only the slow or unusual ones are worth a line in the connection log.
        if (took > SLOW_GATHER_MS || "srflx" !in types) {
            SyncLog.add("peer ${hashCode().toString(16)} sdp ready in ${took}ms, candidates $types")
        }
        return sdp
    }

    private fun attach(dc: DataChannel) {
        channel = dc
        val state = live {
            dc.registerObserver(observe(dc))
            dc.state()
        }
        // The answering side gets the channel via onDataChannel, possibly after it already opened.
        if (state == DataChannel.State.OPEN) notifyOpen()
    }

    private fun observe(dc: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            when (live { dc.state() }) {
                DataChannel.State.OPEN -> notifyOpen()
                DataChannel.State.CLOSED -> scope.launch { events.onClosed(this@Peer) }
                else -> Unit
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary || closed) return
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            scope.launch { events.onMessage(this@Peer, String(bytes)) }
        }
    }

    private fun notifyOpen() {
        scope.launch {
            if (openNotified || closed) return@launch
            openNotified = true
            events.onOpen(this@Peer)
        }
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            SyncLog.add("peer ${this@Peer.hashCode().toString(16)} connection $state")
            if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
                scope.launch { events.onClosed(this@Peer) }
            }
        }

        override fun onDataChannel(dc: DataChannel) {
            scope.launch {
                // Closed already: free it after the connection (in order, on the same thread).
                if (closed) disposer.execute { dc.dispose() } else attach(dc)
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            SyncLog.add("peer ${this@Peer.hashCode().toString(16)} ice $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            if (" typ srflx" in candidate.sdp) gotPublicAddress.complete(Unit)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            fun type(c: IceCandidate) = Regex(""" typ (\w+)""").find(c.sdp)?.groupValues?.get(1) ?: "?"
            SyncLog.add("peer ${this@Peer.hashCode().toString(16)} path: ${type(event.local)} -> ${type(event.remote)}")
        }
    }

    private companion object {
        val disposer: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        const val GATHER_CAP_MS = 1_500L
        const val SLOW_GATHER_MS = 400L
        /** Other interfaces' public addresses usually follow within a moment of the first. */
        const val PUBLIC_ADDRESS_GRACE_MS = 150L
        /** Creating or setting a description takes milliseconds; this is only for one that never answers. */
        const val SDP_TIMEOUT_MS = 10_000L

        fun rtcConfig() = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"))
                    .createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            ),
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
    }
}
