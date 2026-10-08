package app.twodo.net

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Recent connection and sync events, for Settings → Connection log (also logcat tag `TwoDoSync`).
 * Lets a slow or failed connection on real networks be diagnosed without a computer.
 */
object SyncLog {
    private const val TAG = "TwoDoSync"
    private const val MAX_LINES = 500
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lines = ArrayDeque<String>()

    fun add(message: String) {
        Log.d(TAG, message)
        val line = "${LocalTime.now().format(time)} $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
