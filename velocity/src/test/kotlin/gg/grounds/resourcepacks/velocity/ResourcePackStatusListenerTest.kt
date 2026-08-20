package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResourcePackStatusListenerTest {
    // Break caught: terminal failures and invalid URLs can disappear from runtime diagnostics.
    @Test
    fun `status listener counts all diagnostic categories and logs safe identifiers`() {
        val metrics = ResourcePackMetrics()
        val log = FakeResourcePackLog()
        val listener =
            ResourcePackStatusListener(metrics, { _, _ -> true }, { _, _ -> "v1.2.3" }, log)
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val packId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        metrics.requested()
        listOf(
                PlayerResourcePackStatusEvent.Status.ACCEPTED,
                PlayerResourcePackStatusEvent.Status.DOWNLOADED,
                PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
                PlayerResourcePackStatusEvent.Status.INVALID_URL,
                PlayerResourcePackStatusEvent.Status.DECLINED,
                PlayerResourcePackStatusEvent.Status.SUCCESSFUL,
            )
            .forEach { status ->
                listener.onStatus(PlayerResourcePackStatusEvent(player, packId, status, null))
            }

        val counts = metrics.snapshot()
        assertEquals(1, counts.requested)
        assertEquals(1, counts.accepted)
        assertEquals(1, counts.downloaded)
        assertEquals(2, counts.failed)
        assertEquals(1, counts.declined)
        assertEquals(1, counts.invalidUrl)
        assertEquals(6, log.messages.size)
        assertEquals(true, log.messages.all { it.contains(player.uniqueId.toString()) })
        assertEquals(true, log.messages.all { it.contains(packId.toString()) })
        assertEquals(true, log.messages.all { it.contains("v1.2.3") })
    }

    @Test
    fun `status listener ignores packs not sent by this plugin`() {
        val metrics = ResourcePackMetrics()
        val log = FakeResourcePackLog()
        val listener =
            ResourcePackStatusListener(
                metrics,
                { _, _ -> false },
                { _, _ -> error("foreign packs must not be attributed") },
                log,
            )
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val packId = UUID.fromString("22222222-2222-2222-2222-222222222222")

        listener.onStatus(
            PlayerResourcePackStatusEvent(
                player,
                packId,
                PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
                null,
            )
        )

        assertEquals(ResourcePackMetricSnapshot(0, 0, 0, 0, 0, 0), metrics.snapshot())
        assertEquals(emptyList(), log.messages)
    }

    @Test
    fun `status listener retains ambiguous attribution for a pack sent by this plugin`() {
        val metrics = ResourcePackMetrics()
        val log = FakeResourcePackLog()
        val listener = ResourcePackStatusListener(metrics, { _, _ -> true }, { _, _ -> null }, log)
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val packId = UUID.fromString("33333333-3333-3333-3333-333333333333")

        listener.onStatus(
            PlayerResourcePackStatusEvent(
                player,
                packId,
                PlayerResourcePackStatusEvent.Status.DOWNLOADED,
                null,
            )
        )

        assertEquals(1, metrics.snapshot().downloaded)
        assertEquals(true, log.messages.single().contains("targetId=unknown"))
    }

    // Break caught: regex redaction can miss credentials and arbitrary upstream response bodies in
    // formats the plugin did not anticipate.
    @Test
    fun `diagnostic reasons fail closed for every untrusted format`() {
        val hostile =
            listOf(
                "R2_SECRET_ACCESS_KEY=r2-secret",
                "{\"R2_SECRET_ACCESS_KEY\":\"json-secret\",\"body\":\"private\"}",
                "Authorization: Bearer bearer-secret",
                "Authorization: Basic dXNlcjpwYXNz",
                "?token=query-secret&access_key_id=query-key",
                "X-Upstream-Body: database dump and customer text",
                "request failed\n\n<html>private response body</html>",
            )

        assertEquals(
            List(hostile.size) { "unknown_failure" },
            hostile.map(::normalizeDiagnosticReason),
        )
    }

    // Break caught: fail-closed normalization can erase useful client/config failure categories.
    @Test
    fun `diagnostic reasons retain only explicitly controlled categories`() {
        assertEquals("channel_request_failed", normalizeDiagnosticReason("Channel request failed."))
        assertEquals(
            "bootstrap_failed_no_cached_snapshot",
            normalizeDiagnosticReason("bootstrap_failed_no_cached_snapshot"),
        )
        assertNull(normalizeDiagnosticReason(null))
    }
}
