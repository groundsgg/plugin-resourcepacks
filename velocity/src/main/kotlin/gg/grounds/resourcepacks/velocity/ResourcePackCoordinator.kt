package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.proxy.Player
import gg.grounds.resourcepacks.client.PackSetClientState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface PackSender {
    fun send(player: Player, request: net.kyori.adventure.resource.ResourcePackRequest)
}

internal enum class InitialPackDelivery { SENT, WAITING_FOR_SNAPSHOT, NO_REQUEST }
internal class InitialDeliverySession internal constructor()

internal fun interface ResourcePackDeliveryObserver { fun sent(player: Player, prepared: PreparedPackRequest) }
internal fun interface ResourcePackDeliveryExpectation { fun expect(player: Player, prepared: PreparedPackRequest) }
internal fun interface InitialPackDeliveryCompletion { fun completed(playerId: UUID, session: InitialDeliverySession) }

internal class ResourcePackCoordinator(
    private val settings: () -> ResourcePackSettings?,
    private val clientState: () -> PackSetClientState,
    private val sender: PackSender,
    private val requestFactory: VelocityPackRequestFactory,
    private val deliveryObserver: ResourcePackDeliveryObserver = ResourcePackDeliveryObserver { _, _ -> },
    private val deliveryExpectation: ResourcePackDeliveryExpectation = ResourcePackDeliveryExpectation { _, _ -> },
    private val initialDeliveryCompleted: InitialPackDeliveryCompletion = InitialPackDeliveryCompletion { _, _ -> },
) {
    private val delivery = Any()
    private val sent = ConcurrentHashMap<UUID, String>()
    private val pendingInitial = HashMap<UUID, PendingInitial>()
    private val initiallyCompleted = HashMap<UUID, InitialDeliverySession>()
    private val targetAttributions = LinkedHashMap<PlayerPack, TargetAttribution>(16, 0.75f, true)
    private val pendingTargetAttributions = HashMap<PlayerPack, TargetAttribution>()
    private var currentSettings: ResourcePackSettings? = null
    private var closed = false

    fun newInitialSession() = InitialDeliverySession()
    fun onLogin(player: Player): InitialPackDelivery = onLogin(player, newInitialSession())
    fun onLogin(player: Player, session: InitialDeliverySession): InitialPackDelivery = synchronized(delivery) {
        if (closed) return@synchronized InitialPackDelivery.WAITING_FOR_SNAPSHOT
        val configured = settings()
        currentSettings = configured
        when {
            configured == null -> waitForSnapshotLocked(player, session)
            !configured.enabled -> {
                completeInitialLocked(player.uniqueId, session, notify = false)
                InitialPackDelivery.NO_REQUEST
            }
            else -> requestFactory.prepare(configured, clientState())?.let { prepared ->
                dispatchLocked(player, prepared, isolateSendFailure = false)
                completeInitialLocked(player.uniqueId, session, notify = false)
                InitialPackDelivery.SENT
            } ?: waitForSnapshotLocked(player, session)
        }
    }

    fun onServerSwitch(player: Player) = synchronized(delivery) {
        onServerSwitchLocked(player, initiallyCompleted[player.uniqueId])
    }
    fun onServerSwitch(player: Player, session: InitialDeliverySession) = synchronized(delivery) {
        onServerSwitchLocked(player, session)
    }
    private fun onServerSwitchLocked(player: Player, session: InitialDeliverySession?) {
        if (closed || session == null || initiallyCompleted[player.uniqueId] !== session) return
        val configured = settings() ?: return
        currentSettings = configured
        requestFactory.prepare(configured, clientState())?.let { dispatchLocked(player, it, isolateSendFailure = true) }
    }

    fun onSnapshot(state: PackSetClientState) = synchronized(delivery) {
        if (closed) return@synchronized
        val configured = settings() ?: return@synchronized
        currentSettings = configured
        pendingInitial.values.toList().forEach { pending ->
            val player = pending.player
            if (pendingInitial[player.uniqueId] !== pending) return@forEach
            if (!configured.enabled) completeInitialLocked(player.uniqueId, pending.session, notify = true)
            else requestFactory.prepare(configured, state)?.let { prepared ->
                if (dispatchLocked(player, prepared, isolateSendFailure = true))
                    completeInitialLocked(player.uniqueId, pending.session, notify = true)
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

    fun forget(playerId: UUID) = synchronized(delivery) {
        pendingInitial.remove(playerId)
        initiallyCompleted.remove(playerId)
        sent.remove(playerId)
        targetAttributions.keys.removeIf { it.playerId == playerId }
        pendingTargetAttributions.keys.removeIf { it.playerId == playerId }
    }
    fun cancelInitial(playerId: UUID, session: InitialDeliverySession): Boolean = synchronized(delivery) {
        val pending = pendingInitial[playerId] ?: return@synchronized false
        if (pending.session !== session) return@synchronized false
        pendingInitial.remove(playerId)
        initiallyCompleted.remove(playerId, session)
        true
    }

    internal fun targetId(playerId: UUID, packId: UUID?): String? = synchronized(delivery) {
        packId?.let { pendingTargetAttributions[PlayerPack(playerId, it)] ?: targetAttributions[PlayerPack(playerId, it)] }
            ?.let { it as? TargetAttribution.Exact }?.targetId
    }

    internal fun ownsPack(playerId: UUID, packId: UUID?): Boolean = synchronized(delivery) {
        packId != null && (pendingTargetAttributions.containsKey(PlayerPack(playerId, packId)) ||
            targetAttributions.containsKey(PlayerPack(playerId, packId)))
    }

    internal fun clear() = synchronized(delivery) {
        closed = true
        currentSettings = null
        pendingInitial.clear()
        initiallyCompleted.clear()
        sent.clear()
        targetAttributions.clear()
        pendingTargetAttributions.clear()
    }

    private fun waitForSnapshotLocked(player: Player, session: InitialDeliverySession): InitialPackDelivery {
        pendingInitial[player.uniqueId] = PendingInitial(player, session)
        initiallyCompleted.remove(player.uniqueId)
        return InitialPackDelivery.WAITING_FOR_SNAPSHOT
    }

    private fun completeInitialLocked(playerId: UUID, session: InitialDeliverySession, notify: Boolean) {
        pendingInitial.remove(playerId)
        initiallyCompleted[playerId] = session
        if (notify) initialDeliveryCompleted.completed(playerId, session)
    }

    private fun dispatchLocked(player: Player, prepared: PreparedPackRequest, isolateSendFailure: Boolean): Boolean {
        if (sent[player.uniqueId] == prepared.fingerprint) return true
        val provisionalAttributions = prepared.packIds.associate { packId ->
            val key = PlayerPack(player.uniqueId, packId)
            key to when (val previous = targetAttributions[key]) {
                null -> TargetAttribution.Exact(prepared.targetId)
                is TargetAttribution.Exact -> if (previous.targetId == prepared.targetId) previous else TargetAttribution.Ambiguous
                TargetAttribution.Ambiguous -> TargetAttribution.Ambiguous
            }
        }
        pendingTargetAttributions.putAll(provisionalAttributions)
        try {
            deliveryExpectation.expect(player, prepared)
            sender.send(player, prepared.request)
        } catch (failure: Exception) {
            if (isolateSendFailure) return false
            throw failure
        } finally {
            provisionalAttributions.keys.forEach(pendingTargetAttributions::remove)
        }
        targetAttributions.putAll(provisionalAttributions)
        try { deliveryObserver.sent(player, prepared) } catch (_: Exception) { }
        while (targetAttributions.size > MAX_STATUS_ATTRIBUTIONS) targetAttributions.entries.iterator().run { next(); remove() }
        sent[player.uniqueId] = prepared.fingerprint
        return true
    }

    private data class PlayerPack(val playerId: UUID, val packId: UUID)
    private data class PendingInitial(val player: Player, val session: InitialDeliverySession)
    private sealed interface TargetAttribution {
        data class Exact(val targetId: String) : TargetAttribution
        data object Ambiguous : TargetAttribution
    }
    private companion object { const val MAX_STATUS_ATTRIBUTIONS = 4_096 }
}
