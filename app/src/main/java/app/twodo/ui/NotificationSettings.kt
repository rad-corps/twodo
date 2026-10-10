@file:OptIn(ExperimentalMaterial3Api::class)

package app.twodo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.twodo.TwoDoApp
import app.twodo.data.Identity
import app.twodo.sync.CalendarAlerts
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.reflect.KMutableProperty1

private val REMINDER_CHOICES = listOf(0 to "Off", 10 to "10 min before", 30 to "30 min before", 60 to "1 hour before")

/**
 * Which notifications this phone gets, for all groups and lists. Changes by others only notify while
 * the app is closed; when it's open they're highlighted instead.
 */
@Composable
internal fun NotificationSettingsScreen(app: TwoDoApp, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val identity = app.identity
    // Bumped on every change, so the screen re-reads the settings it shows.
    var version by remember { mutableIntStateOf(0) }
    var pickingTime by remember { mutableStateOf(false) }
    val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun changed() {
        version++
        CalendarAlerts.reschedule(context)
    }

    @Composable
    fun toggle(title: String, detail: String?, setting: KMutableProperty1<Identity, Boolean>) {
        ToggleRow(title, detail, setting.get(identity)) { setting.set(identity, it); changed() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = flatBar(),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            if (!allowed) {
                run {
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Notifications are turned off for this app", style = MaterialTheme.typography.titleSmall)
                            Text("Nothing below will show until they're allowed in Android settings.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { context.startActivity(appNotificationSettings(context)) }, contentPadding = PaddingValues(0.dp)) {
                                Text("Open Android settings")
                            }
                        }
                    }
                }
            }
            // The settings live in SharedPreferences, not Compose state: keying on the version re-reads them after each change.
            key(version) {
                SectionTitle("Calendar")
                ToggleRow("Daily schedule", "What's on today, each morning", identity.notifyDaily) { identity.notifyDaily = it; changed() }
                if (identity.notifyDaily) {
                    val is24Hour = DateFormat.is24HourFormat(context)
                    val time = LocalTime.parse(identity.dailyTime)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { pickingTime = true }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("At", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (is24Hour) identity.dailyTime else time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(Modifier.padding(start = 16.dp)) {
                        Column(Modifier.weight(1f)) {
                            ToggleRow("Only when something's on", null, identity.dailyOnlyIfSomething) { identity.dailyOnlyIfSomething = it; changed() }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Reminders", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Before anything with a time, on every calendar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(REMINDER_CHOICES) { (minutes, label) ->
                        FilterChip(
                            selected = identity.reminderMinutes == minutes,
                            onClick = {
                                identity.reminderMinutes = minutes
                                // Start from now: no burst of reminders for things already passed.
                                identity.remindedUpTo = System.currentTimeMillis()
                                changed()
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                toggle("Someone adds something", null, Identity::notifyCalendarAdded)
                toggle("Someone changes or removes something", null, Identity::notifyCalendarChanged)

                Spacer(Modifier.height(16.dp))
                SectionTitle("Lists")
                toggle("Someone adds an item", null, Identity::notifyListAdded)
                toggle("Someone ticks off or removes an item", "Can be a lot while someone's shopping", Identity::notifyListTicked)

                Spacer(Modifier.height(16.dp))
                SectionTitle("People and groups")
                toggle("Someone joins", "A group, or a list you share", Identity::notifyJoined)
                toggle("Someone leaves", null, Identity::notifyLeft)
                toggle("Group changes", "Lists added or deleted, renames, a new look", Identity::notifyGroupChanges)

                Spacer(Modifier.height(16.dp))
                SectionTitle("Other")
                toggle("Conflicting changes", "When two people change the same thing at once", Identity::notifyConflicts)

                Spacer(Modifier.height(12.dp))
                Text(
                    "Changes by others are only notified while the app is closed — when it's open, they're highlighted instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { context.startActivity(appNotificationSettings(context)) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Sounds and vibration in Android settings")
                }
            }
        }
    }
    if (pickingTime) {
        TimeDialog(identity.dailyTime, onDismiss = { pickingTime = false }, onClear = null) {
            identity.dailyTime = it
            pickingTime = false
            changed()
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).toggleable(value = checked, onValueChange = onChange).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun appNotificationSettings(context: android.content.Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
