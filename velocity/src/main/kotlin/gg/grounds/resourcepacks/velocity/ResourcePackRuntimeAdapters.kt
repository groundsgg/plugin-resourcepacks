package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import gg.grounds.config.ConfigManager
import gg.grounds.config.ConfigRegistrationResult
import gg.grounds.config.ConfigStartupMode
import gg.grounds.config.VelocityConfigManagerServices
import gg.grounds.resourcepacks.client.PackSetClient
import gg.grounds.resourcepacks.client.PackSetClientConfig
import gg.grounds.resourcepacks.client.PackSetClientState
import gg.grounds.resourcepacks.client.PackSetSource
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger

internal object VelocityPlayerPackSender : PackSender {
    override fun send(player: Player, request: net.kyori.adventure.resource.ResourcePackRequest) =
        player.sendResourcePacks(request)
}

internal interface ResourcePackConfigGateway {
    fun register(
        app: String,
        environment: String,
        mode: ConfigStartupMode,
    ): ConfigRegistrationResult

    fun current(): ResourcePackSettings

    fun onChange(listener: (ResourcePackSettings) -> Unit): AutoCloseable
}

internal class VelocityResourcePackConfigGateway(private val proxy: ProxyServer) :
    ResourcePackConfigGateway {
    private lateinit var manager: ConfigManager

    override fun register(
        app: String,
        environment: String,
        mode: ConfigStartupMode,
    ): ConfigRegistrationResult {
        manager = VelocityConfigManagerServices.require(proxy)
        return manager.register(ResourcePackSettingsDefinition, app, environment, mode)
    }

    override fun current(): ResourcePackSettings = manager[ResourcePackSettingsDefinition]

    override fun onChange(listener: (ResourcePackSettings) -> Unit): AutoCloseable {
        val activeListener = AtomicReference<(ResourcePackSettings) -> Unit>(listener)
        manager.onChange(ResourcePackSettingsDefinition) { settings ->
            activeListener.get()?.invoke(settings)
        }
        return AutoCloseable { activeListener.set(null) }
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

internal fun sanitizeLogText(value: String): String =
    value
        .replace(
            Regex("(?i)\\bauthorization\\b\\s*[:=]\\s*Bearer\\s+[^\\s,;]+"),
            "authorization=<redacted>",
        )
        .replace(
            Regex(
                "(?i)\\b(token|secret(?:[_-]?key)?|password|authorization|credential|access[_-]?key(?:[_-]?id)?)\\b\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)"
            ),
            "$1=<redacted>",
        )

internal fun sanitizeNullableLogText(value: String?): String? = value?.let(::sanitizeLogText)
