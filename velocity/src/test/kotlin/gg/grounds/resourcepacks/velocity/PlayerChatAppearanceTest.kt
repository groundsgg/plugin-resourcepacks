package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.network.ProtocolVersion
import gg.grounds.proxy.api.PlayerChatAppearance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerChatAppearanceTest {
    private val playerId = UUID.fromString("12345678-1234-4321-8765-123456789abc")
    private val oldPack = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val newPack = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val oldHash = "1".repeat(40)
    private val allowedHash = "2".repeat(40)

    @Test
    fun `font readiness requires matching successful load`() {
        val tracker = PlayerChatAppearanceTracker(setOf(allowedHash))

        tracker.offered(playerId, mapOf(newPack to allowedHash))
        assertFalse(tracker.chatFontReady(playerId))

        tracker.loaded(playerId, newPack)
        assertTrue(tracker.chatFontReady(playerId))
    }

    @Test
    fun `only offered platform pack fingerprints are tracked`() {
        val settings = settings()
        val platformPack = resolvedPack(role = "platform", uuid = newPack, sha1 = allowedHash)
        val contentPack = resolvedPack(role = "content", uuid = oldPack, sha1 = oldHash)
        val snapshot = snapshot(settings, packs = listOf(contentPack, platformPack))

        val prepared =
            requireNotNull(
                VelocityPackRequestFactory().prepare(settings, readyState(settings, snapshot))
            )

        assertEquals(mapOf(newPack to allowedHash), prepared.packHashes)
    }

    @Test
    fun `old hash and late prior events cannot enable or clear current readiness`() {
        val tracker = PlayerChatAppearanceTracker(setOf(allowedHash))
        tracker.offered(playerId, mapOf(oldPack to oldHash))
        tracker.loaded(playerId, oldPack)
        assertFalse(tracker.chatFontReady(playerId))

        tracker.offered(playerId, mapOf(newPack to allowedHash))
        tracker.loaded(playerId, newPack)
        tracker.failed(playerId, oldPack)
        assertTrue(tracker.chatFontReady(playerId))
    }

    @Test
    fun `reused UUID with different hashes is ambiguous and fails closed`() {
        val tracker = PlayerChatAppearanceTracker(setOf(allowedHash, oldHash))
        tracker.offered(playerId, mapOf(oldPack to oldHash))
        tracker.offered(playerId, mapOf(oldPack to allowedHash))

        tracker.loaded(playerId, oldPack)

        assertFalse(tracker.chatFontReady(playerId))
    }

    @Test
    fun `disconnect and shutdown clear readiness`() {
        val tracker = PlayerChatAppearanceTracker(setOf(allowedHash))
        tracker.offered(playerId, mapOf(newPack to allowedHash))
        tracker.loaded(playerId, newPack)

        tracker.forget(playerId)
        assertFalse(tracker.chatFontReady(playerId))
        tracker.offered(playerId, mapOf(newPack to allowedHash))
        tracker.loaded(playerId, newPack)
        tracker.clear()
        assertFalse(tracker.chatFontReady(playerId))
    }

    @Test
    fun `style snapshots stay globally ordered across player resets`() {
        val tracker = PlayerChatAppearanceTracker(setOf(allowedHash))
        tracker.offered(playerId, mapOf(newPack to allowedHash))
        tracker.loaded(playerId, newPack)
        val ready = tracker.snapshot(playerId) { true }

        tracker.offered(playerId, mapOf(newPack to allowedHash))
        val unavailable = tracker.snapshot(playerId) { true }

        assertTrue(ready.ready)
        assertFalse(unavailable.ready)
        assertTrue(unavailable.revision > ready.revision)
        val disconnect = tracker.disconnectSnapshot(playerId)
        assertTrue(disconnect.revision > unavailable.revision)
        val nextSession = tracker.snapshot(playerId) { true }
        assertTrue(nextSession.revision > disconnect.revision)
        tracker.reset(playerId)
        assertTrue(tracker.snapshot(playerId) { true }.revision > nextSession.revision)
    }

    @Test
    fun `linked Bedrock UUID and platform lookup failure both stay text only`() {
        val tracker = PlayerChatAppearanceTracker(emptySet())
        val linkedJavaShapedUuid = playerId
        val bedrock =
            ResourcePackPlayerChatAppearanceQuery(
                protocol = { ProtocolVersion.MINECRAFT_1_21_9 },
                platform = ChatPlatformLookup { id -> id == linkedJavaShapedUuid },
                tracker = tracker,
            )
        val failedLookup =
            ResourcePackPlayerChatAppearanceQuery(
                protocol = { ProtocolVersion.MINECRAFT_1_21_9 },
                platform = ChatPlatformLookup { null },
                tracker = tracker,
            )

        assertEquals(PlayerChatAppearance(), bedrock.appearanceOf(playerId))
        assertEquals(PlayerChatAppearance(), failedLookup.appearanceOf(playerId))
    }

    @Test
    fun `reflection failure is remembered and remains unknown`() {
        var attempts = 0
        var reports = 0
        val lookup =
            SafeChatPlatformLookup(
                lookup = {
                    attempts++
                    throw ReflectiveOperationException("unavailable")
                },
                onFailure = { reports++ },
            )

        assertNull(lookup.isBedrock(playerId))
        assertNull(lookup.isBedrock(playerId))
        assertEquals(1, attempts)
        assertEquals(1, reports)
    }

    @Test
    fun `native heads require an online supported Java client`() {
        val tracker = PlayerChatAppearanceTracker(emptySet())
        val protocols = mutableMapOf(playerId to ProtocolVersion.MINECRAFT_1_21_9)
        val query =
            ResourcePackPlayerChatAppearanceQuery(
                protocol = protocols::get,
                platform = ChatPlatformLookup { false },
                tracker = tracker,
            )

        assertEquals(PlayerChatAppearance(javaHeadsSupported = true), query.appearanceOf(playerId))
        protocols[playerId] = ProtocolVersion.MINECRAFT_1_21_7
        assertEquals(PlayerChatAppearance(), query.appearanceOf(playerId))
        protocols.clear()
        assertEquals(PlayerChatAppearance(), query.appearanceOf(playerId))
    }

    @Test
    fun `allowlist accepts only comma separated lowercase SHA1 values`() {
        assertEquals(
            setOf(oldHash, allowedHash),
            ChatAppearanceEnvironment.from(mapOf("CHAT_FONT_PACK_SHA1S" to "$oldHash,$allowedHash"))
                .fontPackSha1s,
        )
        assertEquals(emptySet(), ChatAppearanceEnvironment.from(emptyMap()).fontPackSha1s)
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ChatAppearanceEnvironment.from(mapOf("CHAT_FONT_PACK_SHA1S" to "ABC"))
            }
        assertFalse(requireNotNull(failure.message).contains("ABC"))
    }
}
