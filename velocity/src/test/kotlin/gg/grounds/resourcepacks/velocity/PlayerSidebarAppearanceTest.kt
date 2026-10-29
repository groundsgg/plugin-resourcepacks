package gg.grounds.resourcepacks.velocity

import java.util.UUID
import kotlin.test.*

class PlayerSidebarAppearanceTest {
    @Test
    fun `sidebar has its own strict allowlist and exact Java protocol eligibility`() {
        val hash = "a".repeat(40)
        assertEquals(
            setOf(hash),
            SidebarAppearanceEnvironment.from(mapOf("SIDEBAR_FONT_PACK_SHA1S" to hash)),
        )
        assertTrue(
            SidebarAppearanceEnvironment.from(mapOf("CHAT_FONT_PACK_SHA1S" to hash)).isEmpty()
        )
        assertFailsWith<IllegalArgumentException> {
            SidebarAppearanceEnvironment.from(mapOf("SIDEBAR_FONT_PACK_SHA1S" to "invalid"))
        }
        assertTrue(SidebarAppearanceEnvironment.eligible(777, false))
        assertFalse(SidebarAppearanceEnvironment.eligible(776, false))
        assertFalse(SidebarAppearanceEnvironment.eligible(777, true))
        assertFalse(SidebarAppearanceEnvironment.eligible(777, null))
    }

    @Test
    fun `old chat pack never confirms sidebar and changed pack identity revokes readiness`() {
        val player = UUID.randomUUID()
        val pack = UUID.randomUUID()
        val tracker = PlayerChatAppearanceTracker(setOf("b".repeat(40)))
        tracker.offered(player, mapOf(pack to "a".repeat(40)))
        tracker.loaded(player, pack)
        assertFalse(tracker.chatFontReady(player))
        val newPack = UUID.randomUUID()
        tracker.offered(player, mapOf(newPack to "b".repeat(40)))
        tracker.loaded(player, newPack)
        assertTrue(tracker.chatFontReady(player))
        tracker.failed(player, newPack)
        assertFalse(tracker.chatFontReady(player))
    }
}
