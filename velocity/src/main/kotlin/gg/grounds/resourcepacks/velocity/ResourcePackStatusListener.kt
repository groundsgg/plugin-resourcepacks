package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import java.util.UUID

internal class ResourcePackStatusListener(
    private val metrics: ResourcePackMetrics,
    private val ownsPack: (UUID, UUID?) -> Boolean,
    private val targetId: (UUID, UUID?) -> String?,
    private val log: ResourcePackLog,
) {
    @Subscribe
    fun onStatus(event: PlayerResourcePackStatusEvent) {
        if (!ownsPack(event.player.uniqueId, event.packId)) return
        metrics.record(event.status)
        log.info(
            "Resource-pack status (playerId=${event.player.uniqueId}, packId=${event.packId}, " +
                "targetId=${targetId(event.player.uniqueId, event.packId) ?: "unknown"}, " +
                "status=${event.status})"
        )
    }
}
