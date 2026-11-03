package gg.grounds.resourcepacks.velocity

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerHudAppearanceTest {
    @Test fun `only explicit HUD hashes enable a loaded pack`() {
        val id = UUID.randomUUID(); val pack = UUID.randomUUID(); val hash = "a".repeat(40)
        val tracker = PlayerChatAppearanceTracker(HudAppearanceEnvironment.from(mapOf("HUD_FONT_PACK_SHA1S" to hash)))
        tracker.offered(id, mapOf(pack to hash))
        assertFalse(tracker.chatFontReady(id))
        tracker.loaded(id, pack)
        assertTrue(tracker.chatFontReady(id))
        assertTrue(HudAppearanceEnvironment.eligible(777, false))
        assertFalse(HudAppearanceEnvironment.eligible(777, true))
        assertFalse(HudAppearanceEnvironment.eligible(776, false))
        tracker.failed(id,pack); assertFalse(tracker.chatFontReady(id))
        tracker.offered(id,mapOf(pack to "b".repeat(40)));tracker.loaded(id,pack)
        assertFalse(tracker.chatFontReady(id))
        tracker.forget(id); assertFalse(tracker.chatFontReady(id))
    }
    @Test fun `chat hash configuration never enables HUD`() {
        assertTrue(HudAppearanceEnvironment.from(mapOf("CHAT_FONT_PACK_SHA1S" to "a".repeat(40))).isEmpty())
    }
}
