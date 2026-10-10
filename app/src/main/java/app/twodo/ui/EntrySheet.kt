@file:OptIn(ExperimentalMaterial3Api::class)

package app.twodo.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.twodo.model.AuditEntry
import app.twodo.model.findTime
import app.twodo.model.withoutTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the entry sheet saves: the text, its day, and its time ("HH:mm", or null for all day). */
data class EntryDraft(val text: String, val date: LocalDate, val time: String?)

private val fullDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private const val MINUTE_STEP = 15

/**
 * The one place calendar entries are added and edited: what's happening, which day (Today, Tomorrow
 * or any day), and all day or at a time — set with up/down buttons, or typed by tapping the time.
 * A time typed in the words ("Dentist 11:30am") sets the time, until the time is set by hand; it's
 * taken out of the words on saving. With several [calendars] it also asks which one. Editing adds Delete and the entry's history.
 */
@Composable
internal fun EntrySheet(
    initial: EntryDraft,
    editing: Boolean,
    onDismiss: () -> Unit,
    onSave: (EntryDraft, calendar: Int) -> Unit,
    calendars: List<Pair<String, Color>> = emptyList(),
    onDelete: (() -> Unit)? = null,
    history: List<AuditEntry> = emptyList(),
    historyRow: @Composable (AuditEntry) -> Unit = {},
) {
    val context = LocalContext.current
    val is24Hour = remember { DateFormat.is24HourFormat(context) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(initial.text) }
    var date by remember { mutableStateOf(initial.date) }
    var time by remember { mutableStateOf(initial.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }) }
    var calendar by remember { mutableStateOf(0) }
    var pickingDay by remember { mutableStateOf(false) }
    var typingTime by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    // The time picked up from the words (if any), and what the time was before, to go back to if it's deleted.
    var typedTime by remember { mutableStateOf<LocalTime?>(null) }
    var timeBefore by remember { mutableStateOf<LocalTime?>(null) }
    var timeSetByHand by remember { mutableStateOf(false) }
    fun setTime(value: LocalTime?) {
        time = value
        timeSetByHand = true
        typedTime = null
    }
    val focus = remember { FocusRequester() }
    // A new entry starts with the keyboard up, ready to type.
    LaunchedEffect(Unit) { if (!editing) runCatching { focus.requestFocus() } }

    fun save() {
        if (text.isBlank()) return
        val found = findTime(text, !is24Hour)
        val words = if (found != null && found.time == time) withoutTime(text, found) else text.trim()
        onSave(EntryDraft(words, date, time?.let { "%02d:%02d".format(it.hour, it.minute) }), calendar)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(if (editing) "Edit entry" else "Add to the calendar", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (!timeSetByHand) {
                        val found = findTime(it, !is24Hour)?.time
                        if (found != null && typedTime == null) timeBefore = time
                        if (found != null) time = found else if (typedTime != null) time = timeBefore
                        typedTime = found
                    }
                },
                label = { Text("What's happening?") },
                textStyle = MaterialTheme.typography.titleMedium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            SheetLabel("Day")
            val today = LocalDate.now()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigChip("Today", date == today) { date = today }
                BigChip("Tomorrow", date == today.plusDays(1)) { date = today.plusDays(1) }
                BigChip("Pick a day…", date != today && date != today.plusDays(1)) { pickingDay = true }
            }
            Text(
                date.format(fullDay) + if (date.year != today.year) " ${date.year}" else "",
                Modifier.padding(top = 6.dp, start = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SheetLabel("Time")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = time == null, onClick = { setTime(null) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("All day") }
                SegmentedButton(
                    selected = time != null,
                    onClick = { if (time == null) setTime(nextHour()) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("At a time") }
            }
            time?.let { current ->
                Spacer(Modifier.height(12.dp))
                TimeStepper(current, is24Hour, onChange = ::setTime, onType = { typingTime = true })
                if (typedTime != null) {
                    Text(
                        "From what you typed",
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (calendars.size > 1) {
                SheetLabel("Calendar")
                calendars.forEachIndexed { index, (label, color) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { calendar = index }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            label,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (index == calendar) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (index == calendar) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = ::save,
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (editing) "Save" else "Add", style = MaterialTheme.typography.titleMedium) }
            if (onDelete != null) {
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
            if (history.isNotEmpty()) {
                TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showHistory) "Hide history" else "History (${history.size})")
                }
                if (showHistory) history.forEach { historyRow(it) }
            }
        }
    }

    if (pickingDay) DayPickerDialog(date, onDismiss = { pickingDay = false }) { date = it; pickingDay = false }
    if (typingTime) {
        TimeDialog(time?.let { "%02d:%02d".format(it.hour, it.minute) }, onDismiss = { typingTime = false }, onClear = null) {
            setTime(LocalTime.parse(it))
            typingTime = false
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        Modifier.padding(top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun BigChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp)) },
    )
}

/**
 * Hour, minutes and (on 12-hour phones) AM/PM, each with up and down buttons. Minutes move in
 * [MINUTE_STEP]s; tap the time to type an exact one.
 */
@Composable
private fun TimeStepper(time: LocalTime, is24Hour: Boolean, onChange: (LocalTime) -> Unit, onType: () -> Unit) {
    val hourText = if (is24Hour) "%02d".format(time.hour) else ((time.hour + 11) % 12 + 1).toString()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        StepColumn(hourText, "hour", onUp = { onChange(time.plusHours(1)) }, onDown = { onChange(time.minusHours(1)) }, onType = onType)
        Text(":", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 4.dp))
        StepColumn(
            "%02d".format(time.minute), "minutes",
            // Snap to the step first, so 3:07 goes to 3:15 / 3:00 rather than 3:22 / 2:52.
            onUp = { onChange(time.withMinute(time.minute / MINUTE_STEP * MINUTE_STEP).plusMinutes(MINUTE_STEP.toLong())) },
            onDown = {
                val snapped = time.withMinute(time.minute / MINUTE_STEP * MINUTE_STEP)
                onChange(if (snapped == time) time.minusMinutes(MINUTE_STEP.toLong()) else snapped)
            },
            onType = onType,
        )
        if (!is24Hour) {
            Spacer(Modifier.width(12.dp))
            val am = time.hour < 12
            StepColumn(if (am) "AM" else "PM", "morning or afternoon", onUp = { onChange(time.plusHours(12)) }, onDown = { onChange(time.plusHours(12)) }, onType = onType)
        }
    }
}

@Composable
private fun StepColumn(value: String, what: String, onUp: () -> Unit, onDown: () -> Unit, onType: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onUp, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Later $what") }
        Text(
            value,
            Modifier.widthIn(min = 72.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onType).padding(vertical = 6.dp),
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
        FilledTonalIconButton(onClick = onDown, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Earlier $what") }
    }
}

/** The next whole hour from now: a sensible starting point for "At a time". */
private fun nextHour(): LocalTime = LocalTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1)
