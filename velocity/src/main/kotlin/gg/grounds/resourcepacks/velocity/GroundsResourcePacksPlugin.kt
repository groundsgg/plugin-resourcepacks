package gg.grounds.resourcepacks.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
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
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger

@Plugin(
    id = "plugin-resourcepacks",
    name = "Grounds ResourcePacks",
    version = BuildInfo.VERSION,
    dependencies = [Dependency(id = "plugin-config")],
)
class GroundsResourcePacksPlugin
internal constructor(
    private val dataDirectory: Path,
    private val environment: () -> Map<String, String>,
    private val configGateway: ResourcePackConfigGateway,
    private val clientFactory: ResourcePackClientFactory,
    players: OnlinePlayerView,
    sender: PackSender,
    private val eventRegistry: ResourcePackEventRegistry,
    private val log: ResourcePackLog,
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
        OnlinePlayerView { proxy.allPlayers },
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
    private val coordinator =
        ResourcePackCoordinator(
            configured::get,
            state::get,
            players,
            PackSender { player, request ->
                sender.send(player, request)
                metrics.requested()
            },
            VelocityPackRequestFactory(),
        )

    private var client: ResourcePackClient? = null
    private var clientListener: AutoCloseable? = null
    private var configListener: AutoCloseable? = null
    private var statusListener: ResourcePackStatusListener? = null

    @Subscribe
    fun onInitialize(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        if (!initialized.compareAndSet(false, true)) return
        val listener = ResourcePackStatusListener(metrics, coordinator::targetId, log)
        statusListener = listener
        eventRegistry.register(this, listener)

        val deploymentEnvironment =
            ResourcePackEnvironment.from(environment()).deploymentEnvironment
        val result =
            configGateway.register(
                app = "network",
                environment = deploymentEnvironment,
                mode = ConfigStartupMode.DEGRADED,
            )
        val subscription = configGateway.onChange(::applySettings)
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
    fun onLogin(event: PostLoginEvent) {
        if (!stopped.get()) coordinator.onLogin(event.player)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        coordinator.forget(event.player.uniqueId)
    }

    @Subscribe
    fun onShutdown(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
        if (!stopped.compareAndSet(false, true)) return
        var configToClose: AutoCloseable? = null
        var clientListenerToClose: AutoCloseable? = null
        var statusToUnregister: ResourcePackStatusListener? = null
        var clientToClose: ResourcePackClient? = null
        coordinator.clear()
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
            }

        coordinator.reconcileSettings(next) {
            synchronized(lifecycle) {
                if (stopped.get()) {
                    false
                } else {
                    val previous = configured.get()
                    val existing = client
                    if (existing == null) {
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
                    "currentFingerprint=${next.current?.fingerprint}, " +
                    "fallbackFingerprint=${next.degradedFallback?.fingerprint}, " +
                    "reason=${normalizeDiagnosticReason(next.lastError)})"
            )
        }
        if (!stopped.get()) coordinator.onSnapshot(next)
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
