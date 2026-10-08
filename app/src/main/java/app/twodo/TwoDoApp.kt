package app.twodo

import android.app.Application
import app.twodo.data.Identity
import app.twodo.data.ListRepository
import app.twodo.sync.Notifications
import app.twodo.sync.SyncManager
import app.twodo.sync.SyncService
import app.twodo.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class TwoDoApp : Application() {
    lateinit var identity: Identity
        private set
    lateinit var repo: ListRepository
        private set
    lateinit var sync: SyncManager
        private set

    /** Number of started activities; conflicts are shown in-app while visible, as notifications otherwise. */
    var visibleActivities = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        identity = Identity(this)
        repo = ListRepository(File(filesDir, "lists"), identity)
        sync = SyncManager(this, repo, identity)
        Notifications.createChannels(this)
        SyncWorker.schedule(this)
        scope.launch {
            repo.conflicts.collect { if (visibleActivities == 0) Notifications.showConflict(this@TwoDoApp, it) }
        }
    }

    fun setBackgroundSync(enabled: Boolean) {
        identity.backgroundSync = enabled
        SyncService.setEnabled(this, enabled)
    }
}
