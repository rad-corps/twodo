package app.twodo.sync

import app.twodo.model.SpaceKind

/** Things other people did to a shared list, for notifications and in-app highlights. */
sealed interface ListEvent {
    val listId: String
    val listName: String

    data class Joined(override val listId: String, override val listName: String, val who: String) : ListEvent
    data class Left(override val listId: String, override val listName: String, val who: String) : ListEvent

    /** This device finished joining a shared list: everything from [who] has arrived. */
    data class JoinedList(override val listId: String, override val listName: String, val who: String) : ListEvent

    /**
     * [lines] like "ticked Milk"; [itemIds] are the items affected; [added] says, line by line, whether it
     * was something new (rather than ticked, changed or removed). [kind] is the kind of space it's in.
     */
    data class Changed(
        override val listId: String,
        override val listName: String,
        val who: String,
        val lines: List<String>,
        val itemIds: List<String>,
        val added: List<Boolean> = lines.map { false },
        val kind: SpaceKind = SpaceKind.LIST,
    ) : ListEvent
}
