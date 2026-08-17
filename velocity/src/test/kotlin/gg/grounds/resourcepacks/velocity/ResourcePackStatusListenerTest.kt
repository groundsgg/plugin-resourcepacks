package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ResourcePackStatusListenerTest {
    // Break caught: terminal failures and invalid URLs can disappear from runtime diagnostics.
    @Test
    fun `status listener counts all diagnostic categories and logs safe identifiers`() {
        val metrics = ResourcePackMetrics()
        val log = FakeResourcePackLog()
        val listener = ResourcePackStatusListener(metrics, { "v1.2.3" }, log)
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

    // Break caught: config-service tokens can leak through state reasons and local diagnostics.
    @Test
    fun `sanitizer redacts credential values from status text`() {
        val sanitized =
            sanitizeLogText(
                "token=abc password: hunter2 authorization: Bearer xyz secret_key=qwerty"
            )

        assertFalse(sanitized.contains("abc"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("xyz"))
        assertFalse(sanitized.contains("qwerty"))
    }
}
