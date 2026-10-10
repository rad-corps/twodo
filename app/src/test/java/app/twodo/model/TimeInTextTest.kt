package app.twodo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class TimeInTextTest {
    private fun time(text: String, twelveHour: Boolean = true) = findTime(text, twelveHour)?.time
    private fun words(text: String) = findTime(text, true)?.let { withoutTime(text, it) } ?: text
    private fun at(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun readsTheUsualWaysOfWritingATime() {
        assertEquals(at(11, 30), time("Dentist 11:30am"))
        assertEquals(at(11, 30), time("Dentist 11:30 am"))
        assertEquals(at(11, 30), time("Dentist 11.30am"))
        assertEquals(at(11, 30), time("Dentist 11:30 AM"))
        assertEquals(at(11), time("Dentist 11am"))
        assertEquals(at(19), time("Dinner 7 p.m."))
        assertEquals(at(19), time("Dinner 7pm."))
        assertEquals(at(19, 15), time("Dinner @7:15pm"))
        assertEquals(at(0), time("Flight 12am"))
        assertEquals(at(12, 30), time("Lunch 12:30pm"))
        assertEquals(at(14, 30), time("Meeting 14:30"))
        assertEquals(at(9, 5), time("Bus 09:05"))
        assertEquals(at(12), time("Lunch at noon"))
        assertEquals(at(0), time("New year at midnight"))
    }

    @Test
    fun aColonTimeWithoutAmPmIsGuessedSensibly() {
        assertEquals(at(15, 30), time("Swimming 3:30"))
        assertEquals(at(3, 30), time("Swimming 3:30", twelveHour = false))
        assertEquals(at(3, 30), time("Swimming 03:30"))
        assertEquals(at(8, 15), time("School 8:15"))
        assertEquals(at(3, 30), time("Feed baby 3:30am"))
    }

    @Test
    fun aRangeGivesItsStart() {
        assertEquals(at(15), time("Party 3-4pm"))
        assertEquals(at(11), time("Brunch 11-1pm"))
        assertEquals(at(9, 30), time("Work 9:30am to 5pm"))
        assertEquals(at(15, 30), time("Swim 3:30–4:30"))
    }

    @Test
    fun leavesAloneThingsThatAreNotTimes() {
        listOf(
            "Pick up kids at 3", "Buy 2-3 apples", "Pay \$11.30", "Pay 11.30", "Due 10/12", "Party 2024",
            "Run 5km", "Room 12", "Mix 3:1", "Version 1.2", "Ring 0412 345 678", "Afternoon tea",
            "Midnight mass", "Noon run", "Chat 25:00", "Score 3:75", "13pm club", "I am here", "Bring 2 apples",
        ).forEach { assertNull(it, findTime(it, true)) }
    }

    @Test
    fun takesTheTimeOutOfTheWords() {
        assertEquals("Dentist", words("Dentist 11:30am"))
        assertEquals("Dentist", words("Dentist at 11:30am"))
        assertEquals("Pick up Sam from school", words("Pick up Sam at 3:15pm from school"))
        assertEquals("Dinner, bring wine", words("Dinner 7pm, bring wine"))
        assertEquals("Lunch", words("Lunch at noon"))
        assertEquals("Dinner", words("Dinner @ 7pm"))
        // Ranges stay as typed (the end time has nowhere else to go), and so does a time on its own.
        assertEquals("Party 3-4pm", words("Party 3-4pm"))
        assertEquals("3pm", words("3pm"))
    }
}
