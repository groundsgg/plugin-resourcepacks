package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.proxy.Player
import gg.grounds.resourcepacks.client.PackSetClientState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface OnlinePlayerView {
    fun players(): Collection<Player>
}

fun interface PackSender {
    fun send(player: Player, request: net.kyori.adventure.resource.ResourcePackRequest)
}

class ResourcePackCoordinator(
    private val settings: () -> ResourcePackSettings?,
    private val clientState: () -> PackSetClientState,
    private val players: OnlinePlayerView,
    private val sender: PackSender,
    private val requestFactory: VelocityPackRequestFactory,
) {
    private val delivery = Any()
    private val sent = ConcurrentHashMap<UUID, String>()
    private var currentSettings: ResourcePackSettings? = null
    private var closed = false

    fun onLogin(player: Player) = synchronized(delivery) { dispatchLocked(player, clientState()) }

    fun onSnapshot(state: PackSetClientState) {
        synchronized(delivery) {
            if (!closed) players.players().forEach { dispatchLocked(it, state) }
        }
    }

    fun onSettingsChanged(settings: ResourcePackSettings) = reconcileSettings(settings) { true }

    internal fun reconcileSettings(settings: ResourcePackSettings, mutation: () -> Boolean) {
        var applied = false
        synchronized(delivery) {
            if (closed) return@synchronized
            val old = currentSettings
            currentSettings = settings
            if (
                old == null ||
                    old.required != settings.required ||
                    old.prompt != settings.prompt ||
                    old.enabled != settings.enabled ||
                    old.source != settings.source
            )
                sent.clear()
            try {
                applied = mutation()
                if (!applied) currentSettings = old
            } catch (failure: Throwable) {
                currentSettings = old
                throw failure
            }
        }
        if (applied) onSnapshot(clientState())
    }

    fun forget(playerId: UUID) {
        synchronized(delivery) { sent.remove(playerId) }
    }

    internal fun clear() =
        synchronized(delivery) {
            closed = true
            currentSettings = null
            sent.clear()
        }

    private fun dispatchLocked(player: Player, state: PackSetClientState) {
        if (closed) return
        val configured = settings() ?: return
        currentSettings = configured
        val prepared = requestFactory.prepare(configured, state) ?: return
        sent.compute(player.uniqueId) { _, existing ->
            if (existing == prepared.fingerprint) existing
            else {
                sender.send(player, prepared.request)
                prepared.fingerprint
            }
        }
    }
}
