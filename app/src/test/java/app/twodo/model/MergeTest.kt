package app.twodo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    private val empty = TodoList("list", "Groceries", ListKeys.newSecret())
    private val milk = Item("milk", "Milk", createdAt = 1, version = Version(100, "A"), editor = "Phone A")

    private fun TodoList.with(vararg items: Item) = copy(items = items.associateBy { it.id })
    private fun Item.edit(device: String, now: Long, change: Item.() -> Item = { this }) =
        edited(device, "Phone $device", now, change)

    @Test
    fun newRemoteItemIsAccepted() {
        val result = empty.merge(listOf(milk))
        assertEquals(milk, result.list.items["milk"])
        assertEquals(listOf(milk), result.accepted)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun descendantFastForwardsWithoutConflict() {
        val checkedThenRenamed = milk.edit("B", 200) { copy(checked = true) }.edit("B", 300) { copy(text = "Oat milk") }
        val result = empty.with(milk).merge(listOf(checkedThenRenamed))
        assertEquals(checkedThenRenamed, result.list.items["milk"])
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun olderVersionIsIgnored() {
        val checked = milk.edit("A", 200) { copy(checked = true) }
        val result = empty.with(checked).merge(listOf(milk))
        assertEquals(checked, result.list.items["milk"])
        assertTrue(result.accepted.isEmpty())
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun concurrentEditsLatestWinsOnBothSidesAndBothAreNotified() {
        val onA = milk.edit("A", 200) { copy(checked = true) }
        val onB = milk.edit("B", 300) { copy(deleted = true) }
        val a = empty.with(onA).merge(listOf(onB))
        val b = empty.with(onB).merge(listOf(onA))

        assertEquals(onB, a.list.items["milk"])
        assertEquals(onB, b.list.items["milk"])
        assertTrue(a.conflicts.single().localLost)
        assertEquals(false, b.conflicts.single().localLost)
        assertEquals(onA, a.conflicts.single().loser)
    }

    @Test
    fun identicalConcurrentEditsConvergeSilently() {
        val onA = milk.edit("A", 200) { copy(checked = true) }
        val onB = milk.edit("B", 300) { copy(checked = true) }
        val a = empty.with(onA).merge(listOf(onB))
        assertEquals(onB, a.list.items["milk"])
        assertTrue(a.conflicts.isEmpty())
    }

    @Test
    fun conflictIsOnlyReportedOnceAcrossRepeatedSyncs() {
        val onA = milk.edit("A", 200) { copy(checked = true) }
        val onB = milk.edit("B", 300) { copy(text = "Soy milk") }
        val once = empty.with(onA).merge(listOf(onB)).list
        // Peer B sends full state again on reconnect; A also re-receives its own losing version via a third device.
        val again = once.merge(listOf(onB, onA))
        assertTrue(again.conflicts.isEmpty())
        assertEquals(onB, again.list.items["milk"])
    }

    @Test
    fun editAfterConflictFastForwardsOnTheOtherSide() {
        val onA = milk.edit("A", 200) { copy(checked = true) }
        val onB = milk.edit("B", 300) { copy(text = "Soy milk") }
        val a = empty.with(onA).merge(listOf(onB)).list
        val b = empty.with(onB).merge(listOf(onA)).list
        val aEdits = a.items.getValue("milk").edit("A", 400) { copy(checked = true) }
        val result = b.merge(listOf(aEdits))
        assertTrue(result.conflicts.isEmpty())
        assertEquals(aEdits, result.list.items["milk"])
    }

    @Test
    fun editKeepsVersionsIncreasingWhenClockIsBehind() {
        val edited = milk.edit("B", now = 50) { copy(checked = true) }
        assertTrue(edited.version > milk.version)
    }

    @Test
    fun shareLinkRoundTrips() {
        val list = empty.copy(name = "Trip & stuff")
        val invite = ShareLink.parse(ShareLink.build(list))
        assertNotNull(invite)
        assertEquals(Invite(list.id, list.name, list.secret), invite)
        assertNull(ShareLink.parse("https://example.com"))
        assertNull(ShareLink.parse("twodo://join?id=x&k=short"))
    }

    @Test
    fun encryptionRoundTripsAndRejectsOtherKeys() {
        val keys = ListKeys(empty.secret)
        val message = keys.encrypt("hello")
        assertEquals("hello", keys.decrypt(message))
        assertNull(ListKeys(ListKeys.newSecret()).decrypt(message))
        assertEquals(20, keys.topic.length)
    }
}
