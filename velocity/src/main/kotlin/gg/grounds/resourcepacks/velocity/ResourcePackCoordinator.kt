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

internal fun interface ResourcePackDeliveryObserver {
    fun sent(player: Player, prepared: PreparedPackRequest)
}

internal class ResourcePackCoordinator(
    private val settings: () -> ResourcePackSettings?,
    private val clientState: () -> PackSetClientState,
    private val players: OnlinePlayerView,
    private val sender: PackSender,
    private val requestFactory: VelocityPackRequestFactory,
    private val deliveryObserver: ResourcePackDeliveryObserver =
        ResourcePackDeliveryObserver { _, _ ->
        },
) {
    private val delivery = Any()
    private val sent = ConcurrentHashMap<UUID, String>()
    private val targetAttributions = LinkedHashMap<PlayerPack, TargetAttribution>(16, 0.75f, true)
    private val pendingTargetAttributions = HashMap<PlayerPack, TargetAttribution>()
    private var currentSettings: ResourcePackSettings? = null
    private var closed = false

    fun onLogin(player: Player) =
        synchronized(delivery) { dispatchLocked(player, clientState(), isolateSendFailure = false) }

    fun onSnapshot(state: PackSetClientState) {
        synchronized(delivery) {
            if (!closed)
                players.players().forEach { player ->
                    dispatchLocked(player, state, isolateSendFailure = true)
                }
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
            if (old?.enabled == true && !settings.enabled) targetAttributions.clear()
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
        synchronized(delivery) {
            sent.remove(playerId)
            targetAttributions.keys.removeIf { it.playerId == playerId }
            pendingTargetAttributions.keys.removeIf { it.playerId == playerId }
        }
    }

    internal fun targetId(playerId: UUID, packId: UUID?): String? =
        synchronized(delivery) {
            packId
                ?.let {
                    val key = PlayerPack(playerId, it)
                    pendingTargetAttributions[key] ?: targetAttributions[key]
                }
                ?.let { it as? TargetAttribution.Exact }
                ?.targetId
        }

    internal fun ownsPack(playerId: UUID, packId: UUID?): Boolean =
        synchronized(delivery) {
            if (packId == null) false
            else {
                val key = PlayerPack(playerId, packId)
                pendingTargetAttributions.containsKey(key) || targetAttributions.containsKey(key)
            }
        }

    internal fun clear() =
        synchronized(delivery) {
            closed = true
            currentSettings = null
            sent.clear()
            targetAttributions.clear()
            pendingTargetAttributions.clear()
        }

    private fun dispatchLocked(
        player: Player,
        state: PackSetClientState,
        isolateSendFailure: Boolean,
    ) {
        if (closed) return
        val configured = settings() ?: return
        currentSettings = configured
        val prepared = requestFactory.prepare(configured, state) ?: return
        sent.compute(player.uniqueId) { _, existing ->
            if (existing == prepared.fingerprint) existing
            else {
                val provisionalAttributions =
                    prepared.packIds.associate { packId ->
                        val key = PlayerPack(player.uniqueId, packId)
                        val previous =
                            targetAttributions.entries.firstOrNull { it.key == key }?.value
                        key to
                            when (previous) {
                                null -> TargetAttribution.Exact(prepared.targetId)
                                is TargetAttribution.Exact ->
                                    if (previous.targetId == prepared.targetId) previous
                                    else TargetAttribution.Ambiguous
                                TargetAttribution.Ambiguous -> TargetAttribution.Ambiguous
                            }
                    }
                pendingTargetAttributions.putAll(provisionalAttributions)
                try {
                    sender.send(player, prepared.request)
                } catch (failure: Exception) {
                    if (isolateSendFailure) return@compute existing
                    throw failure
                } finally {
                    provisionalAttributions.keys.forEach(pendingTargetAttributions::remove)
                }
                targetAttributions.putAll(provisionalAttributions)
                try {
                    deliveryObserver.sent(player, prepared)
                } catch (_: Exception) {
                    // Diagnostics must not change the delivery result.
                }
                while (targetAttributions.size > MAX_STATUS_ATTRIBUTIONS) {
                    targetAttributions.entries.iterator().run {
                        next()
                        remove()
                    }
                }
                prepared.fingerprint
            }
        }
    }

    private data class PlayerPack(val playerId: UUID, val packId: UUID)

    private sealed interface TargetAttribution {
        data class Exact(val targetId: String) : TargetAttribution

        data object Ambiguous : TargetAttribution
    }

    private companion object {
        const val MAX_STATUS_ATTRIBUTIONS = 4_096
    }
}
