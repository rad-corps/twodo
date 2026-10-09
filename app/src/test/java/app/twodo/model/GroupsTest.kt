package app.twodo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsTest {
    private val calendarSecret = ListKeys.newSecret()
    private val shoppingSecret = ListKeys.newSecret()

    private fun ref(id: String, name: String, secret: String, kind: SpaceKind, createdAt: Long) =
        Item(id, name, createdAt = createdAt, version = Version(createdAt, "A"), editor = "Phone A",
            spaceId = id, spaceSecret = secret, spaceKind = kind)

    private val calendar = ref("cal", "Calendar", calendarSecret, SpaceKind.DIARY, 10)
    private val shopping = ref("shop", "Shopping", shoppingSecret, SpaceKind.LIST, 20)
    private val name = Item(GROUP_NAME_ITEM, "Smiths", createdAt = 0, version = Version(5, "A"), editor = "Phone A")
    private val group = TodoList("g", "Family", ListKeys.newSecret(), kind = SpaceKind.GROUP)
        .copy(items = listOf(calendar, shopping, name).associateBy { it.id })

    @Test
    fun newMemberJoinsEverySpaceInTheGroup() {
        val plan = planGroup(group, mapOf(group.id to group))
        assertEquals(
            listOf(Invite("cal", "Calendar", calendarSecret, SpaceKind.DIARY), Invite("shop", "Shopping", shoppingSecret, SpaceKind.LIST)),
            plan.join.sortedBy { it.listId },
        )
        assertTrue(plan.update.isEmpty() && plan.remove.isEmpty())
    }

    @Test
    fun spacesAlreadyHereAreAdoptedAndRenamed() {
        val local = TodoList("shop", "Groceries", shoppingSecret)
        val plan = planGroup(group, mapOf("shop" to local))
        assertEquals(listOf(local.copy(groupId = "g", name = "Shopping")), plan.update)
        assertEquals(listOf("cal"), plan.join.map { it.listId })
    }

    @Test
    fun deletedSpacesAreRemovedOnlyIfTheyBelongToTheGroup() {
        val deleted = group.copy(items = group.items + ("shop" to shopping.edited("B", "Phone B", 30) { copy(deleted = true) }))
        val mine = TodoList("shop", "Shopping", shoppingSecret, groupId = "g")
        assertEquals(listOf("shop"), planGroup(deleted, mapOf("shop" to mine)).remove)
        // Someone else's group can't take a list away from this phone.
        assertTrue(planGroup(deleted, mapOf("shop" to mine.copy(groupId = null))).remove.isEmpty())
    }

    @Test
    fun calendarIsTheOldestDiaryAndTheNameIsSynced() {
        val second = ref("cal2", "Calendar 2", ListKeys.newSecret(), SpaceKind.DIARY, 40)
        val withTwo = group.copy(items = group.items + (second.id to second))
        assertEquals("cal", withTwo.calendarRef?.spaceId)
        assertEquals(listOf("shop"), withTwo.listRefs.map { it.spaceId })
        assertEquals("Smiths", group.syncedGroupName)
        assertNull(group.copy(items = group.items - GROUP_NAME_ITEM).syncedGroupName)
    }

    @Test
    fun groupLinksRoundTrip() {
        val invite = ShareLink.parse(ShareLink.build(group))
        assertEquals(Invite("g", "Family", group.secret, SpaceKind.GROUP), invite)
    }

    @Test
    fun sharedLookAndPhotoAreReassembled() {
        val photo = "A".repeat(PHOTO_PART_CHARS * 2 + 10)
        val parts = splitPhoto(photo)
        assertEquals(3, parts.size)
        val look = Look(themeId = "photo", photoId = "p1", photoParts = parts.size, accent = 0xFF336699)
        val lookItem = Item(GROUP_LOOK_ITEM, look.toJson(), createdAt = 0, version = Version(1, "A"), editor = "A")
        val partItems = parts.mapIndexed { i, text -> Item(photoPartId("p1", i), text, createdAt = 0, version = Version(1, "A"), editor = "A") }
        val withLook = group.copy(items = group.items + (listOf(lookItem) + partItems).associateBy { it.id })
        assertEquals(look, withLook.sharedLook)
        assertEquals(photo, withLook.photoBase64())
        // Until every piece is here, there's no photo yet.
        assertNull(withLook.copy(items = withLook.items - photoPartId("p1", 1)).photoBase64())
        assertEquals(Look(), group.sharedLook)
        // Photo pieces and settings aren't spaces.
        assertEquals(listOf("cal", "shop"), withLook.spaceRefs.map { it.spaceId })
    }
}
