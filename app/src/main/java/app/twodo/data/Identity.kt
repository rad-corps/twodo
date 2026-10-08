package app.twodo.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.util.UUID

/** This device's identity and user settings. */
class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString("deviceId", null)
        ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString("deviceId", id) } }

    var deviceName: String
        get() = prefs.getString("deviceName", null) ?: Build.MODEL
        set(value) = prefs.edit { putString("deviceName", value.trim().ifEmpty { Build.MODEL }) }

    var backgroundSync: Boolean
        get() = prefs.getBoolean("backgroundSync", false)
        set(value) = prefs.edit { putBoolean("backgroundSync", value) }
}
