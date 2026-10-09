package app.intack.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.intack.MainActivity
import app.intack.R
import app.intack.model.Conflict
import app.intack.model.describe

object Notifications {
    const val CHANNEL_CONFLICTS = "conflicts"
    const val CHANNEL_SYNC = "sync"
    const val CHANNEL_PEOPLE = "people"
    const val CHANNEL_CHANGES = "changes"
    const val EXTRA_LIST_ID = "app.intack.LIST_ID"

    private const val MAX_LINES = 6

    /** Change lines shown in each list's notification since it was last opened. */
    private val pendingChanges = mutableMapOf<String, MutableList<String>>()

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_CONFLICTS, context.getString(R.string.channel_conflicts), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_SYNC, context.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CHANNEL_PEOPLE, context.getString(R.string.channel_people), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_CHANGES, context.getString(R.string.channel_changes), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    /** "Sam joined Groceries" / "Sam left Groceries". */
    fun showPeople(context: Context, event: ListEvent) {
        val text = when (event) {
            is ListEvent.Joined -> "${event.who} joined ${event.listName}"
            is ListEvent.Left -> "${event.who} left ${event.listName}"
            else -> return
        }
        notify(
            context, "people", (event.listId + text).hashCode(),
            NotificationCompat.Builder(context, CHANNEL_PEOPLE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text)
                .setContentIntent(openAppIntent(context, event.listId))
                .setAutoCancel(true),
        )
    }

    /** Adds to the list's quiet "what changed" notification, e.g. "Sam ticked Milk". */
    fun addChanges(context: Context, event: ListEvent.Changed) {
        val lines = pendingChanges.getOrPut(event.listId) { mutableListOf() }
        lines += event.lines.map { "${event.who} $it" }
        val style = NotificationCompat.InboxStyle()
        lines.takeLast(MAX_LINES).forEach(style::addLine)
        if (lines.size > MAX_LINES) style.setSummaryText("+${lines.size - MAX_LINES} more")
        notify(
            context, "changes", event.listId.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_CHANGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(event.listName)
                .setContentText(lines.last())
                .setStyle(style)
                .setNumber(lines.size)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(context, event.listId))
                .setAutoCancel(true),
        )
    }

    /** The user is looking at the list, so its changes notification is no longer needed. */
    fun clearChanges(context: Context, listId: String) {
        pendingChanges.remove(listId)
        NotificationManagerCompat.from(context).cancel("changes", listId.hashCode())
    }

    private fun notify(context: Context, tag: String, id: Int, builder: NotificationCompat.Builder) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context).notify(tag, id, builder.build())
    }

    private fun canNotify(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showConflict(context: Context, conflict: Conflict) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_CONFLICTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.conflict_title, conflict.listName))
            .setContentText(conflict.describe())
            .setStyle(NotificationCompat.BigTextStyle().bigText(conflict.describe()))
            .setContentIntent(openAppIntent(context, conflict.listId))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(conflict.winner.id.hashCode(), notification)
    }

    /** Opens the app, on [listId] if given. */
    fun openAppIntent(context: Context, listId: String? = null): PendingIntent = PendingIntent.getActivity(
        context, listId.hashCode(),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_LIST_ID, listId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
