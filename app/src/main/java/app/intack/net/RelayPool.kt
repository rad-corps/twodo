package app.intack.net

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Free public Nostr relays used as a fallback when phones can't connect directly, and to hold changes
 * for phones that are offline (events expire after [RELAY_EXPIRY_DAYS] days). Chosen October 2026: each
 * accepted Intack's event kind and 45 KB events, and returned them by author.
 */
val RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.primal.net",
    "wss://nostr.mom",
    "wss://relay.snort.social",
)

const val RELAY_EXPIRY_DAYS = 14

/** One WebSocket to a Nostr relay; reconnects with backoff until closed. Callbacks run in [scope]. */
private class NostrRelay(
    val url: String,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val onOpen: suspend (NostrRelay) -> Unit,
    private val onMessage: suspend (NostrRelay, JsonArray) -> Unit,
) {
    private var socket: WebSocket? = null
    var isOpen = false
        private set
    private var closed = false
    private var failures = 0
    private var reconnect: Job? = null
    /** Outgoing messages, sent one per [interval]: relays rate-limit (and briefly ban) bursts. */
    private val outbox = ArrayDeque<JsonArray>()
    private var sender: Job? = null
    var interval = BASE_INTERVAL_MS
        private set

    fun connect() {
        if (closed || socket != null) return
        reconnect?.cancel()
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    fun kick() {
        failures = 0
        if (socket == null) connect()
    }

    fun send(message: JsonArray): Boolean {
        if (!isOpen) return false
        outbox.addLast(message)
        if (sender?.isActive != true) {
            sender = scope.launch {
                while (outbox.isNotEmpty() && isOpen) {
                    socket?.send(outbox.removeFirst().toString())
                    delay(interval)
                }
            }
        }
        return true
    }

    /** The relay said we're sending too fast. */
    fun slowDown(banned: Boolean) {
        interval = if (banned) MAX_INTERVAL_MS else minOf(interval * 2, MAX_INTERVAL_MS)
    }

    /** An event was accepted: creep back towards the normal pace. */
    fun accepted() {
        interval = maxOf(BASE_INTERVAL_MS, interval - 100)
    }

    fun close() {
        closed = true
        reconnect?.cancel()
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
                onOpen(this@NostrRelay)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
            scope.launch { if (webSocket === socket) onMessage(this@NostrRelay, message) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, t.message)

        private fun dropped(webSocket: WebSocket, why: String?) {
            scope.launch {
                if (webSocket !== socket) return@launch
                SyncLog.add("relay $url disconnected: $why")
                socket = null
                isOpen = false
                outbox.clear() // recent events are re-sent when it reconnects
                if (closed) return@launch
                val wait = minOf(120_000L, 2_000L shl minOf(failures++, 6))
                reconnect = scope.launch { delay(wait); connect() }
            }
        }
    }
}

private const val BASE_INTERVAL_MS = 200L
private const val MAX_INTERVAL_MS = 5_000L

/**
 * Connections to the public Nostr relays, shared by all lists. Lists [subscribe] to their events and
 * [publish] signed events; each event goes to every relay, and incoming duplicates are dropped.
 * Use from [scope] only.
 */
class RelayPool(http: OkHttpClient, private val scope: CoroutineScope) {
    private class Subscription(val filter: JsonObject, val onEvent: suspend (NostrEvent) -> Unit, val onCaughtUp: suspend () -> Unit) {
        val caughtUpOn = mutableSetOf<String>()
    }

    private val relays = RELAYS.map { NostrRelay(it, http, scope, ::onOpen, ::onMessage) }
    private val subscriptions = mutableMapOf<String, Subscription>()
    private val seen = LinkedHashSet<String>()
    /** Recently published events, re-sent to relays that (re)connect, so nothing is lost while one is down. */
    private val recent = ArrayDeque<Pair<Long, NostrEvent>>()
    /** Retries so far for events a relay rate-limited, by relay url + event id. */
    private val retries = mutableMapOf<String, Int>()
    private val startedAt = SystemClock.elapsedRealtime()
    private val json = Json { ignoreUnknownKeys = true }

    val online: Int get() = relays.count { it.isOpen }

    fun start() {
        SyncLog.add("relays: connecting to ${relays.size}")
        relays.forEach { it.connect() }
    }

    fun close() = relays.forEach { it.close() }

    fun kick() = relays.forEach { it.kick() }

    /**
     * Calls [onEvent] for every matching event, stored or new, from any relay (once per event), and
     * [onCaughtUp] once the first relay has sent everything it had stored.
     */
    fun subscribe(id: String, filter: JsonObject, onEvent: suspend (NostrEvent) -> Unit, onCaughtUp: suspend () -> Unit) {
        subscriptions[id] = Subscription(filter, onEvent, onCaughtUp)
        relays.filter { it.isOpen }.forEach { it.send(req(id, filter)) }
    }

    fun unsubscribe(id: String) {
        if (subscriptions.remove(id) == null) return
        relays.filter { it.isOpen }.forEach { it.send(buildJsonArray { add(JsonPrimitive("CLOSE")); add(JsonPrimitive(id)) }) }
    }

    fun publish(event: NostrEvent) {
        seen += event.id // our own events come back through the subscription
        recent.addLast(SystemClock.elapsedRealtime() to event)
        trimRecent()
        val message = eventMessage(event)
        relays.filter { it.isOpen }.forEach { it.send(message) }
    }

    private suspend fun onOpen(relay: NostrRelay) {
        SyncLog.add("relay open after ${SystemClock.elapsedRealtime() - startedAt}ms: ${relay.url}")
        subscriptions.forEach { (id, sub) -> relay.send(req(id, sub.filter)) }
        trimRecent()
        recent.forEach { (_, event) -> relay.send(eventMessage(event)) }
    }

    private suspend fun onMessage(relay: NostrRelay, message: JsonArray) {
        when (message.getOrNull(0)?.jsonPrimitive?.content) {
            "EVENT" -> {
                val sub = subscriptions[message.getOrNull(1)?.jsonPrimitive?.content] ?: return
                val event = runCatching { json.decodeFromJsonElement(NostrEvent.serializer(), message[2]) }.getOrNull() ?: return
                if (!seen.add(event.id)) return
                while (seen.size > MAX_SEEN) seen.remove(seen.first())
                if (!NostrIdentity.isValid(event)) return
                sub.onEvent(event)
            }
            "EOSE" -> {
                val id = message.getOrNull(1)?.jsonPrimitive?.content ?: return
                val sub = subscriptions[id] ?: return
                val first = sub.caughtUpOn.isEmpty()
                sub.caughtUpOn += relay.url
                if (first) sub.onCaughtUp()
            }
            "OK" -> {
                val id = message.getOrNull(1)?.jsonPrimitive?.content ?: return
                val accepted = message.getOrNull(2)?.jsonPrimitive?.content == "true"
                if (accepted) return relay.accepted()
                val reason = message.getOrNull(3)?.jsonPrimitive?.content.orEmpty()
                val limited = reason.startsWith("rate-limited")
                val banned = reason.startsWith("banned")
                if (!limited && !banned) return SyncLog.add("relay ${relay.url} rejected an event: $reason")
                relay.slowDown(banned)
                // Try that event again on this relay later (the other relays usually have it already).
                val key = relay.url + id
                val attempt = (retries[key] ?: 0) + 1
                val event = recent.firstOrNull { it.second.id == id }?.second
                if (attempt > MAX_RETRIES || event == null) {
                    retries.remove(key)
                    return SyncLog.add("relay ${relay.url} gave up on an event: $reason")
                }
                retries[key] = attempt
                SyncLog.add("relay ${relay.url} $reason; retrying in ${relay.interval}ms")
                scope.launch {
                    delay(relay.interval)
                    relay.send(eventMessage(event))
                }
            }
            "NOTICE", "CLOSED" -> SyncLog.add("relay ${relay.url}: ${message.drop(1).joinToString(" ") { it.contentOrJson() }}")
        }
    }

    private fun trimRecent() {
        val cutoff = SystemClock.elapsedRealtime() - RESEND_WINDOW_MS
        while (recent.isNotEmpty() && recent.first().first < cutoff) recent.removeFirst()
    }

    private fun req(id: String, filter: JsonObject) =
        buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(id)); add(filter) }

    private fun eventMessage(event: NostrEvent) =
        buildJsonArray { add(JsonPrimitive("EVENT")); add(json.encodeToJsonElement(NostrEvent.serializer(), event)) }

    private companion object {
        const val MAX_SEEN = 5_000
        const val RESEND_WINDOW_MS = 120_000L
        const val MAX_RETRIES = 3
    }
}

private fun JsonElement.contentOrJson(): String = (this as? JsonPrimitive)?.content ?: toString()
