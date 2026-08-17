package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.Player
import gg.grounds.config.ConfigRegistrationResult
import gg.grounds.config.ConfigStartupMode
import gg.grounds.generated.BuildInfo
import gg.grounds.resourcepacks.client.PackSetClientState
import gg.grounds.resourcepacks.client.PackSetClientStatus
import gg.grounds.resourcepacks.client.PackSetSource
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.kyori.adventure.resource.ResourcePackRequest

class GroundsResourcePacksPluginTest {
    // Break caught: Velocity cannot load the plugin or order plugin-config before it with bad
    // metadata.
    @Test
    fun `annotation uses canonical id generated version and required config dependency`() {
        val annotation = GroundsResourcePacksPlugin::class.java.getAnnotation(Plugin::class.java)

        assertEquals("plugin-resourcepacks", annotation.id)
        assertEquals(BuildInfo.VERSION, annotation.version)
        assertEquals(
            listOf("plugin-config" to false),
            annotation.dependencies.map { it.id to it.optional },
        )
    }

    // Break caught: using Velocity's legacy single-pack API drops order, prompt, and required
    // flags.
    @Test
    fun `velocity sender forwards the complete Adventure request`() {
        val settings = settings()
        val request =
            VelocityPackRequestFactory()
                .request(settings, readyState(settings, snapshot(settings)))!!
        var received: ResourcePackRequest? = null
        val player =
            Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
                proxy,
                method,
                args ->
                when (method.name) {
                    "sendResourcePacks" -> {
                        received = args!!.single() as ResourcePackRequest
                        null
                    }
                    "equals" -> proxy === args?.singleOrNull()
                    "hashCode" -> 1
                    else -> defaultValue(method.returnType)
                }
            } as Player

        VelocityPlayerPackSender.send(player, request)

