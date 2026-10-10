package app.twodo.model

import java.time.LocalTime

/**
 * A time found in what someone typed for a calendar entry ("Dentist 11:30am"). [range] is the part of
 * the text it came from (with any "at" / "@" before it); [removable] says whether taking that out
 * leaves sensible words — a range like "3-4pm" stays, since the end time has nowhere else to go.
 */
data class FoundTime(val time: LocalTime, val range: IntRange, val removable: Boolean)

private const val MERIDIEM = """([ap])\.?\s?m\.?"""
private const val CLOCK = """(\d{1,2})(?:([:.])(\d{2}))?\s?(?:$MERIDIEM)?"""

// Not inside a word, number, price, date or ratio: "$11.30", "10/12", "v1.2", "2024", "5km", "3:1".
private val NUMERIC = Regex(
    """(?:\bat\s+|@\s*)?(?<![\w$£€.:/])""" + CLOCK +
        """(?:\s*(?:-|–|to|till|until)\s*""" + CLOCK + """)?(?![\w:/%])""",
    RegexOption.IGNORE_CASE,
)
private val WORDS = Regex("""(?:\bat\s+|@\s*)(noon|midday|midnight)\b""", RegexOption.IGNORE_CASE)

/**
 * The first time in [text], if it clearly is one: "11:30am", "11.30 am", "11am", "7 p.m.", "14:30",
 * "3:30", "3-4pm" (the start), "at noon". A bare number ("at 3", "2-3 apples") isn't a time, nor is a
 * dot without am/pm ("11.30" may be a price). A colon time without am/pm from 1:00 to 6:59, on a
 * [twelveHour] phone, is taken as the afternoon: people rarely mean 3:30 in the morning.
 */
fun findTime(text: String, twelveHour: Boolean): FoundTime? {
    NUMERIC.findAll(text).forEach { m -> numericTime(m, twelveHour)?.let { return FoundTime(it, m.range, removable = m.groupValues[5].isEmpty()) } }
    return WORDS.find(text)?.let { m ->
        val time = if (m.groupValues[1].equals("midnight", ignoreCase = true)) LocalTime.MIDNIGHT else LocalTime.NOON
        FoundTime(time, m.range, removable = true)
    }
}

private fun numericTime(m: MatchResult, twelveHour: Boolean): LocalTime? {
    val g = m.groupValues
    val hour = g[1].toInt()
    val minute = g[3].ifEmpty { "0" }.toInt()
    val ownMeridiem = g[4].lowercase()
    val endMeridiem = g[8].lowercase()
    if (minute > 59) return null
    if (ownMeridiem.isNotEmpty()) return withMeridiem(hour, minute, ownMeridiem == "p")
    if (endMeridiem.isNotEmpty()) {
        // "3-4pm" is 3pm; "11-1pm" is 11am.
        val endHour = g[5].toInt()
        val pm = endMeridiem == "p" && !(hour in 1..11 && endHour in 1..11 && hour > endHour)
        return withMeridiem(hour, minute, pm)
    }
    // No am/pm anywhere: only a colon with minutes counts.
    if (g[2] != ":" || hour > 23) return null
    val afternoon = twelveHour && hour in 1..6 && !g[1].startsWith("0")
    return LocalTime.of(if (afternoon) hour + 12 else hour, minute)
}

private fun withMeridiem(hour: Int, minute: Int, pm: Boolean): LocalTime? {
    if (hour !in 1..12) return null
    return LocalTime.of(hour % 12 + if (pm) 12 else 0, minute)
}

/** [text] without the time that was found in it — or as it was, if that would leave nothing. */
fun withoutTime(text: String, found: FoundTime): String {
    if (!found.removable) return text
    val rest = text.removeRange(found.range)
        .replace(Regex("""\s{2,}"""), " ")
        .replace(Regex("""\s+([,.;!?])"""), "$1")
        .trim()
        .trim(',', '-', '–', ';', ':')
        .trim()
    return rest.ifBlank { text.trim() }
}
