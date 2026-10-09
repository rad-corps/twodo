package app.twodo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DescribeChangeTest {
    private val milk = Item("milk", "Milk", createdAt = 1, version = Version(100, "A"), editor = "Adam")
    private fun Item.edit(change: Item.() -> Item) = edited("B", "Sam", 200, change)

    @Test
    fun describesWhatHappened() {
        assertEquals("added Milk", describeChange(null, milk))
        assertEquals("ticked Milk", describeChange(milk, milk.edit { copy(checked = true) }))
        assertEquals("unticked Milk", describeChange(milk.copy(checked = true), milk.edit { copy(checked = false) }))
        assertEquals("removed Milk", describeChange(milk, milk.edit { copy(deleted = true) }))
        assertEquals("renamed Milk to Oat milk", describeChange(milk, milk.edit { copy(text = "Oat milk") }))
    }

    @Test
    fun movesAndAlreadyDeletedItemsAreQuiet() {
        assertNull(describeChange(milk, milk.moved(5.0, "B", 200)))
        assertNull(describeChange(null, milk.copy(deleted = true)))
    }

    @Test
    fun describesDiaryEntries() {
        val dentist = milk.copy(id = "d", text = "Dentist", date = "2026-10-09", time = "15:00")
        val thisYear = java.time.LocalDate.now().year == 2026
        val day = if (thisYear) "Fri 9 Oct" else "Fri 9 Oct 2026"
        assertEquals("added Dentist on $day at 15:00", describeChange(null, dentist))
        assertEquals("set Dentist to 16:30", describeChange(dentist, dentist.edit { copy(time = "16:30") }))
        assertEquals("removed the time from Dentist", describeChange(dentist, dentist.edit { copy(time = null) }))
        assertEquals("removed Dentist on $day at 15:00", describeChange(dentist, dentist.edit { copy(deleted = true) }))
    }

    @Test
    fun auditMergeIsAUnionAndIdsComeFromVersions() {
        val list = TodoList("l", "Groceries", ListKeys.newSecret())
        val ticked = milk.edit { copy(checked = true) }
        val entry = auditEntryFor(milk, ticked)!!
        assertEquals(entry, auditEntryFor(milk, ticked)) // same edit, same entry
        assertEquals("Sam", entry.byName)
        val (once, added) = list.mergeAudit(listOf(entry))
        assertEquals(listOf(entry), added)
        val (twice, addedAgain) = once.mergeAudit(listOf(entry))
        assertEquals(once, twice)
        assertEquals(emptyList<AuditEntry>(), addedAgain)
        assertNull(auditEntryFor(milk, milk.moved(5.0, "B", 300)))
    }

    @Test
    fun concurrentDiaryEditsToDifferentFieldsAreReportedAndDescribed() {
        val dentist = milk.copy(id = "d", text = "Dentist", date = "2026-10-09", time = "15:00")
        val movedOnA = dentist.edited("A", "Adam", 200) { copy(date = "2026-10-10") }
        val retimedOnB = dentist.edited("B", "Sam", 300) { copy(time = "16:30") }
        val list = TodoList("l", "Family", ListKeys.newSecret(), items = mapOf("d" to movedOnA), kind = SpaceKind.DIARY)
        val conflict = list.merge(listOf(retimedOnB)).conflicts.single()
        val year = if (java.time.LocalDate.now().year == 2026) "" else " 2026"
        assertEquals(
            "“Dentist”: your change (Sat 10 Oct$year at 15:00) was replaced by Sam's newer change (Fri 9 Oct$year at 16:30).",
            conflict.describe(),
        )
    }

    @Test
    fun listConflictDescriptionUnchanged() {
        val onA = milk.edited("A", "Adam", 200) { copy(checked = true) }
        val onB = milk.edited("B", "Sam", 300) { copy(deleted = true) }
        val list = TodoList("l", "Groceries", ListKeys.newSecret(), items = mapOf("milk" to onA))
        assertEquals(
            "“Milk”: your change (checked) was replaced by Sam's newer change (deleted).",
            list.merge(listOf(onB)).conflicts.single().describe(),
        )
    }
}
