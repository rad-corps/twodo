package app.intack.sync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.intack.R
import app.intack.IntackApp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Best-effort catch-up every 15 minutes while online: joins every list's swarm for a short window. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as IntackApp).sync
        sync.acquire()
        try {
            delay(SYNC_WINDOW_MS)
        } finally {
            sync.release()
        }
        return Result.success()
    }

    companion object {
        private const val SYNC_WINDOW_MS = 60_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Optional: keeps connections up while the app isn't visible, so the other phone can reach this one. */
class SyncService : Service() {
    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        (application as IntackApp).sync.acquire()
    }

    override fun onDestroy() {
        (application as IntackApp).sync.release()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification =
        NotificationCompat.Builder(this, Notifications.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.sync_service_title))
            .setContentText(getString(R.string.sync_service_text))
            .setOngoing(true)
            .setContentIntent(Notifications.openAppIntent(this))
            .build()

    companion object {
        private const val NOTIFICATION_ID = 1

        fun setEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, SyncService::class.java)
            if (enabled) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}
