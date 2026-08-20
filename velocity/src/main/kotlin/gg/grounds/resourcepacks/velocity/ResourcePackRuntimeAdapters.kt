package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import gg.grounds.config.ConfigDefinition
import gg.grounds.config.ConfigDefinitionNotReadyException
import gg.grounds.config.ConfigManager
import gg.grounds.config.ConfigRegistrationResult
import gg.grounds.config.ConfigStartupMode
import gg.grounds.config.VelocityConfigManagerServices
import gg.grounds.resourcepacks.client.PackSetClient
import gg.grounds.resourcepacks.client.PackSetClientConfig
import gg.grounds.resourcepacks.client.PackSetClientState
import gg.grounds.resourcepacks.client.PackSetSource
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger

internal object VelocityPlayerPackSender : PackSender {
    override fun send(player: Player, request: net.kyori.adventure.resource.ResourcePackRequest) =
        player.sendResourcePacks(request)
}

internal interface ResourcePackConfigGateway {
    fun register(
        definition: ConfigDefinition<ResourcePackSettings>,
        app: String,
        environment: String,
        mode: ConfigStartupMode,
    ): ConfigRegistrationResult

    fun onChange(
        definition: ConfigDefinition<ResourcePackSettings>,
        listener: (ResourcePackSettings) -> Unit,
    ): ResourcePackConfigSubscription
}

internal interface ResourcePackConfigSubscription : AutoCloseable {
    fun deliverLatestIfAvailable()
}

internal interface ResourcePackConfigBackend {
    fun register(
        definition: ConfigDefinition<ResourcePackSettings>,
        app: String,
        environment: String,
        mode: ConfigStartupMode,
    ): ConfigRegistrationResult

    fun current(definition: ConfigDefinition<ResourcePackSettings>): ResourcePackSettings

    fun onChange(definition: ConfigDefinition<ResourcePackSettings>, listener: () -> Unit)
}

private class VelocityResourcePackConfigBackend(private val proxy: ProxyServer) :
    ResourcePackConfigBackend {
    private lateinit var manager: ConfigManager

    override fun register(
        definition: ConfigDefinition<ResourcePackSettings>,
        app: String,
        environment: String,
        mode: ConfigStartupMode,
    ): ConfigRegistrationResult {
        manager = VelocityConfigManagerServices.require(proxy)
        return manager.register(definition, app, environment, mode)
    }

    override fun current(definition: ConfigDefinition<ResourcePackSettings>): ResourcePackSettings =
        manager[definition]

    override fun onChange(
        definition: ConfigDefinition<ResourcePackSettings>,
        listener: () -> Unit,
    ) {
        manager.onChange(definition) { listener() }
    }
}

internal class VelocityResourcePackConfigGateway(private val backend: ResourcePackConfigBackend) :
    ResourcePackConfigGateway {
    constructor(proxy: ProxyServer) : this(VelocityResourcePackConfigBackend(proxy))

    override fun register(
        definition: ConfigDefinition<ResourcePackSettings>,
        app: String,
        environment: String,
        mode: ConfigStartupMode,
    ): ConfigRegistrationResult = backend.register(definition, app, environment, mode)

    override fun onChange(
        definition: ConfigDefinition<ResourcePackSettings>,
        listener: (ResourcePackSettings) -> Unit,
    ): ResourcePackConfigSubscription {
        val subscription = SerializedResourcePackConfigSubscription(backend, definition, listener)
        backend.onChange(definition, subscription::deliverLatestIfAvailable)
        return subscription
    }
}