        assertSame(request, received)
    }

    // Break caught: degraded NOT_READY must not manufacture defaults or start CDN I/O.
    @Test
    fun `not ready registers exact scope without reading defaults or creating a client`() {
        val gateway =
            FakeConfigGateway(ConfigRegistrationResult.notReady("token=top-secret offline"))
        val clients = FakeClientFactory()
        val events = FakeEventRegistry()
        val log = FakeResourcePackLog()
        val plugin = plugin(gateway, clients, events = events, log = log)

        plugin.onInitialize(ProxyInitializeEvent())

        assertEquals(
            Registration("network", "stage", ConfigStartupMode.DEGRADED),
            gateway.registration,
        )
        assertEquals(0, gateway.currentReads)
        assertEquals(0, clients.created.size)
        assertTrue(gateway.subscribed)
        assertEquals(1, events.registered.size)
        assertFalse(log.messages.joinToString().contains("top-secret"))
        assertNull(plugin.runtimeStatus().currentFingerprint)
    }

    // Break caught: the first valid change after NOT_READY can otherwise leave the runtime inert.
    @Test
    fun `first valid change lazily creates and starts exactly one cached client`() {
        val gateway = FakeConfigGateway(ConfigRegistrationResult.notReady("bootstrap_unavailable"))
        val clients = FakeClientFactory()
        val directory = Files.createTempDirectory("resourcepacks-plugin-test")
        val plugin = plugin(gateway, clients, directory = directory)
        plugin.onInitialize(ProxyInitializeEvent())

        gateway.emit(settings())
        gateway.emit(settings(prompt = "changed"))

        assertEquals(1, clients.created.size)
        assertEquals(directory.resolve("packset-cache"), clients.cacheDirectories.single())
        assertEquals(1, clients.created.single().starts)
        assertEquals(0, clients.created.single().reconfigurations.size)
    }

    // Break caught: ordinary settings changes must not reconstruct the HTTP client.
    @Test
    fun `usable registration reconciles flags and reconfigures the same client on source change`() {
        val initial = settings(prompt = "first", required = true)
        val gateway = FakeConfigGateway(ConfigRegistrationResult.ready(), initial)
        val clients = FakeClientFactory()
        val online = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val sent = mutableListOf<ResourcePackRequest>()
        val plugin =
            plugin(
                gateway,
                clients,
                online = listOf(online),
                sender = PackSender { _, request -> sent += request },
            )
        plugin.onInitialize(ProxyInitializeEvent())
        val client = clients.created.single()
        client.emit(readyState(initial, snapshot(initial)))
        plugin.onLogin(PostLoginEvent(online))

        gateway.emit(initial.copy(prompt = "second"))
        gateway.emit(initial.copy(prompt = "second", required = false))
        gateway.emit(initial.copy(prompt = "second", required = false, enabled = false))
        gateway.emit(initial.copy(prompt = "second", required = false, enabled = true))
        val changedSource = settings(packSet = "next", prompt = "second", required = false)
        gateway.emit(changedSource)

        assertEquals(1, clients.created.size)
        assertSame(client, clients.created.single())
        assertEquals(listOf(changedSource.toClientSource()), client.reconfigurations)
        assertEquals(listOf(true, true, false, false), sent.map { it.required() })
        assertEquals(
            listOf("first", "second", "second", "second"),
            sent.map { (it.prompt() as net.kyori.adventure.text.TextComponent).content() },
        )
    }

    // Break caught: an invalid live setting can replace the last valid state or trigger I/O.
    @Test
    fun `invalid settings change preserves the running source and records no client operation`() {
        val initial = settings()
        val gateway = FakeConfigGateway(ConfigRegistrationResult.ready(), initial)
        val clients = FakeClientFactory()
        val log = FakeResourcePackLog()
        val plugin = plugin(gateway, clients, log = log)
        plugin.onInitialize(ProxyInitializeEvent())
        val client = clients.created.single()

        gateway.emit(
            initial.copy(source = initial.source.copy(baseUrl = "http://credentials.example.test"))
        )

        assertEquals(0, client.reconfigurations.size)
        assertEquals(initial.toClientSource(), client.state().source)
        assertTrue(log.messages.any { it.contains("invalid_settings") })
    }

    // Break caught: concurrent config callbacks can reconfigure the client back to an older source.
    @Test
    fun `concurrent source changes preserve config callback order`() {
        val initial = settings()
        val gateway = FakeConfigGateway(ConfigRegistrationResult.ready(), initial)
        val clients = FakeClientFactory()
        val secondReconciliationEntered = CountDownLatch(1)
        val releaseSecondReconciliation = CountDownLatch(1)
        val playerReads = AtomicInteger()
        val players = OnlinePlayerView {
            if (playerReads.incrementAndGet() == 2) {
                secondReconciliationEntered.countDown()
                releaseSecondReconciliation.await(1, TimeUnit.SECONDS)
            }
            emptyList()
        }
        val plugin =
            GroundsResourcePacksPlugin(
                Files.createTempDirectory("resourcepacks-plugin-test"),
                { mapOf("GROUNDS_ENVIRONMENT" to "stage") },
                gateway,
                clients,
                players,
                PackSender { _, _ -> },
                FakeEventRegistry(),
                FakeResourcePackLog(),
            )
        plugin.onInitialize(ProxyInitializeEvent())
        val client = clients.created.single()
        val firstSource = settings(packSet = "first")
        val secondSource = settings(packSet = "second")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { gateway.emit(firstSource) }
            assertTrue(secondReconciliationEntered.await(1, TimeUnit.SECONDS))
            val secondStarted = CountDownLatch(1)
            val second =
                executor.submit {
                    secondStarted.countDown()
                    gateway.emit(secondSource)
                }
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
            assertFalse(second.isDone)
            releaseSecondReconciliation.countDown()
            first.get(1, TimeUnit.SECONDS)
            second.get(1, TimeUnit.SECONDS)
        } finally {
            releaseSecondReconciliation.countDown()
            executor.shutdownNow()
        }

        assertEquals(
            listOf(firstSource.toClientSource(), secondSource.toClientSource()),
            client.reconfigurations,
        )
    }

    // Break caught: shutdown can leak config/client/status listeners or accept late callbacks.
    @Test
    fun `shutdown closes client and listeners and ignores later config changes`() {
        val initial = settings()
        val gateway = FakeConfigGateway(ConfigRegistrationResult.ready(), initial)
        val clients = FakeClientFactory()
        val events = FakeEventRegistry()
        val plugin = plugin(gateway, clients, events = events)
        plugin.onInitialize(ProxyInitializeEvent())
        val client = clients.created.single()

        plugin.onShutdown(ProxyShutdownEvent())
        gateway.emit(settings(packSet = "late"))

        assertTrue(client.closed)
        assertTrue(client.listenerClosed)
        assertTrue(gateway.listenerClosed)
        assertEquals(events.registered, events.unregistered)
        assertEquals(0, client.reconfigurations.size)
        assertEquals(PackSetClientStatus.CLOSED, plugin.runtimeStatus().clientStatus)
    }

    // Break caught: runtime diagnostics can expose raw errors or omit delivery/status counters.
    @Test
    fun `runtime status snapshots client fingerprints safe errors and counters`() {
        val initial = settings()
        val gateway = FakeConfigGateway(ConfigRegistrationResult.ready(), initial)
        val clients = FakeClientFactory()
        val events = FakeEventRegistry()
        val log = FakeResourcePackLog()
        val online = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val plugin =
            plugin(
                gateway,
                clients,
                online = listOf(online),
                sender = PackSender { _, _ -> },
                events = events,
                log = log,
            )
        plugin.onInitialize(ProxyInitializeEvent())
        val fallback = snapshot(initial)
        clients.created
            .single()
            .emit(degradedState(initial, fallback).copy(lastError = "token=do-not-log offline"))
        plugin.onLogin(PostLoginEvent(online))
        val statusListener = events.registered.single().second as ResourcePackStatusListener
        statusListener.onStatus(
            PlayerResourcePackStatusEvent(
                online,
                fallback.packs.single().uuid,
                PlayerResourcePackStatusEvent.Status.ACCEPTED,
                null,
            )
        )
        statusListener.onStatus(
            PlayerResourcePackStatusEvent(
                online,
                fallback.packs.single().uuid,
                PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
                null,
            )
        )

        val status = plugin.runtimeStatus()
        assertEquals(PackSetClientStatus.DEGRADED, status.clientStatus)
        assertNull(status.currentFingerprint)
        assertEquals(fallback.fingerprint, status.fallbackFingerprint)
        assertFalse(status.lastError!!.contains("do-not-log"))
        assertEquals(1, status.requested)
        assertEquals(1, status.accepted)
        assertEquals(1, status.failed)
        assertFalse(log.messages.joinToString().contains("do-not-log"))
    }

    // Break caught: ignoring STARTING hides the subsequent READY transition after reconfigure.
    @Test
    fun `every ready transition is logged after an intermediate starting state`() {
        val initial = settings()
        val gateway = FakeConfigGateway(ConfigRegistrationResult.ready(), initial)
        val clients = FakeClientFactory()
        val log = FakeResourcePackLog()
        val plugin = plugin(gateway, clients, log = log)
        plugin.onInitialize(ProxyInitializeEvent())
        val client = clients.created.single()
        val first = snapshot(initial, sequence = 1)
        val second = snapshot(initial, sequence = 2)

        client.emit(readyState(initial, first))
        client.emit(
            PackSetClientState(
                initial.toClientSource(),
                null,
                first,
                PackSetClientStatus.STARTING,
                null,
            )
        )
        client.emit(readyState(initial, second))

        val transitions = log.messages.filter { it.contains("client transition") }
        assertEquals(2, transitions.size)
        assertTrue(transitions.first().contains(first.fingerprint))
        assertTrue(transitions.last().contains(second.fingerprint))
    }

    private fun plugin(
        gateway: FakeConfigGateway,
        clients: FakeClientFactory,
        directory: Path = Files.createTempDirectory("resourcepacks-plugin-test"),
        online: Collection<com.velocitypowered.api.proxy.Player> = emptyList(),
        sender: PackSender = PackSender { _, _ -> },
        events: FakeEventRegistry = FakeEventRegistry(),
        log: FakeResourcePackLog = FakeResourcePackLog(),
    ) =
        GroundsResourcePacksPlugin(
            dataDirectory = directory,
            environment = { mapOf("GROUNDS_ENVIRONMENT" to "stage") },
            configGateway = gateway,
            clientFactory = clients,
            players = OnlinePlayerView { online },
            sender = sender,
            eventRegistry = events,
            log = log,
        )
}

