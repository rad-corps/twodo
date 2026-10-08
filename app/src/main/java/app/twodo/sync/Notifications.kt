package app.twodo.sync

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
import app.twodo.MainActivity
import app.twodo.R
import app.twodo.model.Conflict
import app.twodo.model.Item

object Notifications {
    const val CHANNEL_CONFLICTS = "conflicts"
    const val CHANNEL_SYNC = "sync"

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_CONFLICTS, context.getString(R.string.channel_conflicts), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_SYNC, context.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_MIN),
            ),
        )
    }

    fun showConflict(context: Context, conflict: Conflict) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_CONFLICTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.conflict_title, conflict.listName))
            .setContentText(conflict.describe())
            .setStyle(NotificationCompat.BigTextStyle().bigText(conflict.describe()))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(conflict.winner.id.hashCode(), notification)
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}

/** e.g. "“Milk”: your change (checked) was replaced by Pixel 8's newer change (deleted)." */
fun Conflict.describe(): String {
    val name = winner.text.ifBlank { loser.text }
    return if (localLost) {
        "“$name”: your change (${loser.state()}) was replaced by ${winner.editor}'s newer change (${winner.state()})."
    } else {
        "“$name”: your newer change (${winner.state()}) replaced ${loser.editor}'s change (${loser.state()})."
    }
}

private fun Item.state(): String = when {
    deleted -> "deleted"
    checked -> "checked"
    else -> "unchecked"
}
