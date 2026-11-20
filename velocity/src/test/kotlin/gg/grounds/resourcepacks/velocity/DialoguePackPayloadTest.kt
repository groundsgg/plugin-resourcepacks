package gg.grounds.resourcepacks.velocity

import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.*

class DialoguePackPayloadTest {
    @Test
    fun `wire includes exact pin UUID positive revision and readiness`() {
        val id = UUID.randomUUID()
        val pin = "a".repeat(64)
        val bytes = DialoguePackPayload.encode(id, PlayerChatStyleSnapshot(42, true), pin)
        assertEquals(90, bytes.size)
        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(1, buffer.get().toInt())
        assertEquals(id.mostSignificantBits, buffer.long)
        assertEquals(id.leastSignificantBits, buffer.long)
        assertEquals(42, buffer.long)
        assertEquals(1, buffer.get().toInt())
        assertEquals(pin, String(ByteArray(64).also(buffer::get), Charsets.US_ASCII))
        assertFailsWith<IllegalArgumentException> {
            DialoguePackPayload.encode(id, PlayerChatStyleSnapshot(0, false), pin)
        }
        assertFailsWith<IllegalArgumentException> {
            DialoguePackPayload.encode(id, PlayerChatStyleSnapshot(1, true), "A".repeat(64))
        }
    }

    @Test
    fun `mapping is atomic and independent of chat and sidebar configuration`() {
        assertTrue(
            DialogueAppearanceEnvironment.from(mapOf("SIDEBAR_FONT_PACK_SHA1S" to "a".repeat(40)))
                .hashes
                .isEmpty()
        )
        for (env in
            listOf(
                mapOf("DIALOGUE_PACK_SHA1" to "a".repeat(40)),
                mapOf("DIALOGUE_ASSET_PIN" to "b".repeat(64)),
            )) {
            assertFailsWith<IllegalArgumentException> { DialogueAppearanceEnvironment.from(env) }
        }
        val config =
            DialogueAppearanceEnvironment.from(
                mapOf(
                    "DIALOGUE_PACK_SHA1" to "a".repeat(40),
                    "DIALOGUE_ASSET_PIN" to "b".repeat(64),
                )
            )
        assertEquals(setOf("a".repeat(40)), config.hashes)
        assertTrue(DialogueAppearanceEnvironment.eligible(777, false))
        assertFalse(DialogueAppearanceEnvironment.eligible(776, false))
        assertFalse(DialogueAppearanceEnvironment.eligible(777, true))
        assertFalse(DialogueAppearanceEnvironment.eligible(777, null))
    }

    @Test
    fun `replacement stale success failure and ambiguous UUID cannot grant readiness`() {
        val id = UUID.randomUUID()
        val first = UUID.randomUUID()
        val next = UUID.randomUUID()
        val tracker = PlayerChatAppearanceTracker(setOf("a".repeat(40)))
        tracker.offered(id, mapOf(first to "b".repeat(40)))
        tracker.loaded(id, first)
        assertFalse(tracker.chatFontReady(id))
        tracker.offered(id, mapOf(next to "a".repeat(40)))
        tracker.loaded(id, first)
        assertFalse(tracker.chatFontReady(id))
        tracker.loaded(id, next)
        assertTrue(tracker.chatFontReady(id))
        tracker.failed(id, next)
        assertFalse(tracker.chatFontReady(id))
        tracker.loaded(id, next)
        tracker.offered(id, mapOf(first to "a".repeat(40)))
        assertFalse(tracker.chatFontReady(id))
        tracker.loaded(id, first)
        assertFalse(tracker.chatFontReady(id))
        tracker.reset(id)
        tracker.loaded(id, next)
        assertFalse(tracker.chatFontReady(id))
    }
}
