package app.twodo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionsTest {
    private fun item(id: String, heading: Boolean = false) =
        Item(id, id, createdAt = 0, version = Version(1, "A"), editor = "A", heading = heading)

    // bread, [Dairy] milk cheese, [Fruit] apples
    private val ordered = listOf(item("bread"), item("dairy", true), item("milk"), item("cheese"), item("fruit", true), item("apples"))

    @Test
    fun itemsBelongToTheHeadingAboveThem() {
        assertNull(sectionOf(ordered, "bread"))
        assertEquals("dairy", sectionOf(ordered, "cheese")?.id)
        assertEquals("fruit", sectionOf(ordered, "apples")?.id)
    }

    @Test
    fun movingToASectionPutsItAtTheEndOfThatSection() {
        // Bread into Dairy: after cheese, before Fruit (index among the others, without bread).
        assertEquals(3, indexForSection(ordered, "bread", "dairy"))
        // Apples into Dairy: before Fruit.
        assertEquals(4, indexForSection(ordered, "apples", "dairy"))
        // Into the last section: the very end.
        assertEquals(5, indexForSection(ordered, "milk", "fruit"))
        // Out of any section: above the first heading.
        assertEquals(1, indexForSection(ordered, "apples", null))
        assertEquals(0, indexForSection(listOf(item("dairy", true), item("milk")), "milk", null))
    }

    @Test
    fun headingsAreDescribedAsHeadings() {
        val dairy = item("dairy", true).copy(text = "Dairy")
        assertEquals("added the heading Dairy", describeChange(null, dairy))
        assertEquals("renamed the heading Dairy to Milk & eggs", describeChange(dairy, dairy.copy(text = "Milk & eggs")))
    }
}
