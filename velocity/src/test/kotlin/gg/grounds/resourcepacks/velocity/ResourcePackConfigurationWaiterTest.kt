package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourcePackConfigurationWaiterTest {
    private val playerId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val firstPack = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val secondPack = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `waits for every requested pack to reach a terminal status`() {
        val waiter = ResourcePackConfigurationWaiter()
        val completion = waiter.begin(playerId)
        waiter.expect(playerId, setOf(firstPack, secondPack))
        waiter.seal(playerId)

        waiter.onStatus(playerId, firstPack, PlayerResourcePackStatusEvent.Status.ACCEPTED)
        waiter.onStatus(playerId, firstPack, PlayerResourcePackStatusEvent.Status.DOWNLOADED)
        waiter.onStatus(playerId, firstPack, PlayerResourcePackStatusEvent.Status.SUCCESSFUL)
        assertFalse(completion.isDone)

        waiter.onStatus(playerId, secondPack, PlayerResourcePackStatusEvent.Status.DECLINED)
        assertTrue(completion.isDone)
    }

    @Test
    fun `completes immediately when login sends no resourcepacks`() {
        val waiter = ResourcePackConfigurationWaiter()
        val completion = waiter.begin(playerId)

        waiter.seal(playerId)

        assertTrue(completion.isDone)
    }

    @Test
    fun `disconnect and shutdown release pending configuration events`() {
        val waiter = ResourcePackConfigurationWaiter()
        val disconnected = waiter.begin(playerId)
        waiter.expect(playerId, setOf(firstPack))
        waiter.seal(playerId)

        waiter.forget(playerId)
        assertTrue(disconnected.isDone)

        val shutdown = waiter.begin(playerId)
        waiter.expect(playerId, setOf(secondPack))
        waiter.seal(playerId)
        waiter.clear()
        assertTrue(shutdown.isDone)
    }
}
