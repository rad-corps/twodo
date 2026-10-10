package app.twodo.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.twodo.BuildConfig
import app.twodo.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Crashes, noted on this phone only. Nothing is sent anywhere unless the user chooses to email them
 * (see [Identity.offerCrashReports]); the email opens in their own mail app for them to send.
 */
object CrashLog {
    private const val KEEP = 10
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /** Notes crashes, then lets Android carry on as usual (closing the app). */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** One crash per file, named by time so the newest sort last; only the last [KEEP] are kept. */
    private fun record(context: Context, thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("Crash at ${stamp.format(Instant.ofEpochMilli(now))} on thread ${thread.name}")
            append(trace)
        }
        dir(context).apply { mkdirs() }.resolve("$now.txt").writeText(report)
        reports(context).dropLast(KEEP).forEach { it.delete() }
    }

    /**
     * Crashes in native code (WebRTC, for one) end the app without reaching [install]'s handler, but
     * Android remembers why the app last ended (Android 11+). Notes any such crash not yet noted, with
     * the readable parts of Android's crash record (its function names).
     */
    fun noteNativeCrashes(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
        val since = prefs.getLong("nativeSeenUpTo", 0)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, KEEP)
            .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestamp > since }
        if (exits.isEmpty()) return
        exits.forEach { exit ->
            val report = buildString {
                appendLine("Native crash at ${stamp.format(Instant.ofEpochMilli(exit.timestamp))} in ${exit.processName}: ${exit.description}")
                readableParts(exit).forEach { appendLine("  $it") }
            }
            dir(context).apply { mkdirs() }.resolve("${exit.timestamp}.txt").writeText(report)
        }
        prefs.edit().putLong("nativeSeenUpTo", exits.maxOf { it.timestamp }).apply()
        reports(context).dropLast(KEEP).forEach { it.delete() }
    }

    /** Function names from the crash record (a binary file), in order: enough to see where it happened. */
    private fun readableParts(exit: ApplicationExitInfo): List<String> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        val bytes = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: return emptyList()
        return Regex("""[A-Za-z_$][\w$.:<>~]{5,}(?:\([^)]{0,80}\))?(?:\+\d+)?""")
            .findAll(String(bytes, Charsets.ISO_8859_1))
            .map { it.value }
            .filter { '.' in it || '_' in it || "::" in it }
            .distinct()
            .take(40)
            .toList()
    }

    private fun dir(context: Context) = File(context.filesDir, "crashes")

    /** Saved crashes, oldest first. */
    fun reports(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }

    /** Crashes since the user was last asked about them. */
    fun unseen(context: Context, identity: Identity): List<File> =
        reports(context).filter { (it.nameWithoutExtension.toLongOrNull() ?: 0) > identity.crashesSeenUpTo }

    fun markSeen(context: Context, identity: Identity) {
        reports(context).lastOrNull()?.nameWithoutExtension?.toLongOrNull()?.let { identity.crashesSeenUpTo = it }
    }

    fun clear(context: Context) = reports(context).forEach { it.delete() }

    /**
     * Opens the user's email app with [reports] written out, addressed to the developer. They see
     * exactly what's sent and send it themselves. Returns false if there's no email app.
     */
    fun email(context: Context, reports: List<File>): Boolean {
        val app = context.getString(R.string.brand_name)
        val body = buildString {
            appendLine("$app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine("Anything you were doing when it happened (optional):")
            appendLine()
            appendLine()
            reports.forEach { appendLine("----"); append(it.readText()) }
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.support_email)))
            .putExtra(Intent.EXTRA_SUBJECT, "$app crash report (${BuildConfig.VERSION_NAME})")
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
