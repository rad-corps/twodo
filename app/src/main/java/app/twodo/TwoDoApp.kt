package app.twodo

import android.app.Application
import app.twodo.data.CrashLog
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

    lateinit var appTheme: MutableStateFlow<String>
        private set

    lateinit var textScale: MutableStateFlow<Float>
        private set

    /** Number of started activities; conflicts are shown in-app while visible, as notifications otherwise. */
    var visibleActivities = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Runs a change that must finish even if the screen that asked for it closes (e.g. saving from a
     * dialog that dismisses itself). A screen's own coroutine scope is cancelled when it goes.
     */
    fun save(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        identity = Identity(this)
        repo = ListRepository(File(filesDir, "lists"), identity)
        sync = SyncManager(this, repo, identity)
        appTheme = MutableStateFlow(identity.themeId)
        textScale = MutableStateFlow(identity.textScale)
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

    fun setTextScale(scale: Float) {
        identity.textScale = scale
        textScale.value = scale
    }

    fun setBackgroundSync(enabled: Boolean) {
        identity.backgroundSync = enabled
        SyncService.setEnabled(this, enabled)
    }
}
