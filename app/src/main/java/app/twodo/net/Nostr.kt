package app.twodo.net

import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.security.MessageDigest
import java.security.SecureRandom

/** Kind of TwoDo's stored Nostr events (edits, leaving); content is encrypted. */
const val TWODO_KIND = 4333

/** Kind of TwoDo's ephemeral Nostr events (hello, whole-list replies): relayed live, not stored. */
const val TWODO_LIVE_KIND = 24333

/** A Nostr event (NIP-01). */
@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
)

/**
 * Each list has its own Nostr identity, derived from the list's secret, so every member can publish
 * and fetch the list's (encrypted) messages. Relays see only this key, not who the members are.
 */
class NostrIdentity(listSecret: String) {
    private val privateKey: ByteArray = deriveKey(listSecret)
    val publicKey: String = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(privateKey)).copyOfRange(1, 33).toHex()

    fun sign(kind: Int, tags: List<List<String>>, content: String, createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent {
        val id = eventId(publicKey, createdAt, kind, tags, content)
        val aux = ByteArray(32).also { random.nextBytes(it) }
        val sig = Secp256k1.signSchnorr(id.fromHex(), privateKey, aux).toHex()
        return NostrEvent(id, publicKey, createdAt, kind, tags, content, sig)
    }

    companion object {
        private val random = SecureRandom()

        private fun deriveKey(secret: String): ByteArray {
            var key = sha256("twodo/nostr/$secret".toByteArray())
            // A hash is a valid secp256k1 key except with negligible probability; rehash if not.
            while (!Secp256k1.secKeyVerify(key)) key = sha256(key)
            return key
        }

        /** True if [event]'s id matches its contents and it's signed by its pubkey. */
        fun isValid(event: NostrEvent): Boolean = runCatching {
            event.id == eventId(event.pubkey, event.created_at, event.kind, event.tags, event.content) &&
                Secp256k1.verifySchnorr(event.sig.fromHex(), event.id.fromHex(), event.pubkey.fromHex())
        }.getOrDefault(false)

        /** NIP-01 event id: sha256 of `[0, pubkey, created_at, kind, tags, content]`. */
        fun eventId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val serialized = buildJsonArray {
                add(JsonPrimitive(0))
                add(JsonPrimitive(pubkey))
                add(JsonPrimitive(createdAt))
                add(JsonPrimitive(kind))
                add(JsonArray(tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }))
                add(JsonPrimitive(content))
            }.toString()
            return sha256(serialized.toByteArray()).toHex()
        }
    }
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
