package app.twodo.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Identifies one edit of an item: when it was made and by which device. Ordered by time, then device. */
@Serializable
data class Version(val ts: Long, val by: String) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, { it.ts }, { it.by })
}

/** A to-do entry. Every edit replaces the whole item with a new [version]. */
@Serializable
data class Item(
    val id: String,
    val text: String,
    val checked: Boolean = false,
    /** Deletes are tombstones so they sync like any other edit. */
    val deleted: Boolean = false,
    val createdAt: Long,
    val version: Version,
    /** Display name of the device that made [version]. */
    val editor: String,
    /** Versions this one descends from, newest first, capped at [HISTORY_LIMIT]. */
    val history: List<Version> = emptyList(),
    /**
     * Manual sort position. Synced as its own last-writer-wins value (stamped by [posVersion]) so that
     * moving an item never conflicts with someone ticking it.
     */
    val pos: Double? = null,
    val posVersion: Version? = null,
    /** Diary entries: the day (ISO yyyy-MM-dd) and optional time (HH:mm). Null for list items. */
    val date: String? = null,
    val time: String? = null,
    /**
     * Group entries: the space this entry stands for (its id, secret and kind). Joining the group joins
     * every space it lists. Null for list items and diary entries.
     */
    val spaceId: String? = null,
    val spaceSecret: String? = null,
    val spaceKind: SpaceKind? = null,
    /**
     * A section heading in a list ("Dairy"), not something to tick. Items below it, up to the next
     * heading, are in its section. Older versions ignore this and show it as an item.
     */
    val heading: Boolean = false,
) {
    val position: Double get() = pos ?: createdAt.toDouble()
    val localDate: LocalDate? get() = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/** One recorded change: who did what, when. The log is shared and only ever grows. */
@Serializable
data class AuditEntry(
    /** Derived from the item version, so the same edit always has the same id. */
    val id: String,
    val itemId: String,
    val ts: Long,
    /** Device id of whoever made the change. */
    val by: String,
    /** Their name at the time. */
    val byName: String,
    /** e.g. "ticked Milk", "added Dentist on Fri 9 Oct at 15:00". */
    val description: String,
)

@Serializable
enum class SpaceKind {
    @SerialName("list") LIST,
    @SerialName("diary") DIARY,
    /** A set of people sharing a calendar and lists; its items are the spaces in it. */
    @SerialName("group") GROUP,
}

@Serializable
data class TodoList(
    val id: String,
    val name: String,
    /** Shared secret from the QR code; derives the room id and the encryption key. */
    val secret: String,
    val items: Map<String, Item> = emptyMap(),
    /** Local only: concurrent versions that lost a conflict, so they're never re-applied. */
    val superseded: Map<String, Set<Version>> = emptyMap(),
    /** Local only: other devices seen on this list (device id -> name). */
    val members: Map<String, String> = emptyMap(),
    /** Local only: this device created the list (rather than joining it). */
    val createdHere: Boolean = false,
    val kind: SpaceKind = SpaceKind.LIST,
    /** Local only: this phone's colour theme for the list (null: follow the app's theme). */
    val themeId: String? = null,
    /** Local only: this device has had a complete copy of the list from someone (or created it). */
    val fullSynced: Boolean = false,
    /** Local only: newest relay event seen (unix seconds), to fetch only what's new next time. */
    val relaySince: Long = 0,
    /** Every recorded change, by [AuditEntry.id]. Synced. */
    val audit: Map<String, AuditEntry> = emptyMap(),
    /** Local only: the group this space belongs to, if any. */
    val groupId: String? = null,
) {
    val visibleItems: List<Item>
        get() = items.values.filter { !it.deleted }.sortedWith(compareBy({ it.position }, { it.id }))
}

/**
 * How a group looks, shared by everyone in it (stored as JSON in the group's [GROUP_LOOK_ITEM]).
 * The photo travels as [photoParts] base64 pieces in items `photo:<photoId>:<n>`.
 */
@Serializable
data class Look(
    /** Colour theme; null follows each phone's app theme. "photo" takes the colours from the photo. */
    val themeId: String? = null,
    val photoId: String? = null,
    val photoParts: Int = 0,
    /** How strongly the photo shows through, 0 (barely) to 1 (fully). */
    val photoStrength: Float = 0.45f,
    /** Accent colour (ARGB) overriding the theme's; null keeps the theme's. */
    val accent: Long? = null,
)

/** Just joined and nothing received yet. */
val TodoList.isJoining: Boolean get() = !createdHere && members.isEmpty() && items.isEmpty()

/** Two devices edited the same item concurrently; [winner] (the later edit) was kept. */
data class Conflict(
    val listId: String,
    val listName: String,
    val winner: Item,
    val loser: Item,
    /** True when this device's edit was the one overridden. */
    val localLost: Boolean,
)
