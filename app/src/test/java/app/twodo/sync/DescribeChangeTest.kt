package app.twodo.sync

import app.twodo.model.Item
import app.twodo.model.Version
import app.twodo.model.edited
import app.twodo.model.moved
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
}
