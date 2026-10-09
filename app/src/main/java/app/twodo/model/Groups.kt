package app.twodo.model

/**
 * A group is itself a shared space (kind [SpaceKind.GROUP]) whose items stand for the spaces in it:
 * each carries the space's id, secret and kind, with its name as the text. So the group syncs like any
 * list, and whoever joins it learns everything needed to join its calendar and lists. One special item
 * holds the group's own name, so renaming the group reaches everyone.
 */
const val GROUP_NAME_ITEM = "group-name"

/** The spaces in this group, oldest first (the default calendar and list come first). */
val TodoList.spaceRefs: List<Item>
    get() = items.values.filter { !it.deleted && it.spaceId != null }.sortedWith(compareBy({ it.createdAt }, { it.id }))

/** The group's calendar: its oldest diary. Groups have one; if two were ever made, the first one counts. */
val TodoList.calendarRef: Item? get() = spaceRefs.firstOrNull { it.spaceKind == SpaceKind.DIARY }

val TodoList.listRefs: List<Item> get() = spaceRefs.filter { it.spaceKind == SpaceKind.LIST }

/** The group's synced name, if anyone has set one. */
val TodoList.syncedGroupName: String? get() = items[GROUP_NAME_ITEM]?.takeIf { !it.deleted }?.text

fun Item.toInvite(): Invite? {
    val id = spaceId ?: return null
    val secret = spaceSecret ?: return null
    return Invite(id, text, secret, spaceKind ?: SpaceKind.LIST)
}

/** What this phone should change so its spaces match [group]. */
data class GroupPlan(
    /** Spaces in the group this phone doesn't have yet. */
    val join: List<Invite>,
    /** Spaces this phone has that should now be marked as in the group, or renamed. */
    val update: List<TodoList>,
    /** Spaces removed from the group by someone: remove them here too. */
    val remove: List<String>,
)

/** Works out how [lists] (everything on this phone) should change to follow [group]. */
fun planGroup(group: TodoList, lists: Map<String, TodoList>): GroupPlan {
    val join = mutableListOf<Invite>()
    val update = mutableListOf<TodoList>()
    val remove = mutableListOf<String>()
    for (item in group.items.values) {
        val spaceId = item.spaceId ?: continue
        val local = lists[spaceId]
        when {
            item.deleted -> if (local?.groupId == group.id) remove += spaceId
            local == null -> item.toInvite()?.let { join += it }
            local.groupId != group.id || local.name != item.text -> update += local.copy(groupId = group.id, name = item.text)
        }
    }
    return GroupPlan(join, update, remove)
}

/** Holds the group's shared [Look] as JSON. */
const val GROUP_LOOK_ITEM = "group-look"

private const val PHOTO_PREFIX = "photo:"

/** Base64 characters per photo piece: small enough for one relay event each. */
const val PHOTO_PART_CHARS = 16_000

fun photoPartId(photoId: String, index: Int) = "$PHOTO_PREFIX$photoId:$index"

val Item.isPhotoPart: Boolean get() = id.startsWith(PHOTO_PREFIX)

private val lookJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** The group's shared look; the default (follow each phone's theme, no photo) if none was set. */
val TodoList.sharedLook: Look
    get() = items[GROUP_LOOK_ITEM]?.takeIf { !it.deleted }
        ?.let { runCatching { lookJson.decodeFromString<Look>(it.text) }.getOrNull() } ?: Look()

fun Look.toJson(): String = lookJson.encodeToString(this)

/** The look's photo as base64, once every piece has arrived; null if there's no photo or it's incomplete. */
fun TodoList.photoBase64(look: Look = sharedLook): String? {
    val photoId = look.photoId ?: return null
    if (look.photoParts <= 0) return null
    val parts = (0 until look.photoParts).map { items[photoPartId(photoId, it)]?.takeIf { p -> !p.deleted }?.text ?: return null }
    return parts.joinToString("")
}

/** Splits a base64 photo into pieces of at most [PHOTO_PART_CHARS]. */
fun splitPhoto(base64: String): List<String> = base64.chunked(PHOTO_PART_CHARS)
