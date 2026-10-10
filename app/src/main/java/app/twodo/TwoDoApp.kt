package app.twodo

import android.app.Application
import app.twodo.data.CrashLog
import app.twodo.data.Identity
import app.twodo.data.ListRepository
import app.twodo.model.SpaceKind
import app.twodo.sync.CalendarAlerts
import app.twodo.sync.ListEvent
import app.twodo.sync.Notifications
import app.twodo.sync.SyncManager
import app.twodo.sync.SyncService
import app.twodo.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File

@OptIn(FlowPreview::class)
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

    /** This person's chosen colour, or null for the one derived from their device. */
    lateinit var myColor: MutableStateFlow<Long?>
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
        runCatching { CrashLog.noteNativeCrashes(this) }
        identity = Identity(this)
        repo = ListRepository(File(filesDir, "lists"), identity)
        sync = SyncManager(this, repo, identity)
        appTheme = MutableStateFlow(identity.themeId)
        textScale = MutableStateFlow(identity.textScale)
        myColor = MutableStateFlow(identity.myColor)
        Notifications.createChannels(this)
        SyncWorker.schedule(this)
        // Calendar changes (from anyone) can move the next reminder; settle for a moment, then set it.
        scope.launch {
            repo.lists.debounce(2_000).collect { CalendarAlerts.reschedule(this@TwoDoApp) }
        }
        scope.launch {
            repo.conflicts.collect { if (visibleActivities == 0 && identity.notifyConflicts) Notifications.showConflict(this@TwoDoApp, it) }
        }
        scope.launch {
            sync.events.collect { event ->
                when (event) {
                    // People coming and going always gets a pull-down notification.
                    is ListEvent.Joined -> if (identity.notifyJoined) Notifications.showPeople(this@TwoDoApp, event)
                    is ListEvent.Left -> if (identity.notifyLeft) Notifications.showPeople(this@TwoDoApp, event)
                    // Changes are highlighted in the app when it's open; otherwise one notification per list,
                    // with just the kinds of change this phone wants to hear about.
                    is ListEvent.Changed -> if (visibleActivities == 0) Notifications.addChanges(this@TwoDoApp, event, wantedLines(event))
                    // Shown in the app (it's the joining phone, so the user is looking at it).
                    is ListEvent.JoinedList -> Unit
                }
            }
        }
    }

    /** The lines of [event] this phone's notification settings want. */
    private fun wantedLines(event: ListEvent.Changed): List<String> = event.lines.filterIndexed { i, _ ->
        val added = event.added.getOrElse(i) { false }
        when (event.kind) {
            SpaceKind.DIARY -> if (added) identity.notifyCalendarAdded else identity.notifyCalendarChanged
            SpaceKind.LIST -> if (added) identity.notifyListAdded else identity.notifyListTicked
            SpaceKind.GROUP -> identity.notifyGroupChanges
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

    /** Sets this person's colour and tells the others (it travels with their name). */
    fun setColor(color: Long) {
        if (identity.myColor == color) return
        identity.myColor = color
        myColor.value = color
        sync.nameChanged()
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
