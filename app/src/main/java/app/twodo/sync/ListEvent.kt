package app.twodo.sync

/** Things other people did to a shared list, for notifications and in-app highlights. */
sealed interface ListEvent {
    val listId: String
    val listName: String

    data class Joined(override val listId: String, override val listName: String, val who: String) : ListEvent
    data class Left(override val listId: String, override val listName: String, val who: String) : ListEvent

    /** This device finished joining a shared list: everything from [who] has arrived. */
    data class JoinedList(override val listId: String, override val listName: String, val who: String) : ListEvent

    /** [lines] like "ticked Milk"; [itemIds] are the items affected. */
    data class Changed(
        override val listId: String,
        override val listName: String,
        val who: String,
        val lines: List<String>,
        val itemIds: List<String>,
    ) : ListEvent
}
