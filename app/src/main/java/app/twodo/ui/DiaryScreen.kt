@file:OptIn(ExperimentalMaterial3Api::class)

package app.twodo.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twodo.TwoDoApp
import app.twodo.model.Item
import app.twodo.model.TodoList
import app.twodo.model.formatDay
import app.twodo.sync.SyncStatus
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

// Each pager page is one day. Pages cover 1900-2199, so swiping never hits an edge in practice.
private val FIRST_DAY: LocalDate = LocalDate.of(1900, 1, 1)
private val PAGE_COUNT = ChronoUnit.DAYS.between(FIRST_DAY, LocalDate.of(2200, 1, 1)).toInt()
private fun pageOf(date: LocalDate) = ChronoUnit.DAYS.between(FIRST_DAY, date).toInt().coerceIn(0, PAGE_COUNT - 1)
private fun dateOf(page: Int): LocalDate = FIRST_DAY.plusDays(page.toLong())

private val longDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val longDayWithYear = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

/** Untimed entries first (in the order they were added), then by time. */
private val entryOrder = compareBy<Item>({ it.time ?: "" }, { it.createdAt }, { it.id })

@Composable
internal fun DiaryScreen(app: TwoDoApp, list: TodoList, status: SyncStatus, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var showHistory by rememberSaveable(list.id) { mutableStateOf(false) }
    val names by app.sync.names.collectAsStateWithLifecycle()
    val highlighted = rememberRemoteHighlights(app, list.id)
    if (showHistory) return HistoryScreen(list, app.identity.deviceId, names, onBack = { showHistory = false })

    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = pageOf(LocalDate.now())) { PAGE_COUNT }
    // Grouped once per change to the diary, so showing any day is a map lookup.
    val byDay = remember(list.items) {
        list.items.values.filter { !it.deleted && it.date != null }
            .groupBy { it.date!! }
            .mapValues { (_, entries) -> entries.sortedWith(entryOrder) }
    }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Item?>(null) }
    val day = dateOf(pager.currentPage)

    fun goTo(date: LocalDate) {
        scope.launch {
            val target = pageOf(date)
            // Animate short hops; jump straight to far-away days instead of scrolling through them.
            if (abs(target - pager.currentPage) <= 2) pager.animateScrollToPage(target) else pager.scrollToPage(target)
        }
    }

    Scaffold(
        topBar = { SpaceTopBar(app, list, status, onBack, onHistory = { showHistory = true }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DayHeader(
                day = day,
                onPrevious = { goTo(day.minusDays(1)) },
                onNext = { goTo(day.plusDays(1)) },
                onPick = { picking = true },
                onToday = { goTo(LocalDate.now()) },
            )
            AddEntryBar(day) { text, time -> scope.launch { app.repo.addEntry(list.id, day, text, time) } }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1,
                key = { it },
            ) { page ->
                val date = dateOf(page)
                DayPage(date, byDay[date.toString()].orEmpty(), highlighted, app.identity.deviceId, names) { editing = it }
            }
        }
    }

    if (picking) DayPickerDialog(day, onDismiss = { picking = false }) { picking = false; goTo(it) }
    editing?.let { entry ->
        // Show the latest version if it changed while the dialog was open.
        val current = list.items[entry.id]?.takeIf { !it.deleted } ?: return@let
        EntryDialog(
            entry = current,
            list = list,
            myDeviceId = app.identity.deviceId,
            names = names,
            onDismiss = { editing = null },
            onSave = { text, date, time ->
                editing = null
                scope.launch { app.repo.editEntry(list.id, current.id, text, date, time) }
                if (date != day) goTo(date)
            },
            onDelete = {
                editing = null
                scope.launch { app.repo.deleteItem(list.id, current.id) }
            },
        )
    }
}

/** "‹  Thursday 8 October  ›" with "Today" / "In 3 days" underneath; tap the date to pick another. */
@Composable
private fun DayHeader(day: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit, onPick: () -> Unit, onToday: () -> Unit) {
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onPick).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.format(if (day.year == today.year) longDay else longDayWithYear),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.DateRange, "Pick a date", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            }
            Text(relativeDay(day, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (day != today) TextButton(onClick = onToday) { Text("Today") }
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day") }
    }
}

