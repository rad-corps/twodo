package app.twodo.net

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * One connection per public tracker, shared by every list. Opening a tracker connection costs several
 * network round trips (DNS, TCP, TLS, WebSocket upgrade) — over a second on mobile data to an overseas
 * tracker — so they're opened once when syncing starts and kept, and a newly joined list announces
 * over connections that are already open. Messages are routed to lists by room id (`info_hash`).
 * Use from [scope] only.
 */
class TrackerPool(http: OkHttpClient, private val scope: CoroutineScope) {
    private val swarms = mutableMapOf<String, ListSwarm>()
    private val startedAt = SystemClock.elapsedRealtime()

    val clients = TRACKERS.map { TrackerClient(it, http, scope, ::onOpen, ::onMessage) }
    val online: Int get() = clients.count { it.isOpen }

    fun start() {
        SyncLog.add("trackers: connecting to ${clients.size}")
        clients.forEach { it.connect() }
    }

    fun close() = clients.forEach { it.close() }

    /** The network changed: reconnect now instead of waiting for the backoff. */
    fun kick() = clients.forEach { it.kick() }

    fun register(swarm: ListSwarm) {
        swarms[swarm.topic] = swarm
    }

    fun unregister(swarm: ListSwarm) {
        if (swarms[swarm.topic] === swarm) swarms.remove(swarm.topic)
    }

    private suspend fun onOpen(tracker: TrackerClient) {
        SyncLog.add("tracker open after ${SystemClock.elapsedRealtime() - startedAt}ms: ${tracker.url}")
        swarms.values.toList().forEach { it.onTrackerOpen(tracker) }
    }

    private suspend fun onMessage(tracker: TrackerClient, message: JsonObject) {
        val topic = runCatching { message["info_hash"]?.jsonPrimitive?.content }.getOrNull() ?: return
        swarms[topic]?.onTrackerMessage(tracker, message)
    }
}
