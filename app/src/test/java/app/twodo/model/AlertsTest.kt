package app.twodo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AlertsTest {
    private val day = LocalDate.of(2026, 10, 10)
    private fun entry(id: String, date: LocalDate?, time: String?, deleted: Boolean = false) =
        Item(id, id, createdAt = 0, version = Version(1, "A"), editor = "A", date = date?.toString(), time = time, deleted = deleted)

    private val calendar = TodoList("cal", "Calendar", ListKeys.newSecret(), kind = SpaceKind.DIARY).copy(
        items = listOf(
            entry("swim", day, "15:30"),
            entry("bins", day, null),
            entry("dentist", day.plusDays(1), "09:00"),
            entry("gone", day, "16:00", deleted = true),
        ).associateBy { it.id },
    )
    private val shopping = TodoList("shop", "Shopping", ListKeys.newSecret()).copy(items = mapOf("milk" to entry("milk", null, null)))
    private val entries = calendarEntries(listOf(calendar, shopping))

    @Test
    fun onlyLiveCalendarEntriesCount() {
        assertEquals(setOf("swim", "bins", "dentist"), entries.map { it.item.id }.toSet())
    }

    @Test
    fun dayListsAllDayFirstThenByTime() {
        assertEquals(listOf("bins", "swim"), entries.on(day).map { it.item.id })
    }

    @Test
    fun remindersAreDueOnceInTheirWindow() {
        val at = { h: Int, m: Int -> LocalDateTime.of(day, LocalTime.of(h, m)) }
        // Swimming at 15:30 with a 30-minute reminder: due at 15:00.
        assertEquals(listOf("swim"), entries.remindersDue(30, after = at(14, 59), upTo = at(15, 0)).map { it.item.id })
        assertEquals(emptyList<String>(), entries.remindersDue(30, after = at(15, 0), upTo = at(15, 5)).map { it.item.id })
        assertEquals(at(15, 0), entries.nextReminder(30, at(14, 0)))
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(8, 30)), entries.nextReminder(30, at(15, 0)))
        assertNull(entries.nextReminder(30, LocalDateTime.of(day.plusDays(2), LocalTime.MIDNIGHT)))
    }

    @Test
    fun dailyScheduleIsTodayIfStillAheadOtherwiseTomorrow() {
        val at = LocalTime.of(7, 30)
        assertEquals(LocalDateTime.of(day, at), nextDaily(at, LocalDateTime.of(day, LocalTime.of(6, 0))))
        assertEquals(LocalDateTime.of(day.plusDays(1), at), nextDaily(at, LocalDateTime.of(day, LocalTime.of(7, 30))))
    }
}