private fun relativeDay(day: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, day)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days == -1L -> "Yesterday"
        days > 0 -> "In $days days"
        else -> "${-days} days ago"
    }
}

@Composable
private fun AddEntryBar(day: LocalDate, onAdd: (String, String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingTime by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun add() {
        if (text.isBlank()) return
        onAdd(text, time)
        text = ""
        time = null
    }

    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Add to ${dayLabel(day).let { if (it == formatDay(day)) it else it.lowercase() }}") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            trailingIcon = {
                TextButton(onClick = { pickingTime = true }) {
                    Text(time?.let { displayTime(context, it) } ?: "Time")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = ::add, enabled = text.isNotBlank()) { Icon(Icons.Default.Add, "Add") }
    }
    if (pickingTime) {
        TimeDialog(
            initial = time,
            onDismiss = { pickingTime = false },
            onClear = if (time != null) ({ time = null; pickingTime = false }) else null,
        ) { time = it; pickingTime = false }
    }
}

@Composable
private fun DayPage(
    date: LocalDate,
    entries: List<Item>,
    highlighted: Map<String, Long>,
    myDeviceId: String,
    names: Map<String, String>,
    onOpen: (Item) -> Unit,
) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                "Nothing on ${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)} yet",
                Modifier.padding(top = 48.dp),
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(entries, key = { it.id }) { entry ->
            val background by animateColorAsState(
                if (entry.id in highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                animationSpec = tween(600),
                label = "highlight",
            )
            Row(
                Modifier.fillMaxWidth().background(background).clickable { onOpen(entry) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    entry.time?.let { displayTime(context, it) } ?: "",
                    Modifier.width(64.dp).padding(top = 2.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(entry.text, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (entry.version.by == myDeviceId) "You" else names[entry.version.by] ?: entry.editor,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** Edit an entry's text, day and time, delete it, or see its change history. */
@Composable
private fun EntryDialog(
    entry: Item,
    list: TodoList,
    myDeviceId: String,
    names: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (String, LocalDate, String?) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember(entry.id) { mutableStateOf(entry.text) }
    var date by remember(entry.id) { mutableStateOf(entry.localDate ?: LocalDate.now()) }
    var time by remember(entry.id) { mutableStateOf(entry.time) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val history = remember(list.audit, entry.id) {
        list.audit.values.filter { it.itemId == entry.id }.sortedByDescending { it.ts }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit entry") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { pickingDate = true }, label = { Text(formatDay(date)) })
                    AssistChip(
                        onClick = { pickingTime = true },
                        label = { Text(time?.let { displayTime(context, it) } ?: "Add time") },
                        trailingIcon = time?.let {
                            {
                                Icon(
                                    Icons.Default.Close, "Remove time",
                                    Modifier.size(16.dp).clickable { time = null },
                                )
                            }
                        },
                    )
                }
                if (history.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text("History", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(history, key = { it.id }) { AuditRow(it, myDeviceId, names, showDate = true, compact = true) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, date, time) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (pickingDate) DayPickerDialog(date, onDismiss = { pickingDate = false }) { date = it; pickingDate = false }
    if (pickingTime) {
        TimeDialog(time, onDismiss = { pickingTime = false }, onClear = null) { time = it; pickingTime = false }
    }
}

/** Calendar picker; its pencil toggle switches to typing the date in. */
@Composable
private fun DayPickerDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, showModeToggle = true)
    }
}

/** Type a time in; [onClear] (if given) offers "No time". */
@Composable
private fun TimeDialog(initial: String?, onDismiss: () -> Unit, onClear: (() -> Unit)?, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val start = initial?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.now().withMinute(0)
    val state = rememberTimePickerState(start.hour, start.minute, is24Hour = DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time") },
        text = { TimeInput(state) },
        confirmButton = { TextButton(onClick = { onPick("%02d:%02d".format(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = {
            Row {
                if (onClear != null) TextButton(onClick = onClear) { Text("No time") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private val twelveHour = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** "15:00" stored; shown as "15:00" or "3:00 PM" depending on the phone's setting. */
private fun displayTime(context: android.content.Context, time: String): String =
    if (DateFormat.is24HourFormat(context)) time
    else runCatching { LocalTime.parse(time).format(twelveHour) }.getOrDefault(time)
