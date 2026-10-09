package app.twodo.data

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
