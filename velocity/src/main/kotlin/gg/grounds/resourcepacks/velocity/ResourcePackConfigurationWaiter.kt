package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class ResourcePackConfigurationWaiter {
    private val monitor = Any()
    private val pending = HashMap<UUID, PendingConfiguration>()

    fun begin(playerId: UUID): CompletableFuture<Void> =
        synchronized(monitor) {
            pending.remove(playerId)?.completion?.complete(null)
            CompletableFuture<Void>().also { completion ->
                pending[playerId] = PendingConfiguration(completion)
            }
        }

    fun expect(playerId: UUID, packIds: Set<UUID>) {
        synchronized(monitor) { pending[playerId]?.remaining?.addAll(packIds) }
    }

    fun seal(playerId: UUID) {
        synchronized(monitor) {
            pending[playerId]?.let { configuration ->
                configuration.sealed = true
                completeIfResolved(playerId, configuration)
            }
        }
    }

    fun onStatus(playerId: UUID, packId: UUID?, status: PlayerResourcePackStatusEvent.Status) {
        if (packId == null || status.isIntermediate) return
        synchronized(monitor) {
            pending[playerId]?.let { configuration ->
                configuration.remaining.remove(packId)
                completeIfResolved(playerId, configuration)
            }
        }
    }

    fun forget(playerId: UUID) {
        synchronized(monitor) { pending.remove(playerId)?.completion?.complete(null) }
    }

    fun clear() {
        synchronized(monitor) {
            pending.values.forEach { it.completion.complete(null) }
            pending.clear()
        }
    }

    private fun completeIfResolved(playerId: UUID, configuration: PendingConfiguration) {
        if (!configuration.sealed || configuration.remaining.isNotEmpty()) return
        pending.remove(playerId, configuration)
        configuration.completion.complete(null)
    }

    private class PendingConfiguration(val completion: CompletableFuture<Void>) {
        val remaining = linkedSetOf<UUID>()
        var sealed = false
    }
}
