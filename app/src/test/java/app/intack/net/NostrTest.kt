package app.intack.net

import fr.acinq.secp256k1.Secp256k1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrTest {
    // Reference values from @noble/curves (see scratch vector.mjs): key = sha256("twodo/nostr/" + secret).
    private val secret = "test-secret-for-vectors"

    @Test
    fun publicKeyAndEventIdMatchReference() {
        val identity = NostrIdentity(secret)
        assertEquals("525b4eda32d879a6bb743e3fc2bbf7b8a296e6d04cf813083f7110db7fe3e2c9", identity.publicKey)
        val id = NostrIdentity.eventId(identity.publicKey, 1760000000, 4333, listOf(listOf("expiration", "1761209600")), "aGVsbG8gIndvcmxkIgo=")
        assertEquals("65a40beac2c00d39d8669569d165b0ed4076574da5f6aaa894086fb4145c6fa4", id)
    }

    @Test
    fun signedEventsVerify() {
        val identity = NostrIdentity(secret)
        val event = identity.sign(TWODO_KIND, emptyList(), "payload")
        assertEquals(NostrIdentity.eventId(event.pubkey, event.created_at, event.kind, event.tags, event.content), event.id)
        assertTrue(Secp256k1.verifySchnorr(event.sig.fromHex(), event.id.fromHex(), event.pubkey.fromHex()))
    }
}
