package app.twodo.model

import java.net.URLDecoder
import java.net.URLEncoder

data class Invite(val listId: String, val name: String, val secret: String)

/** `twodo://join?id=…&name=…&k=…` — what the QR code carries. Anyone holding it can edit the list. */
object ShareLink {
    private const val PREFIX = "twodo://join?"

    fun build(list: TodoList): String =
        "${PREFIX}id=${list.id}&name=${URLEncoder.encode(list.name, "UTF-8")}&k=${list.secret}"

    fun parse(text: String): Invite? {
        val query = text.trim().substringAfter(PREFIX, "").ifEmpty { return null }
        val params = query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i < 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }.toMap()
        val id = params["id"]?.takeIf { it.isNotBlank() } ?: return null
        val secret = params["k"]?.takeIf { ListKeys.isValidSecret(it) } ?: return null
        return Invite(id, params["name"].orEmpty().ifBlank { "Shared list" }, secret)
    }
}
