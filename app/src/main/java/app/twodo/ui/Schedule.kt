package app.twodo.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twodo.TwoDoApp
import app.twodo.model.Item
import app.twodo.model.TodoList
import app.twodo.model.formatDay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A calendar shown in a schedule: entries from [list], labelled [label] in [color] when several are mixed. */
class ScheduleSource(val list: TodoList, val label: String, val color: Color)

private class ScheduleEntry(val item: Item, val source: ScheduleSource)

private val scheduleDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val scheduleDayWithYear = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

/**
 * Everything coming up, day by day, on one scrolling page — days without entries are skipped. With
 * several [sources] (e.g. all of someone's groups), each entry shows which calendar it's from.
 * Past days are one tap away at the top.
 */
@Composable
internal fun ScheduleView(app: TwoDoApp, sources: List<ScheduleSource>, modifier: Modifier = Modifier, onDay: ((LocalDate) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val is24Hour = remember { DateFormat.is24HourFormat(context) }
    val names by app.sync.names.collectAsStateWithLifecycle()
    var showPast by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val mixed = sources.size > 1

    val days = remember(sources.map { it.list.items }, showPast, today) {
        sources.flatMap { source ->
            source.list.items.values.filter { !it.deleted && it.date != null }.map { ScheduleEntry(it, source) }
        }
            .filter { e -> e.item.localDate?.let { showPast || !it.isBefore(today) } == true }
            .groupBy { it.item.localDate!! }
            .toSortedMap()
            .mapValues { (_, entries) -> entries.sortedWith(compareBy({ it.item.time ?: "" }, { it.item.createdAt }, { it.item.id })) }
    }
    val hasPast = remember(sources.map { it.list.items }, today) {
        sources.any { s -> s.list.items.values.any { !it.deleted && it.localDate?.isBefore(today) == true } }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hasPast) {
                    TextButton(onClick = { showPast = !showPast }) { Text(if (showPast) "Hide past days" else "Show past days") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
            }
        }
        if (days.isEmpty()) {
            item {
                Text(
                    "Nothing coming up.\nTap Add to put something on the calendar.",
                    Modifier.fillMaxWidth().padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        days.forEach { (date, entries) ->
            item(key = "day-$date") { DayHeading(date, today, onDay) }
            items(entries, key = { "${it.source.list.id}/${it.item.id}" }) { entry ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = entry.source.list.id to entry.item.id }
                        .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        entry.item.time?.let { displayTime(is24Hour, it) } ?: "All day",
                        Modifier.width(72.dp).padding(top = 2.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (entry.item.time != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                    if (mixed) {
                        Box(Modifier.padding(top = 4.dp, end = 10.dp).width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(entry.source.color))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(entry.item.text, style = MaterialTheme.typography.bodyLarge)
                        val who = if (entry.item.version.by == app.identity.deviceId) "You" else names[entry.item.version.by] ?: entry.item.editor
                        Text(
                            if (mixed) "${entry.source.label} · $who" else who,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }

    editing?.let { (listId, itemId) ->
        val source = sources.firstOrNull { it.list.id == listId } ?: return@let
        val entry = source.list.items[itemId]?.takeIf { !it.deleted } ?: return@let
        EntryDialog(
            entry = entry,
            list = source.list,
            myDeviceId = app.identity.deviceId,
            names = names,
            onDismiss = { editing = null },
            onSave = { text, date, time ->
                editing = null
                scope.launch { app.repo.editEntry(listId, itemId, text, date, time) }
            },
            onDelete = {
                editing = null
                scope.launch { app.repo.deleteItem(listId, itemId) }
            },
        )
    }
    if (adding) {
        NewEntryDialog(sources, onDismiss = { adding = false }) { source, text, date, time ->
            adding = false
            scope.launch { app.repo.addEntry(source.list.id, date, text, time) }
        }
    }
}

/** "Today · Friday 9 October", "Tomorrow · Saturday 10 October", "Wednesday 14 October". */
@Composable
private fun DayHeading(date: LocalDate, today: LocalDate, onClick: ((LocalDate) -> Unit)?) {
    val formatted = date.format(if (date.year == today.year) scheduleDay else scheduleDayWithYear)
    val relative = when (date) {
        today -> "Today · "
        today.plusDays(1) -> "Tomorrow · "
        today.minusDays(1) -> "Yesterday · "
        else -> ""
    }
    Text(
        relative + formatted,
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick(date) } else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

/** Add an entry on any day; with several calendars, also choose which one. */
@Composable
private fun NewEntryDialog(
    sources: List<ScheduleSource>,
    onDismiss: () -> Unit,
    onAdd: (ScheduleSource, String, LocalDate, String?) -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var time by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(sources.first()) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to the calendar") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("What's on?") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { pickingDate = true }, label = { Text(formatDay(date)) })
                    AssistChip(
                        onClick = { pickingTime = true },
                        label = { Text(time?.let { displayTime(DateFormat.is24HourFormat(context), it) } ?: "Add time") },
                        trailingIcon = time?.let { { Icon(Icons.Default.Close, "Remove time", Modifier.size(16.dp).clickable { time = null }) } },
                    )
                }
                if (sources.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text("Calendar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    sources.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { source = option }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(option.color))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                option.label,
                                Modifier.weight(1f),
                                fontWeight = if (option == source) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (option == source) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(source, text, date, time) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickingDate) DayPickerDialog(date, onDismiss = { pickingDate = false }) { date = it; pickingDate = false }
    if (pickingTime) TimeDialog(time, onDismiss = { pickingTime = false }, onClear = null) { time = it; pickingTime = false }
}