private class SerializedResourcePackConfigSubscription(
    private val backend: ResourcePackConfigBackend,
    private val definition: ConfigDefinition<ResourcePackSettings>,
    listener: (ResourcePackSettings) -> Unit,
) : ResourcePackConfigSubscription {
    private val activeListener = AtomicReference<(ResourcePackSettings) -> Unit>(listener)
    private val delivery = Any()
    private val latestDelivery = AtomicLong()

    override fun deliverLatestIfAvailable() {
        if (activeListener.get() == null) return
        val deliveryId = latestDelivery.incrementAndGet()
        val current =
            try {
                backend.current(definition)
            } catch (failure: ConfigDefinitionNotReadyException) {
                if (failure.definition !== definition) throw failure
                return
            }
        synchronized(delivery) {
            if (deliveryId == latestDelivery.get()) activeListener.get()?.invoke(current)
        }
    }

    override fun close() {
        synchronized(delivery) { activeListener.set(null) }
    }
}

internal interface ResourcePackClient : AutoCloseable {
    fun state(): PackSetClientState

    fun start()

    fun reconfigure(source: PackSetSource)

    fun addListener(listener: (PackSetClientState) -> Unit): AutoCloseable
}

internal fun interface ResourcePackClientFactory {
    fun create(source: PackSetSource, cacheDirectory: Path): ResourcePackClient
}

internal object DefaultResourcePackClientFactory : ResourcePackClientFactory {
    override fun create(source: PackSetSource, cacheDirectory: Path): ResourcePackClient =
        PackSetClientAdapter(PackSetClient(PackSetClientConfig(source, cacheDirectory)))
}

private class PackSetClientAdapter(private val delegate: PackSetClient) : ResourcePackClient {
    override fun state(): PackSetClientState = delegate.state()

    override fun start() = delegate.start()

    override fun reconfigure(source: PackSetSource) {
        // PackSetClient owns its scheduler; this only queues refresh work.
        delegate.reconfigure(source)
    }

    override fun addListener(listener: (PackSetClientState) -> Unit): AutoCloseable =
        delegate.addListener(listener)

    override fun close() = delegate.close()
}

internal interface ResourcePackEventRegistry {
    fun register(owner: Any, listener: Any)

    fun unregister(owner: Any, listener: Any)
}

internal class VelocityResourcePackEventRegistry(private val proxy: ProxyServer) :
    ResourcePackEventRegistry {
    override fun register(owner: Any, listener: Any) = proxy.eventManager.register(owner, listener)

    override fun unregister(owner: Any, listener: Any) =
        proxy.eventManager.unregisterListener(owner, listener)
}

internal interface ResourcePackLog {
    fun info(message: String)

    fun warn(message: String)
}

internal class Slf4jResourcePackLog(private val logger: Logger) : ResourcePackLog {
    override fun info(message: String) = logger.info(message)

    override fun warn(message: String) = logger.warn(message)
}

internal fun normalizeDiagnosticReason(value: String?): String? =
    value?.let { SAFE_DIAGNOSTIC_REASONS[it] ?: "unknown_failure" }

private val SAFE_DIAGNOSTIC_REASONS =
    mapOf(
        "Channel cache was unavailable." to "channel_cache_unavailable",
        "Manifest cache was unavailable." to "manifest_cache_unavailable",
        "Channel document was invalid." to "channel_document_invalid",
        "Manifest integrity check failed." to "manifest_integrity_failed",
        "Manifest document was invalid." to "manifest_document_invalid",
        "Manifest target did not match channel." to "manifest_target_mismatch",
        "Channel request failed." to "channel_request_failed",
        "Manifest request failed." to "manifest_request_failed",
        "Cache write failed." to "cache_write_failed",
        "Refresh failed." to "refresh_failed",
        "Refresh scheduler was unavailable." to "refresh_scheduler_unavailable",
        "Client is closed." to "client_closed",
        "Source changed." to "source_changed",
        "loaded_cached_snapshot" to "loaded_cached_snapshot",
        "bootstrap_failed_no_cached_snapshot" to "bootstrap_failed_no_cached_snapshot",
        "binding_not_initialized" to "binding_not_initialized",
        "definition_already_registered" to "definition_already_registered",
        "config_key_already_registered" to "config_key_already_registered",
    )
