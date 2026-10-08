package app.twodo.sync

import app.twodo.model.Item

/** Things other people did to a shared list, for notifications and in-app highlights. */
sealed interface ListEvent {
    val listId: String
    val listName: String

    data class Joined(override val listId: String, override val listName: String, val who: String) : ListEvent
    data class Left(override val listId: String, override val listName: String, val who: String) : ListEvent

    /** [lines] like "ticked Milk"; [itemIds] are the items affected. */
    data class Changed(
        override val listId: String,
        override val listName: String,
        val who: String,
        val lines: List<String>,
        val itemIds: List<String>,
    ) : ListEvent
}

/** What a remote edit did to an item, e.g. "ticked Milk"; null for moves and anything not worth mentioning. */
fun describeChange(before: Item?, after: Item): String? = when {
    after.deleted -> if (before == null || before.deleted) null else "removed ${after.text}"
    before == null || before.deleted -> "added ${after.text}"
    before.checked != after.checked -> (if (after.checked) "ticked " else "unticked ") + after.text
    before.text != after.text -> "renamed ${before.text} to ${after.text}"
    else -> null
}
