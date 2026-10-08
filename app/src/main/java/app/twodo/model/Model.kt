package app.twodo.model

import kotlinx.serialization.Serializable

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
)

@Serializable
data class TodoList(
    val id: String,
    val name: String,
    /** Shared secret from the QR code; derives the room id and the encryption key. */
    val secret: String,
    val items: Map<String, Item> = emptyMap(),
    /** Local only: concurrent versions that lost a conflict, so they're never re-applied. */
    val superseded: Map<String, Set<Version>> = emptyMap(),
) {
    val visibleItems: List<Item>
        get() = items.values.filter { !it.deleted }
            .sortedWith(compareBy({ it.checked }, { it.createdAt }, { it.id }))
}

/** Two devices edited the same item concurrently; [winner] (the later edit) was kept. */
data class Conflict(
    val listId: String,
    val listName: String,
    val winner: Item,
    val loser: Item,
    /** True when this device's edit was the one overridden. */
    val localLost: Boolean,
)
