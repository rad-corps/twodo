package app.twodo.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import java.util.UUID

/** This device's identity and user settings. */
class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString("deviceId", null)
        ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString("deviceId", id) } }

    /** False until the user has entered their name. */
    val hasName: Boolean get() = prefs.contains("deviceName")

    var deviceName: String
        get() = prefs.getString("deviceName", null) ?: Build.MODEL
        set(value) = prefs.edit { putString("deviceName", value.trim().ifEmpty { Build.MODEL }) }

    /** Latest name heard from each other device, so older ticks show a renamed person's new name. */
    val knownNames: Map<String, String>
        get() = runCatching { Json.decodeFromString<Map<String, String>>(prefs.getString("knownNames", null)!!) }
            .getOrDefault(emptyMap())

    fun rememberName(deviceId: String, name: String) {
        if (knownNames[deviceId] != name) prefs.edit { putString("knownNames", Json.encodeToString(knownNames + (deviceId to name))) }
    }

    /** The app's colour theme. Older versions had a dark mode switch; turning it off meant the light default. */
    var themeId: String
        get() = prefs.getString("theme", null) ?: if (prefs.getBoolean("darkMode", true)) "twodo-dark" else "twodo-light"
        set(value) = prefs.edit { putString("theme", value) }

    /** The old single "Notify me about changes" switch; now only the starting value for the change options below. */
    var notifyChanges: Boolean
        get() = prefs.getBoolean("notifyChanges", true)
        set(value) = prefs.edit { putBoolean("notifyChanges", value) }

    /** On unless the user turned it off: keeps this phone reachable so others see changes straight away. */
    /** Debug builds can turn direct connections off to exercise the relay route. */
    var directConnections: Boolean
        get() = prefs.getBoolean("directConnections", true)
        set(value) = prefs.edit { putBoolean("directConnections", value) }

    /** Text size: 1 is normal; larger for easier reading. Personal, not part of any group's look. */
    var textScale: Float
        get() = prefs.getFloat("textScale", 1f)
        set(value) = prefs.edit { putFloat("textScale", value) }

    /** The group (or [OTHER_LISTS]) shown last, to open on it next time. */
    var lastView: String?
        get() = prefs.getString("lastView", null)
        set(value) = prefs.edit { putString("lastView", value) }

    // ---- Notifications (app-wide, per phone) ----
    // Change notifications start the way the old "Notify me about changes" switch was set.

    private fun flag(key: String, default: Boolean) = prefs.getBoolean(key, default)
    private fun setFlag(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }

    /** A morning notification listing today's calendar entries. */
    var notifyDaily: Boolean
        get() = flag("notifyDaily", true)
        set(value) = setFlag("notifyDaily", value)

    /** When the daily schedule comes, "HH:mm". */
    var dailyTime: String
        get() = prefs.getString("dailyTime", null) ?: "07:30"
        set(value) = prefs.edit { putString("dailyTime", value) }

    /** Skip the daily schedule on days with nothing on. */
    var dailyOnlyIfSomething: Boolean
        get() = flag("dailyOnlyIfSomething", true)
        set(value) = setFlag("dailyOnlyIfSomething", value)

    /** Minutes before a timed entry to remind about it; 0 is off. */
    var reminderMinutes: Int
        get() = prefs.getInt("reminderMinutes", 0)
        set(value) = prefs.edit { putInt("reminderMinutes", value) }

    /** Latest reminder time already notified (epoch ms), so nothing is reminded twice. */
    var remindedUpTo: Long
        get() = prefs.getLong("remindedUpTo", 0)
        set(value) = prefs.edit { putLong("remindedUpTo", value) }

    var notifyCalendarAdded: Boolean
        get() = flag("notifyCalendarAdded", notifyChanges)
        set(value) = setFlag("notifyCalendarAdded", value)

    var notifyCalendarChanged: Boolean
        get() = flag("notifyCalendarChanged", notifyChanges)
        set(value) = setFlag("notifyCalendarChanged", value)

    var notifyListAdded: Boolean
        get() = flag("notifyListAdded", notifyChanges)
        set(value) = setFlag("notifyListAdded", value)

    /** Ticking off and removing list items: chatty while someone's shopping, so off to start. */
    var notifyListTicked: Boolean
        get() = flag("notifyListTicked", false)
        set(value) = setFlag("notifyListTicked", value)

    var notifyJoined: Boolean
        get() = flag("notifyJoined", true)
        set(value) = setFlag("notifyJoined", value)

    var notifyLeft: Boolean
        get() = flag("notifyLeft", true)
        set(value) = setFlag("notifyLeft", value)

    /** Groups: lists added or removed, renames, a new look. */
    var notifyGroupChanges: Boolean
        get() = flag("notifyGroupChanges", notifyChanges)
        set(value) = setFlag("notifyGroupChanges", value)

    var notifyConflicts: Boolean
        get() = flag("notifyConflicts", true)
        set(value) = setFlag("notifyConflicts", value)

    /** Opt-in: after a crash, offer to email a report to the developer. Off unless the user turns it on. */
    var offerCrashReports: Boolean
        get() = prefs.getBoolean("offerCrashReports", false)
        set(value) = prefs.edit { putBoolean("offerCrashReports", value) }

    /** Newest crash the user has already been asked about, so each is offered once. */
    var crashesSeenUpTo: Long
        get() = prefs.getLong("crashesSeenUpTo", 0)
        set(value) = prefs.edit { putLong("crashesSeenUpTo", value) }

    var backgroundSync: Boolean
        get() = prefs.getBoolean("backgroundSync", true)
        set(value) = prefs.edit { putBoolean("backgroundSync", value) }
}

/** [Identity.lastView] for the lists and diaries that aren't in a group. */
const val OTHER_LISTS = "other"

/** [Identity.lastView] for everyone's calendars in one schedule. */
const val ALL_CALENDARS = "all-calendars"
