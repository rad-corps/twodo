package app.twodo.model

/** A list's section headings, in order. */
val TodoList.headings: List<Item> get() = visibleItems.filter { it.heading }

/** Things to tick: everything but headings. */
val TodoList.tickable: List<Item> get() = visibleItems.filter { !it.heading }

/** The heading [itemId] sits under in [ordered] (a list's items in order), or null if it's above them all. */
fun sectionOf(ordered: List<Item>, itemId: String): Item? {
    val index = ordered.indexOfFirst { it.id == itemId }
    if (index < 0) return null
    return ordered.subList(0, index).lastOrNull { it.heading }
}

/**
 * Where [itemId] goes, as an index among the other items, to join [headingId]'s section — at its end,
 * just before the next heading — or, with null, to sit above every heading.
 */
fun indexForSection(ordered: List<Item>, itemId: String, headingId: String?): Int {
    val others = ordered.filter { it.id != itemId }
    if (headingId == null) return others.indexOfFirst { it.heading }.let { if (it < 0) others.size else it }
    val start = others.indexOfFirst { it.id == headingId }
    if (start < 0) return others.size
    val next = others.subList(start + 1, others.size).indexOfFirst { it.heading }
    return if (next < 0) others.size else start + 1 + next
}
