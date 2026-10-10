package app.twodo.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A calendar entry with the calendar it's on, for daily schedules and reminders. */
data class CalendarEntry(val calendar: TodoList, val item: Item) {
    val start: LocalDateTime?
        get() {
            val date = item.localDate ?: return null
            val time = item.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
            return date.atTime(time)
        }
}

/** Every live entry on every calendar (diaries) in [lists]. */
fun calendarEntries(lists: Collection<TodoList>): List<CalendarEntry> =
    lists.filter { it.kind == SpaceKind.DIARY }.flatMap { calendar ->
        calendar.items.values.filter { !it.deleted && it.date != null }.map { CalendarEntry(calendar, it) }
    }

/** What's on [day]: all-day entries first, then by time. */
fun List<CalendarEntry>.on(day: LocalDate): List<CalendarEntry> =
    filter { it.item.localDate == day }.sortedWith(compareBy({ it.item.time ?: "" }, { it.item.createdAt }, { it.item.id }))

/** When to remind about [entry]: [minutesBefore] its start, or null if it has no time. */
fun reminderTime(entry: CalendarEntry, minutesBefore: Int): LocalDateTime? = entry.start?.minusMinutes(minutesBefore.toLong())

/** Entries whose reminder falls after [after] and no later than [upTo] — due now, not yet reminded. */
fun List<CalendarEntry>.remindersDue(minutesBefore: Int, after: LocalDateTime, upTo: LocalDateTime): List<CalendarEntry> =
    filter { e -> reminderTime(e, minutesBefore)?.let { it > after && it <= upTo } == true }.sortedBy { it.start }

/** The next reminder after [now], to set an alarm for; null if there's nothing to remind about. */
fun List<CalendarEntry>.nextReminder(minutesBefore: Int, now: LocalDateTime): LocalDateTime? =
    mapNotNull { reminderTime(it, minutesBefore) }.filter { it > now }.minOrNull()

/** The next time the daily schedule is due: [at] today if that's still ahead, otherwise tomorrow. */
fun nextDaily(at: LocalTime, now: LocalDateTime): LocalDateTime {
    val today = now.toLocalDate().atTime(at)
    return if (today > now) today else today.plusDays(1)
}
