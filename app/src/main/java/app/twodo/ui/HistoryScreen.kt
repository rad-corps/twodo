@file:OptIn(ExperimentalMaterial3Api::class)

package app.twodo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.twodo.model.AuditEntry
import app.twodo.model.TodoList
import app.twodo.model.formatDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm")

/** Every recorded change to [list], newest first, grouped by day. */
@Composable
internal fun HistoryScreen(list: TodoList, myDeviceId: String, names: Map<String, String>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val days = remember(list.audit) { groupByDay(list.audit.values) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("History", style = MaterialTheme.typography.titleLarge)
                        Text(
                            list.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = flatBar(),
            )
        },
    ) { padding ->
        if (days.isEmpty()) {
            Text(
                "No changes recorded yet. Changes made from now on show up here.",
                Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            days.forEach { (day, entries) ->
                item(key = day.toString()) {
                    Text(
                        dayLabel(day),
                        Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(entries, key = { it.id }) { AuditRow(it, myDeviceId, names) }
            }
        }
    }
}

/** "Sam ticked Milk" with the time on the right. */
@Composable
internal fun AuditRow(
    entry: AuditEntry,
    myDeviceId: String,
    names: Map<String, String>,
    showDate: Boolean = false,
    /** Inside a dialog, which already has its own padding. */
    compact: Boolean = false,
) {
    val who = if (entry.by == myDeviceId) "You" else names[entry.by] ?: entry.byName
    val at = Instant.ofEpochMilli(entry.ts).atZone(ZoneId.systemDefault())
    Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 20.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        val color = personColor(entry.by)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(who) }
                append(" ${entry.description}")
            },
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            (if (showDate) dayLabel(at.toLocalDate()) + " " else "") + at.format(timeOfDay),
            Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

internal fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> formatDay(day, today)
}

private fun groupByDay(entries: Collection<AuditEntry>): List<Pair<LocalDate, List<AuditEntry>>> {
    val zone = ZoneId.systemDefault()
    return entries.sortedByDescending { it.ts }
        .groupBy { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }
        .toList()
}
