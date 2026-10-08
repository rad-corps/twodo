package app.twodo.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val dayWithYearFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

/** e.g. "Fri 9 Oct", with the year if it isn't this year. */
fun formatDay(date: LocalDate, today: LocalDate = LocalDate.now()): String =
    date.format(if (date.year == today.year) dayFormat else dayWithYearFormat)

/** " on Fri 9 Oct at 15:00" for diary entries, "" for list items. */
private fun Item.whenText(): String {
    val day = localDate ?: return ""
    return " on ${formatDay(day)}" + (time?.let { " at $it" } ?: "")
}

/**
 * What an edit did to an item, e.g. "ticked Milk" or "added Dentist on Fri 9 Oct at 15:00";
 * null for moves and anything not worth mentioning.
 */
fun describeChange(before: Item?, after: Item): String? = when {
    after.deleted -> if (before == null || before.deleted) null else "removed ${before.text}${before.whenText()}"
    before == null || before.deleted -> "added ${after.text}${after.whenText()}"
    before.checked != after.checked -> (if (after.checked) "ticked " else "unticked ") + after.text
    else -> buildList {
        if (before.text != after.text) add("renamed ${before.text} to ${after.text}")
        if (before.date != after.date) after.localDate?.let { add("moved ${after.text} to ${formatDay(it)}") }
        if (before.time != after.time) add(after.time?.let { "set ${after.text} to $it" } ?: "removed the time from ${after.text}")
    }.joinToString(", ").ifEmpty { null }
}

/** The audit entry for a local edit, or null if the edit isn't worth recording (e.g. a move). */
fun auditEntryFor(before: Item?, after: Item): AuditEntry? {
    val description = describeChange(before, after) ?: return null
    return AuditEntry(
        id = "${after.id}/${after.version.ts}/${after.version.by}",
        itemId = after.id,
        ts = after.version.ts,
        by = after.version.by,
        byName = after.editor,
        description = description,
    )
}

/** Adds audit entries from a peer; the log only grows, so merging is a union. Returns the new ones too. */
fun TodoList.mergeAudit(entries: Collection<AuditEntry>): Pair<TodoList, List<AuditEntry>> {
    val added = entries.filter { it.id !in audit }
    if (added.isEmpty()) return this to emptyList()
    return copy(audit = audit + added.associateBy { it.id }) to added
}
