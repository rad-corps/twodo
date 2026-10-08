package app.twodo.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WebSocket connection to one public WebTorrent tracker, used only to exchange WebRTC offers/answers
 * with other devices in the same room. Reconnects with backoff until [close]d.
 * Callbacks run in [scope].
 */
class TrackerClient(
    val url: String,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val onOpen: suspend (TrackerClient) -> Unit,
    private val onMessage: suspend (TrackerClient, JsonObject) -> Unit,
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile var isOpen = false
        private set
    private var closed = false
    private var failures = 0
    private var reconnectJob: Job? = null

    fun connect() {
        if (closed || socket != null) return
        reconnectJob?.cancel()
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    /** Reconnect now instead of waiting for the backoff (e.g. the network just came back). */
    fun kick() {
        failures = 0
        if (socket == null) connect()
    }

    fun send(message: JsonObject): Boolean = isOpen && socket?.send(message.toString()) == true

    fun close() {
        closed = true
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        isOpen = false
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch {
                if (webSocket !== socket) return@launch
                isOpen = true
                failures = 0
                onOpen(this@TrackerClient)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            scope.launch { if (webSocket === socket) onMessage(this@TrackerClient, message) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, t)

        private fun dropped(webSocket: WebSocket, error: Throwable?) {
            scope.launch {
                if (webSocket !== socket) return@launch
                SyncLog.add("tracker $url disconnected: ${error?.message}")
                socket = null
                isOpen = false
                if (closed) return@launch
                val wait = minOf(60_000L, 2_000L shl minOf(failures++, 5))
                reconnectJob = scope.launch { delay(wait); connect() }
            }
        }
    }

    private companion object {
        const val TAG = "TwoDo"
    }
}
