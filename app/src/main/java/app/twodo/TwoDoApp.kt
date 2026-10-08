package app.twodo

import android.app.Application
import app.twodo.data.Identity
import app.twodo.data.ListRepository
import app.twodo.sync.ListEvent
import app.twodo.sync.Notifications
import app.twodo.sync.SyncManager
import app.twodo.sync.SyncService
import app.twodo.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class TwoDoApp : Application() {
    lateinit var identity: Identity
        private set
    lateinit var repo: ListRepository
        private set
    lateinit var sync: SyncManager
        private set

    lateinit var darkMode: MutableStateFlow<Boolean>
        private set

    /** Number of started activities; conflicts are shown in-app while visible, as notifications otherwise. */
    var visibleActivities = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        identity = Identity(this)
        repo = ListRepository(File(filesDir, "lists"), identity)
        sync = SyncManager(this, repo, identity)
        darkMode = MutableStateFlow(identity.darkMode)
        Notifications.createChannels(this)
        SyncWorker.schedule(this)
        scope.launch {
            repo.conflicts.collect { if (visibleActivities == 0) Notifications.showConflict(this@TwoDoApp, it) }
        }
        scope.launch {
            sync.events.collect { event ->
                when (event) {
                    // People coming and going always gets a pull-down notification.
                    is ListEvent.Joined, is ListEvent.Left -> Notifications.showPeople(this@TwoDoApp, event)
                    // Changes are highlighted in the app when it's open; otherwise one quiet notification per list.
                    is ListEvent.Changed ->
                        if (visibleActivities == 0 && identity.notifyChanges) Notifications.addChanges(this@TwoDoApp, event)
                }
            }
        }
    }

    fun setName(name: String) {
        if (name.trim() == identity.deviceName && identity.hasName) return
        identity.deviceName = name
        sync.nameChanged()
    }

    fun setDarkMode(enabled: Boolean) {
        identity.darkMode = enabled
        darkMode.value = enabled
    }

    fun setBackgroundSync(enabled: Boolean) {
        identity.backgroundSync = enabled
        SyncService.setEnabled(this, enabled)
    }
}
