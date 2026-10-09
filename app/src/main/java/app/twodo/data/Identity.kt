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

    var notifyChanges: Boolean
        get() = prefs.getBoolean("notifyChanges", true)
        set(value) = prefs.edit { putBoolean("notifyChanges", value) }

    /** On unless the user turned it off: keeps this phone reachable so others see changes straight away. */
    /** Debug builds can turn direct connections off to exercise the relay route. */
    var directConnections: Boolean
        get() = prefs.getBoolean("directConnections", true)
        set(value) = prefs.edit { putBoolean("directConnections", value) }

    var backgroundSync: Boolean
        get() = prefs.getBoolean("backgroundSync", true)
        set(value) = prefs.edit { putBoolean("backgroundSync", value) }
}
