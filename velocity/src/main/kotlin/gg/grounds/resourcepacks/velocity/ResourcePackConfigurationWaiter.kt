package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class ResourcePackConfigurationWaiter {
    private val monitor = Any()
    private val pending = HashMap<UUID, PendingConfiguration>()

    fun begin(playerId: UUID): CompletableFuture<Void> {
        val completion = CompletableFuture<Void>()
        val previous = begin(playerId, completion)
        previous?.complete(null)
        return completion
    }

    fun begin(playerId: UUID, completion: CompletableFuture<Void>): CompletableFuture<Void>? =
        synchronized(monitor) {
            pending.put(playerId, PendingConfiguration(completion))?.completion
        }

    fun expect(playerId: UUID, packIds: Set<UUID>): () -> Unit {
        val attempt =
            synchronized(monitor) {
                pending[playerId]?.let { configuration ->
                    val added = packIds - configuration.remaining
                    configuration.remaining.addAll(added)
                    configuration to added
                }
            } ?: return {}
        return {
            synchronized(monitor) {
                if (pending[playerId] === attempt.first)
                    attempt.first.remaining.removeAll(attempt.second)
            }
        }
    }

    fun seal(playerId: UUID) {
        val completion =
            synchronized(monitor) {
                pending[playerId]?.let { configuration ->
                    configuration.sealed = true
                    completeIfResolved(playerId, configuration)
                }
            }
        completion?.complete(null)
    }

    fun onStatus(
        playerId: UUID,
        packId: UUID?,
        status: PlayerResourcePackStatusEvent.Status,
        expectedCompletion: CompletableFuture<Void>? = null,
    ) {
        if (packId == null || status.isIntermediate) return
        val completion =
            synchronized(monitor) {
                pending[playerId]
                    ?.takeIf { expectedCompletion == null || it.completion === expectedCompletion }
                    ?.let { configuration ->
                        configuration.remaining.remove(packId)
                        completeIfResolved(playerId, configuration)
                    }
            }
        completion?.complete(null)
    }

    fun forget(playerId: UUID) {
        synchronized(monitor) { pending.remove(playerId)?.completion }?.complete(null)
    }

    fun seal(playerId: UUID, completion: CompletableFuture<Void>) {
        val resolved =
            synchronized(monitor) {
                if (pending[playerId]?.completion !== completion) null
                else {
                    pending[playerId]!!.sealed = true
                    completeIfResolved(playerId, pending[playerId]!!)
                }
            }
        resolved?.complete(null)
    }

    fun forget(playerId: UUID, completion: CompletableFuture<Void>) {
        synchronized(monitor) {
                if (pending[playerId]?.completion === completion)
                    pending.remove(playerId)?.completion
                else null
            }
            ?.complete(null)
    }

    fun isPending(playerId: UUID, completion: CompletableFuture<Void>): Boolean =
        synchronized(monitor) { pending[playerId]?.completion === completion }

    fun clear() {
        val completions =
            synchronized(monitor) { pending.values.map { it.completion }.also { pending.clear() } }
        completions.forEach { it.complete(null) }
    }

    private fun completeIfResolved(
        playerId: UUID,
        configuration: PendingConfiguration,
    ): CompletableFuture<Void>? {
        if (!configuration.sealed || configuration.remaining.isNotEmpty()) return null
        pending.remove(playerId, configuration)
        return configuration.completion
    }

    private class PendingConfiguration(val completion: CompletableFuture<Void>) {
        val remaining = linkedSetOf<UUID>()
        var sealed = false
    }
}
