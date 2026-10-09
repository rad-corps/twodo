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
 */
class Peer(factory: PeerConnectionFactory, private val scope: CoroutineScope, private val events: PeerEvents) {
    /** The remote side's tracker peer id, once known. */
    var remotePeerId: String? = null
    var deviceName: String? = null
    val createdAt = System.currentTimeMillis()

    private val gathered = CompletableDeferred<Unit>()
    private val gotPublicAddress = CompletableDeferred<Unit>()
    private var channel: DataChannel? = null
    private var closed = false
    private var openNotified = false

    val isOpen: Boolean get() = !closed && channel?.state() == DataChannel.State.OPEN

    private val pc: PeerConnection = factory.createPeerConnection(rtcConfig(), Observer())
        ?: error("Could not create PeerConnection")

    suspend fun createOffer(): String {
        attach(pc.createDataChannel("sync", DataChannel.Init().apply { ordered = true }))
        val offer = pc.awaitCreate(offer = true)
        pc.awaitSet(local = true, offer)
        return gatheredSdp()
    }

    suspend fun acceptOffer(sdp: String): String {
        pc.awaitSet(local = false, SessionDescription(SessionDescription.Type.OFFER, sdp))
        val answer = pc.awaitCreate(offer = false)
        pc.awaitSet(local = true, answer)
        return gatheredSdp()
    }

    suspend fun acceptAnswer(sdp: String) =
        pc.awaitSet(local = false, SessionDescription(SessionDescription.Type.ANSWER, sdp))

    fun send(text: String): Boolean =
        isOpen && channel?.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false)) == true

    fun close() {
        if (closed) return
        closed = true
        val dc = channel
        dc?.unregisterObserver()
        // Disposing blocks for a while (it waits on WebRTC's threads), so keep it off the sync thread.
        disposer.execute {
            dc?.run { close(); dispose() }
            pc.dispose()
        }
    }

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
        val sdp = pc.localDescription?.description ?: error("No local description")
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
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> notifyOpen()
                    DataChannel.State.CLOSED -> scope.launch { events.onClosed(this@Peer) }
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                scope.launch { events.onMessage(this@Peer, String(bytes)) }
            }
        })
        // The answering side gets the channel via onDataChannel, possibly after it already opened.
        if (dc.state() == DataChannel.State.OPEN) notifyOpen()
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
            scope.launch { if (!closed) attach(dc) else dc.dispose() }
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

private suspend fun PeerConnection.awaitCreate(offer: Boolean): SessionDescription =
    suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        }
        if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
    }

private suspend fun PeerConnection.awaitSet(local: Boolean, sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
        }
        if (local) setLocalDescription(observer, sdp) else setRemoteDescription(observer, sdp)
    }
