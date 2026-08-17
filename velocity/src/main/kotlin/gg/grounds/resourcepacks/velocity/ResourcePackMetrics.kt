package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import java.util.concurrent.atomic.LongAdder

class ResourcePackMetrics {
    private val requested = LongAdder()
    private val accepted = LongAdder()
    private val downloaded = LongAdder()
    private val failed = LongAdder()
    private val declined = LongAdder()
    private val invalidUrl = LongAdder()

    fun requested() = requested.increment()

    fun record(status: PlayerResourcePackStatusEvent.Status) =
        when (status) {
            PlayerResourcePackStatusEvent.Status.ACCEPTED -> accepted.increment()
            PlayerResourcePackStatusEvent.Status.DOWNLOADED -> downloaded.increment()
            PlayerResourcePackStatusEvent.Status.DECLINED -> declined.increment()
            PlayerResourcePackStatusEvent.Status.INVALID_URL -> {
                invalidUrl.increment()
                failed.increment()
            }
            PlayerResourcePackStatusEvent.Status.SUCCESSFUL -> Unit
            else -> failed.increment()
        }

    internal fun snapshot() =
        ResourcePackMetricSnapshot(
            requested.sum(),
            accepted.sum(),
            downloaded.sum(),
            failed.sum(),
            declined.sum(),
            invalidUrl.sum(),
        )
}

internal data class ResourcePackMetricSnapshot(
    val requested: Long,
    val accepted: Long,
    val downloaded: Long,
    val failed: Long,
    val declined: Long,
    val invalidUrl: Long,
)
