package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourcePackConfigurationWaiterTest {
    private val playerId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val firstPack = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val secondPack = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `status captured for predecessor future cannot resolve replacement`() {
        val waiter = ResourcePackConfigurationWaiter()
        val previous = waiter.begin(playerId)
        val replacement = waiter.begin(playerId)
        waiter.expect(playerId, setOf(firstPack))
        waiter.seal(playerId)
        waiter.onStatus(
            playerId,
            firstPack,
            PlayerResourcePackStatusEvent.Status.SUCCESSFUL,
            previous,
        )
        assertFalse(replacement.isDone)
        waiter.onStatus(
            playerId,
            firstPack,
            PlayerResourcePackStatusEvent.Status.SUCCESSFUL,
            replacement,
        )
        assertTrue(replacement.isDone)
    }

    @Test
    fun `provided future registration defers predecessor completion to caller`() {
        val waiter = ResourcePackConfigurationWaiter()
        val old = waiter.begin(playerId)
        val replacement = CompletableFuture<Void>()
        val previous = waiter.begin(playerId, replacement)

        assertTrue(previous === old)
        assertFalse(old.isDone)
        assertTrue(waiter.isPending(playerId, replacement))
        previous.complete(null)
        assertTrue(old.isDone)
        assertFalse(replacement.isDone)
    }

    @Test
    fun `attempt rollback removes only newly added ids`() {
        val waiter = ResourcePackConfigurationWaiter()
        val completion = waiter.begin(playerId)
        waiter.expect(playerId, setOf(firstPack))
        val rollback = waiter.expect(playerId, setOf(firstPack, secondPack))
        rollback()
        waiter.seal(playerId)
        waiter.onStatus(playerId, secondPack, PlayerResourcePackStatusEvent.Status.SUCCESSFUL)
        assertFalse(completion.isDone)
        waiter.onStatus(playerId, firstPack, PlayerResourcePackStatusEvent.Status.SUCCESSFUL)
        assertTrue(completion.isDone)
    }

    @Test
    fun `old attempt rollback cannot remove replacement expectations`() {
        val waiter = ResourcePackConfigurationWaiter()
        waiter.begin(playerId)
        val rollback = waiter.expect(playerId, setOf(firstPack))
        val replacement = waiter.begin(playerId)
        waiter.expect(playerId, setOf(firstPack))
        rollback()
        waiter.seal(playerId)
        assertFalse(replacement.isDone)
        waiter.onStatus(playerId, firstPack, PlayerResourcePackStatusEvent.Status.SUCCESSFUL)
        assertTrue(replacement.isDone)
    }

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