internal data class Registration(
    val app: String,
    val environment: String,
    val mode: ConfigStartupMode,
)

internal class FakeConfigGateway(
    private val result: ConfigRegistrationResult,
    private var current: ResourcePackSettings? = null,
) : ResourcePackConfigGateway {
    var registration: Registration? = null
    var currentReads = 0
    var subscribed = false
    var listenerClosed = false
    private var listener: ((ResourcePackSettings) -> Unit)? = null

    override fun register(
        app: String,
        environment: String,
        mode: ConfigStartupMode,
    ): ConfigRegistrationResult {
        registration = Registration(app, environment, mode)
        return result
    }

    override fun current(): ResourcePackSettings {
        currentReads += 1
        return checkNotNull(current)
    }

    override fun onChange(listener: (ResourcePackSettings) -> Unit): AutoCloseable {
        subscribed = true
        this.listener = listener
        return AutoCloseable {
            listenerClosed = true
            this.listener = null
        }
    }

    fun emit(settings: ResourcePackSettings) {
        current = settings
        listener?.invoke(settings)
    }
}

internal class FakeClientFactory : ResourcePackClientFactory {
    val created = mutableListOf<FakeResourcePackClient>()
    val cacheDirectories = mutableListOf<Path>()

    override fun create(source: PackSetSource, cacheDirectory: Path): ResourcePackClient {
        cacheDirectories.add(cacheDirectory)
        return FakeResourcePackClient(source).also(created::add)
    }
}

