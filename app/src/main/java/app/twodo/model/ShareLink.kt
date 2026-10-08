package app.twodo.model

import java.net.URLDecoder
import java.net.URLEncoder

data class Invite(val listId: String, val name: String, val secret: String, val kind: SpaceKind = SpaceKind.LIST)

/**
 * What the QR code and "Send link" carry. Anyone holding it can edit the list.
 *
 * Shared as `https://rad-corps.github.io/twodo/join/#id=…&name=…&k=…` so chat apps make it tappable;
 * that page opens TwoDo. The parameters sit after `#`, which browsers never send to the server.
 * The older `twodo://join?…` form is still accepted.
 */
object ShareLink {
    const val WEB_PREFIX = "https://rad-corps.github.io/twodo/join/#"
    private val linkPattern = Regex("""(?:twodo://join\?|https://rad-corps\.github\.io/twodo/join/?#)(\S+)""")

    fun build(list: TodoList): String =
        "${WEB_PREFIX}id=${list.id}&name=${URLEncoder.encode(list.name, "UTF-8")}&k=${list.secret}" +
            if (list.kind == SpaceKind.DIARY) "&t=diary" else ""

    /** Message text to send along with the link. */
    fun message(list: TodoList): String = "Join “${list.name}” on TwoDo: ${build(list)}"

    /** Finds a TwoDo link anywhere in [text], e.g. a whole pasted message. */
    fun parse(text: String): Invite? {
        val query = linkPattern.find(text)?.groupValues?.get(1) ?: return null
        val params = query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i < 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }.toMap()
        val id = params["id"]?.takeIf { it.isNotBlank() } ?: return null
        val secret = params["k"]?.takeIf { ListKeys.isValidSecret(it) } ?: return null
        val kind = if (params["t"] == "diary") SpaceKind.DIARY else SpaceKind.LIST
        return Invite(id, params["name"].orEmpty().ifBlank { "Shared list" }, secret, kind)
    }
}
