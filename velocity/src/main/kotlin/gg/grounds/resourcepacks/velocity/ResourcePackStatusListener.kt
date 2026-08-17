package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent

internal class ResourcePackStatusListener(
    private val metrics: ResourcePackMetrics,
    private val targetId: () -> String?,
    private val log: ResourcePackLog,
) {
    @Subscribe
    fun onStatus(event: PlayerResourcePackStatusEvent) {
        metrics.record(event.status)
        log.info(
            "Resource-pack status (playerId=${event.player.uniqueId}, packId=${event.packId}, " +
                "targetId=${targetId() ?: "unknown"}, status=${event.status})"
        )
    }
}
