package app.twodo.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
 * answers are sent once ICE gathering finishes, so a tracker only needs to relay one message each way.
 * Events are delivered in [scope].
 */
class Peer(factory: PeerConnectionFactory, private val scope: CoroutineScope, private val events: PeerEvents) {
    /** The remote side's tracker peer id, once known. */
    var remotePeerId: String? = null
    var deviceName: String? = null
    val createdAt = System.currentTimeMillis()

    private val gathered = CompletableDeferred<Unit>()
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
        channel?.run { unregisterObserver(); close(); dispose() }
        pc.dispose()
    }

    private suspend fun gatheredSdp(): String {
        withTimeoutOrNull(GATHER_TIMEOUT_MS) { gathered.await() }
        return pc.localDescription?.description ?: error("No local description")
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
            if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
                scope.launch { events.onClosed(this@Peer) }
            }
        }

        override fun onDataChannel(dc: DataChannel) {
            scope.launch { if (!closed) attach(dc) else dc.dispose() }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private companion object {
        const val GATHER_TIMEOUT_MS = 5_000L

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