internal class FakeResourcePackClient(source: PackSetSource) : ResourcePackClient {
    private var currentState =
        PackSetClientState(source, null, null, PackSetClientStatus.STARTING, null)
    private var listener: ((PackSetClientState) -> Unit)? = null
    var starts = 0
    val reconfigurations = mutableListOf<PackSetSource>()
    var listenerClosed = false
    var closed = false

    override fun state(): PackSetClientState = currentState

    override fun start() {
        starts += 1
    }

    override fun reconfigure(source: PackSetSource) {
        reconfigurations += source
    }

    override fun addListener(listener: (PackSetClientState) -> Unit): AutoCloseable {
        this.listener = listener
        return AutoCloseable {
            listenerClosed = true
            this.listener = null
        }
    }

    override fun close() {
        closed = true
        currentState =
            currentState.copy(
                current = null,
                degradedFallback = null,
                status = PackSetClientStatus.CLOSED,
            )
    }

    fun emit(state: PackSetClientState) {
        currentState = state
        listener?.invoke(state)
    }
}

internal class FakeEventRegistry : ResourcePackEventRegistry {
    val registered = mutableListOf<Pair<Any, Any>>()
    val unregistered = mutableListOf<Pair<Any, Any>>()

    override fun register(owner: Any, listener: Any) {
        registered += owner to listener
    }

    override fun unregister(owner: Any, listener: Any) {
        unregistered += owner to listener
    }
}

internal class FakeResourcePackLog : ResourcePackLog {
    val messages = mutableListOf<String>()

    override fun info(message: String) {
        messages += message
    }

    override fun warn(message: String) {
        messages += message
    }
}
