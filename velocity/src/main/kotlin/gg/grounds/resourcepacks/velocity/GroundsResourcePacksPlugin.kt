package gg.grounds.resourcepacks.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.player.configuration.PlayerConfigurationEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import gg.grounds.config.ConfigStartupMode
import gg.grounds.generated.BuildInfo
import gg.grounds.resourcepacks.client.PackSetClientState
import gg.grounds.resourcepacks.client.PackSetClientStatus
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.kyori.adventure.text.Component
import org.slf4j.Logger

@Plugin(
    id = "plugin-resourcepacks",
    name = "Grounds ResourcePacks Plugin",
    version = BuildInfo.VERSION,
    description = "Delivers ordered resource-pack requests to players on login",
    authors = ["Grounds Development Team and contributors"],
    url = "https://github.com/groundsgg/plugin-resourcepacks",
    dependencies = [Dependency(id = "plugin-config")],
)
class GroundsResourcePacksPlugin
internal constructor(
    private val dataDirectory: Path,
    private val environment: () -> Map<String, String>,
    private val configGateway: ResourcePackConfigGateway,
    private val clientFactory: ResourcePackClientFactory,
    sender: PackSender,
    private val eventRegistry: ResourcePackEventRegistry,
    private val log: ResourcePackLog,
    private val snapshotDeadline: ResourcePackSnapshotDeadline =
        ScheduledResourcePackSnapshotDeadline(),
) {
    @Inject
    constructor(
        proxy: ProxyServer,
        @DataDirectory dataDirectory: Path,
        logger: Logger,
    ) : this(
        dataDirectory,
        { System.getenv() },
        VelocityResourcePackConfigGateway(proxy),
        DefaultResourcePackClientFactory,
        VelocityPlayerPackSender,
        VelocityResourcePackEventRegistry(proxy),
        Slf4jResourcePackLog(logger),
    )

    private val lifecycle = Any()
    private val initialized = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val metrics = ResourcePackMetrics()
    private val configured = AtomicReference<ResourcePackSettings?>(null)
    private val state = AtomicReference(closedState())
    private val lastLoggedStatus = AtomicReference<PackSetClientStatus?>(null)
    private val configurationWaiter = ResourcePackConfigurationWaiter()
    private val deadlines = Any()
    private val initialDeadlines = HashMap<java.util.UUID, InitialDeadline>()
    private val coordinator =
        ResourcePackCoordinator(
            settings = configured::get,
            clientState = state::get,
            sender =
                PackSender { player, request ->
                    sender.send(player, request)
                    metrics.requested()
                },
            requestFactory = VelocityPackRequestFactory(),
            deliveryObserver =
                ResourcePackDeliveryObserver { player, prepared ->
                    log.info(
                        "Resource-pack request sent (playerId=${player.uniqueId}, " +
                            "targetId=${prepared.targetId}, fingerprint=${prepared.fingerprint}, " +
                            "packCount=${prepared.packIds.size})"
                    )
                },
            deliveryExpectation =
                ResourcePackDeliveryExpectation { player, prepared ->
                    configurationWaiter.expect(player.uniqueId, prepared.packIds)
                },
            initialDeliveryCompleted = InitialPackDeliveryCompletion(::completeInitialConfiguration),
        )

    private var client: ResourcePackClient? = null
    private var clientListener: AutoCloseable? = null
    private var configListener: AutoCloseable? = null
    private var statusListener: ResourcePackStatusListener? = null

    @Subscribe
    fun onInitialize(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        if (!initialized.compareAndSet(false, true)) return
        val deployment = environment()
        val definition = resourcePackSettingsDefinition(bootstrapPackSetChannel(deployment))
        val deploymentEnvironment = ResourcePackEnvironment.from(deployment).deploymentEnvironment
        val listener =
            ResourcePackStatusListener(
                metrics,
                coordinator::ownsPack,
                coordinator::targetId,
                log,
                configurationWaiter::onStatus,
            )
        statusListener = listener
        eventRegistry.register(this, listener)

        val result =
            configGateway.register(
                definition = definition,
                app = "network",
                environment = deploymentEnvironment,
                mode = ConfigStartupMode.DEGRADED,
            )
        val subscription = configGateway.onChange(definition, ::applySettings)
        configListener = subscription
        if (!result.isUsable()) {
            log.warn(
                "Resource-pack configuration not ready (status=${result.status}, " +
                    "reason=${normalizeDiagnosticReason(result.reason)})"
            )
        }
        subscription.deliverLatestIfAvailable()
    }

    @Subscribe
    fun onPlayerConfiguration(event: PlayerConfigurationEvent): EventTask? {
        if (stopped.get()) return null
        val playerId = event.player().uniqueId
        val completion = configurationWaiter.begin(playerId)
        val session = coordinator.newInitialSession()
        val initial = InitialDeadline(event.player(), completion, session)
        val replaced = synchronized(deadlines) { initialDeadlines.put(playerId, initial) }
        replaced?.handle?.close()
        try {
            when (coordinator.onLogin(event.player(), session)) {
                InitialPackDelivery.SENT,
                InitialPackDelivery.NO_REQUEST -> configurationWaiter.seal(playerId, completion)
                InitialPackDelivery.WAITING_FOR_SNAPSHOT -> scheduleSnapshotDeadline(initial)
            }
        } catch (failure: Throwable) {
            forgetInitial(initial)
            throw failure
        }
        return if (completion.isDone) null else EventTask.resumeWhenComplete(completion)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val initial =
            synchronized(deadlines) {
                initialDeadlines[event.player.uniqueId]?.takeIf { it.player === event.player }
            } ?: return
        forgetInitial(initial)
    }

    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        val initial = synchronized(deadlines) { initialDeadlines[event.player.uniqueId] }
        if (
            !stopped.get() &&
                event.previousServer != null &&
                initial != null &&
                initial.completion.isDone
        )
            coordinator.onServerSwitch(event.player, initial.session)
    }

    @Subscribe
    fun onShutdown(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
        if (!stopped.compareAndSet(false, true)) return
        var configToClose: AutoCloseable? = null
        var clientListenerToClose: AutoCloseable? = null
        var statusToUnregister: ResourcePackStatusListener? = null
        var clientToClose: ResourcePackClient? = null
        snapshotDeadline.close()
        synchronized(deadlines) {
            initialDeadlines.values.forEach { it.handle?.close() }
            initialDeadlines.clear()
        }
        coordinator.clear()
        configurationWaiter.clear()
        synchronized(lifecycle) {
            configToClose = configListener
            configListener = null
            clientListenerToClose = clientListener
            clientListener = null
            statusToUnregister = statusListener
            statusListener = null
            clientToClose = client
            client = null
            configured.set(null)
        }
        configToClose?.close()
        clientListenerToClose?.close()
        statusToUnregister?.let { eventRegistry.unregister(this, it) }
        clientToClose?.close()
        state.set(closedState())
    }

    fun runtimeStatus(): ResourcePackRuntimeStatus {
        val value = state.get()
        val counts = metrics.snapshot()
        return ResourcePackRuntimeStatus(
            value.status,
            value.current?.fingerprint,
            value.degradedFallback?.fingerprint,
            normalizeDiagnosticReason(value.lastError),
            counts.requested,
            counts.accepted,
            counts.downloaded,
            counts.failed,
            counts.declined,
        )
    }

    private fun applySettings(next: ResourcePackSettings) {
        if (stopped.get()) return
        val nextSource =
            try {
                next.toClientSource()
            } catch (_: IllegalArgumentException) {
                log.warn("Resource-pack settings rejected (reason=invalid_settings)")
                return
            } catch (_: URISyntaxException) {
                log.warn("Resource-pack settings rejected (reason=invalid_settings)")
                return
            }

        log.info(
            "Resource-pack settings applied (channel=${nextSource.channel.name.lowercase()}, " +
                "enabled=${next.enabled}, required=${next.required})"
        )

        coordinator.reconcileSettings(next) {
            synchronized(lifecycle) {
                if (stopped.get()) {
                    false
                } else {
                    val previous = configured.get()
                    val existing = client
                    if (existing == null) {
                        Files.createDirectories(dataDirectory)
                        val created =
                            clientFactory.create(nextSource, dataDirectory.resolve("packset-cache"))
                        clientListener = created.addListener(::onClientState)
                        client = created
                        configured.set(next)
                        state.set(created.state())
                        created.start()
                    } else {
                        configured.set(next)
                        if (previous?.toClientSource() != nextSource)
                            existing.reconfigure(nextSource)
                    }
                    true
                }
            }
        }
    }

    private fun onClientState(next: PackSetClientState) {
        synchronized(lifecycle) {
            if (stopped.get()) return
            state.set(next)
        }
        val previousStatus = lastLoggedStatus.getAndSet(next.status)
        if (next.status in LOGGED_STATES && previousStatus != next.status) {
            log.info(
                "Resource-pack client transition (status=${next.status}, " +
                    "sourceChannel=${next.source.channel.name.lowercase()}, " +
                    "currentFingerprint=${next.current?.fingerprint}, " +
                    "fallbackFingerprint=${next.degradedFallback?.fingerprint}, " +
                    "reason=${normalizeDiagnosticReason(next.lastError)})"
            )
        }
        if (!stopped.get()) coordinator.onSnapshot(next)
    }

    private fun completeInitialConfiguration(
        playerId: java.util.UUID,
        session: InitialDeliverySession,
    ) {
        val initial =
            synchronized(deadlines) {
                initialDeadlines[playerId]
                    ?.takeIf { it.session === session }
                    ?.also { it.ready = true }
            } ?: return
        val handle = synchronized(deadlines) { initial.handle.also { initial.handle = null } }
        handle?.close()
        configurationWaiter.seal(playerId, initial.completion)
    }

    private fun scheduleSnapshotDeadline(session: InitialDeadline) {
        val handle =
            snapshotDeadline.schedule { expireInitialDelivery(session.player.uniqueId, session) }
        synchronized(deadlines) {
            if (
                initialDeadlines[session.player.uniqueId] === session &&
                    !session.ready &&
                    !stopped.get() &&
                    configurationWaiter.isPending(session.player.uniqueId, session.completion)
            )
                session.handle = handle
            else handle.close()
        }
    }

    private fun expireInitialDelivery(playerId: java.util.UUID, session: InitialDeadline) {
        if (!coordinator.cancelInitial(playerId, session.session)) return
        synchronized(deadlines) {
            if (initialDeadlines[playerId] !== session) return
            initialDeadlines.remove(playerId)
        }
        try {
            session.player.disconnect(
                Component.text("Resource packs are currently unavailable. Please try again.")
            )
        } finally {
            configurationWaiter.forget(playerId, session.completion)
        }
    }

    private fun forgetInitial(initial: InitialDeadline) {
        val playerId = initial.player.uniqueId
        coordinator.forget(playerId, initial.session)
        val handle =
            synchronized(deadlines) {
                if (initialDeadlines[playerId] === initial)
                    initialDeadlines.remove(playerId)?.handle
                else null
            }
        handle?.close()
        configurationWaiter.forget(playerId, initial.completion)
    }

    private class InitialDeadline(
        val player: com.velocitypowered.api.proxy.Player,
        val completion: CompletableFuture<Void>,
        val session: InitialDeliverySession,
    ) {
        var handle: AutoCloseable? = null
        var ready = false
    }

    private companion object {
        val LOGGED_STATES =
            setOf(
                PackSetClientStatus.READY,
                PackSetClientStatus.DEGRADED,
                PackSetClientStatus.UNAVAILABLE,
            )

        fun closedState(): PackSetClientState =
            PackSetClientState(
                ResourcePackSettings().toClientSource(),
                null,
                null,
                PackSetClientStatus.CLOSED,
                null,
            )
    }
}
