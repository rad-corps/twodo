package app.intack

import android.app.Application
import app.intack.data.Identity
import app.intack.data.ListRepository
import app.intack.sync.ListEvent
import app.intack.sync.Notifications
import app.intack.sync.SyncManager
import app.intack.sync.SyncService
import app.intack.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class IntackApp : Application() {
    lateinit var identity: Identity
        private set
    lateinit var repo: ListRepository
        private set
    lateinit var sync: SyncManager
        private set

    lateinit var appTheme: MutableStateFlow<String>
        private set

    /** Number of started activities; conflicts are shown in-app while visible, as notifications otherwise. */
    var visibleActivities = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        identity = Identity(this)
        repo = ListRepository(File(filesDir, "lists"), identity)
        sync = SyncManager(this, repo, identity)
        appTheme = MutableStateFlow(identity.themeId)
        Notifications.createChannels(this)
        SyncWorker.schedule(this)
        scope.launch {
            repo.conflicts.collect { if (visibleActivities == 0) Notifications.showConflict(this@IntackApp, it) }
        }
        scope.launch {
            sync.events.collect { event ->
                when (event) {
                    // People coming and going always gets a pull-down notification.
                    is ListEvent.Joined, is ListEvent.Left -> Notifications.showPeople(this@IntackApp, event)
                    // Changes are highlighted in the app when it's open; otherwise one quiet notification per list.
                    is ListEvent.Changed ->
                        if (visibleActivities == 0 && identity.notifyChanges) Notifications.addChanges(this@IntackApp, event)
                    // Shown in the app (it's the joining phone, so the user is looking at it).
                    is ListEvent.JoinedList -> Unit
                }
            }
        }
    }

    fun setName(name: String) {
        if (name.trim() == identity.deviceName && identity.hasName) return
        identity.deviceName = name
        sync.nameChanged()
    }

    fun setTheme(themeId: String) {
        identity.themeId = themeId
        appTheme.value = themeId
    }

    fun setBackgroundSync(enabled: Boolean) {
        identity.backgroundSync = enabled
        SyncService.setEnabled(this, enabled)
    }
}
