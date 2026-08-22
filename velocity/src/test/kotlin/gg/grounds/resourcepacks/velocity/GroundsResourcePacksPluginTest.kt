package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.Player
import gg.grounds.config.ConfigDefinition
import gg.grounds.config.ConfigDefinitionNotReadyException
import gg.grounds.config.ConfigRegistrationResult
import gg.grounds.config.ConfigStartupMode
import gg.grounds.generated.BuildInfo
import gg.grounds.resourcepacks.client.PackSetClientState
import gg.grounds.resourcepacks.client.PackSetClientStatus
import gg.grounds.resourcepacks.client.PackSetSource
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(1, gateway.currentReads)
        assertEquals(0, clients.created.size)
        assertTrue(gateway.subscribed)
        assertEquals(1, events.registered.size)
        assertFalse(log.messages.joinToString().contains("top-secret"))
        assertTrue(log.messages.single().contains("reason=unknown_failure"))
        assertNull(plugin.runtimeStatus().currentFingerprint)
    }

    // Break caught: Stage can register the approved Stable static default after selecting Edge,
    // or later config reads can switch to a second definition instance.
    @Test
    fun `edge bootstrap registers and observes one edge definition in the stage scope`() {
        val gateway = FakeConfigGateway(ConfigRegistrationResult.ready())
        val plugin =
            GroundsResourcePacksPlugin(
                Files.createTempDirectory("resourcepacks-plugin-test"),
                {
                    mapOf(
                        "GROUNDS_ENVIRONMENT" to "stage",
                        "RESOURCE_PACK_DEFAULT_CHANNEL" to "edge",
                    )
                },
                gateway,
                FakeClientFactory(),
                OnlinePlayerView { emptyList() },
                PackSender { _, _ -> },
                FakeEventRegistry(),
                FakeResourcePackLog(),
            )

        plugin.onInitialize(ProxyInitializeEvent())

        assertEquals(1, gateway.registrations)
        assertEquals(
            Registration("network", "stage", ConfigStartupMode.DEGRADED),
            gateway.registration,
        )
        assertEquals("edge", gateway.registeredDefinition!!.defaultValue.source.channel)
        assertSame(gateway.registeredDefinition, gateway.observedDefinition)
    }

    // Break caught: a malformed deployment variable can partially initialize listeners or CDN
    // client state before refusing an unsafe default.
    @Test
    fun `invalid bootstrap channel fails before registration listener installation or client creation`() {
        val gateway = FakeConfigGateway(ConfigRegistrationResult.ready())
        val clients = FakeClientFactory()
        val events = FakeEventRegistry()
        val plugin =
            GroundsResourcePacksPlugin(
                Files.createTempDirectory("resourcepacks-plugin-test"),
                {
                    mapOf(
                        "GROUNDS_ENVIRONMENT" to "stage",
                        "RESOURCE_PACK_DEFAULT_CHANNEL" to "EDGE",
                    )
                },
                gateway,
                clients,
                OnlinePlayerView { emptyList() },
                PackSender { _, _ -> },
                events,
                FakeResourcePackLog(),
            )

        assertFailsWith<IllegalArgumentException> { plugin.onInitialize(ProxyInitializeEvent()) }

        assertNull(gateway.registration)
        assertFalse(gateway.subscribed)
        assertEquals(emptyList(), events.registered)
        assertEquals(emptyList(), clients.created)
    }

    // Break caught: NOT_READY can become valid after register returns but before callback
    // installation, leaving no later notification to wake the plugin.
    @Test
    fun `not ready registration delivers a value that becomes current before subscription`() {
        val latest = settings(packSet = "latest")
        val backend =
            FakeResourcePackConfigBackend(
                initial = null,
                registrationResult =
                    ConfigRegistrationResult.notReady("bootstrap_failed_no_cached_snapshot"),
            )
        backend.becomeAvailableBeforeListenerInstallation(latest)
        val clients = FakeClientFactory()
        val log = FakeResourcePackLog()
        val plugin =
            GroundsResourcePacksPlugin(
                Files.createTempDirectory("resourcepacks-plugin-test"),
                { mapOf("GROUNDS_ENVIRONMENT" to "stage") },
                VelocityResourcePackConfigGateway(backend),
                clients,
                OnlinePlayerView { emptyList() },
                PackSender { _, _ -> },
                FakeEventRegistry(),
                log,
            )

        plugin.onInitialize(ProxyInitializeEvent())

        val client = clients.created.single()
        assertEquals(latest.toClientSource(), client.state().source)
        assertEquals(1, client.starts)
        assertEquals(emptyList(), client.reconfigurations)
        assertEquals(1, backend.currentReads)
        assertTrue(
            log.messages.contains(
                "Resource-pack configuration not ready (status=NOT_READY, " +
                    "reason=bootstrap_failed_no_cached_snapshot)"
            )
        )
    }

    // Break caught: probing after NOT_READY must treat only the manager's precise not-ready state
    // as an empty latest delivery and must not manufacture settings.
    @Test
    fun `genuinely unavailable manager remains inert after the serialized initial probe`() {
        val backend =
            FakeResourcePackConfigBackend(
                initial = null,
                registrationResult = ConfigRegistrationResult.notReady("bootstrap_unavailable"),
            )
        val clients = FakeClientFactory()
        val plugin =
            GroundsResourcePacksPlugin(
                Files.createTempDirectory("resourcepacks-plugin-test"),
                { mapOf("GROUNDS_ENVIRONMENT" to "stage") },
                VelocityResourcePackConfigGateway(backend),
                clients,
                OnlinePlayerView { emptyList() },
                PackSender { _, _ -> },
                FakeEventRegistry(),
                FakeResourcePackLog(),
            )

        plugin.onInitialize(ProxyInitializeEvent())

        assertEquals(1, backend.currentReads)
        assertEquals(emptyList(), clients.created)
        assertNull(plugin.runtimeStatus().currentFingerprint)
    }

    // Break caught: broad exception suppression can silently strand the plugin on real manager
    // failures that are not the documented not-ready state.
    @Test
    fun `unexpected latest config read failure is not swallowed`() {
        val backend =
            FakeResourcePackConfigBackend(
                initial = null,
                registrationResult = ConfigRegistrationResult.notReady("bootstrap_unavailable"),
            )
        backend.failCurrentRead(IllegalStateException("manager_corrupt"))
        val clients = FakeClientFactory()
        val plugin =
            GroundsResourcePacksPlugin(
                Files.createTempDirectory("resourcepacks-plugin-test"),
                { mapOf("GROUNDS_ENVIRONMENT" to "stage") },
                VelocityResourcePackConfigGateway(backend),
                clients,
                OnlinePlayerView { emptyList() },
                PackSender { _, _ -> },
                FakeEventRegistry(),
                FakeResourcePackLog(),
            )

        val failure =
            assertFailsWith<IllegalStateException> { plugin.onInitialize(ProxyInitializeEvent()) }

        assertEquals("manager_corrupt", failure.message)
        assertEquals(emptyList(), clients.created)
    }

    // Break caught: the first valid change after NOT_READY can otherwise leave the runtime inert.
    @Test
    fun `first valid change lazily creates and starts exactly one cached client`() {
        val gateway = FakeConfigGateway(ConfigRegistrationResult.notReady("bootstrap_unavailable"))
        val clients = FakeClientFactory()
        val directory =
            Files.createTempDirectory("resourcepacks-plugin-test").resolve("plugin-resourcepacks")
        val plugin = plugin(gateway, clients, directory = directory)
        plugin.onInitialize(ProxyInitializeEvent())

        gateway.emit(settings())
        gateway.emit(settings(prompt = "changed"))

        assertEquals(1, clients.created.size)
        assertTrue(Files.isDirectory(directory))
        assertEquals(directory.resolve("packset-cache"), clients.cacheDirectories.single())
        assertEquals(1, clients.created.single().starts)
        assertEquals(0, clients.created.single().reconfigurations.size)
    }

    // Break caught: URI(String) reports malformed escapes and whitespace with a checked
    // URISyntaxException rather than IllegalArgumentException.
    @Test
    fun `malformed base URI is rejected without aborting config delivery`() {
        val malformed =
            settings()
                .copy(source = settings().source.copy(baseUrl = "https://assets.example.test/%zz"))
        val gateway = FakeConfigGateway(ConfigRegistrationResult.ready(), malformed)
        val clients = FakeClientFactory()
        val log = FakeResourcePackLog()
        val plugin = plugin(gateway, clients, log = log)

        plugin.onInitialize(ProxyInitializeEvent())

        assertEquals(emptyList(), clients.created)
        assertEquals(
            listOf("Resource-pack settings rejected (reason=invalid_settings)"),
            log.messages,
        )
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
            initial.copy(
                source =
                    initial.source.copy(
                        channel =
                            "edge\nAuthorization: Basic dXNlcjpwYXNz\n<body>private response</body>"
                    )
            )
        )

        assertEquals(0, client.reconfigurations.size)
        assertEquals(initial.toClientSource(), client.state().source)
        assertEquals(
            "Resource-pack settings rejected (reason=invalid_settings)",
            log.messages.last(),
        )
    }

    // Break caught: a delayed callback carrying an old payload can reconfigure the client back to
    // an obsolete source after a newer manager value is already current.
    @Test
    fun `delayed config callback resolves the latest manager value instead of its old payload`() {
        val initial = settings()
        val backend = FakeResourcePackConfigBackend(initial)
        val gateway = VelocityResourcePackConfigGateway(backend)
        val clients = FakeClientFactory()
        val plugin =
            GroundsResourcePacksPlugin(
                Files.createTempDirectory("resourcepacks-plugin-test"),
                { mapOf("GROUNDS_ENVIRONMENT" to "stage") },
                gateway,
                clients,
                OnlinePlayerView { emptyList() },
                PackSender { _, _ -> },
                FakeEventRegistry(),
                FakeResourcePackLog(),
            )
        plugin.onInitialize(ProxyInitializeEvent())
        val client = clients.created.single()
        val delayedOldSource = settings(packSet = "old-delayed")
        val latestSource = settings(packSet = "latest")

        val delayedCallback = backend.captureChange(delayedOldSource)
        backend.publishChange(latestSource)
        delayedCallback()

        assertEquals(listOf(latestSource.toClientSource()), client.reconfigurations)
    }

    // Break caught: initial current delivery can apply A after a callback for current B when it
    // does not share the callback delivery monitor.
    @Test
    fun `callback overtakes a paused initial read without allowing stale rollback`() {
        val initial = settings(packSet = "initial")
        val latest = settings(packSet = "latest")
        val backend = FakeResourcePackConfigBackend(initial)
        backend.pauseNextCurrentRead()
        val clients = FakeClientFactory()
        val plugin =
            GroundsResourcePacksPlugin(
                Files.createTempDirectory("resourcepacks-plugin-test"),
                { mapOf("GROUNDS_ENVIRONMENT" to "stage") },
                VelocityResourcePackConfigGateway(backend),
                clients,
                OnlinePlayerView { emptyList() },
                PackSender { _, _ -> },
                FakeEventRegistry(),
                FakeResourcePackLog(),
            )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val initialize = executor.submit { plugin.onInitialize(ProxyInitializeEvent()) }
            assertTrue(backend.currentReadEntered.await(1, TimeUnit.SECONDS))
            val callbackStarted = CountDownLatch(1)
            val callback =
                executor.submit {
                    callbackStarted.countDown()
                    backend.publishChange(latest)
                }
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS))
            callback.get(1, TimeUnit.SECONDS)
            assertEquals(latest.toClientSource(), clients.created.single().state().source)

            backend.releaseCurrentRead.countDown()
            initialize.get(1, TimeUnit.SECONDS)
        } finally {
            backend.releaseCurrentRead.countDown()
            executor.shutdownNow()
        }

        assertEquals(emptyList(), clients.created.single().reconfigurations)
    }

    // Break caught: when a callback applies B during subscription, a later independent initial
    // read can still create or reconfigure the client with stale A.
    @Test
    fun `callback delivery before initial delivery keeps the callback value current`() {
        val initial = settings(packSet = "initial")
        val latest = settings(packSet = "latest")
        val backend = FakeResourcePackConfigBackend(initial)
        backend.publishDuringSubscription(latest)
        val clients = FakeClientFactory()
        val plugin =
            GroundsResourcePacksPlugin(
                Files.createTempDirectory("resourcepacks-plugin-test"),
                { mapOf("GROUNDS_ENVIRONMENT" to "stage") },
                VelocityResourcePackConfigGateway(backend),
                clients,
                OnlinePlayerView { emptyList() },
                PackSender { _, _ -> },
                FakeEventRegistry(),
                FakeResourcePackLog(),
            )

        plugin.onInitialize(ProxyInitializeEvent())

        val client = clients.created.single()
        assertEquals(latest.toClientSource(), client.state().source)
        assertEquals(emptyList(), client.reconfigurations)
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
        assertEquals("unknown_failure", status.lastError)
        assertEquals(1, status.requested)
        assertEquals(1, status.accepted)
        assertEquals(1, status.failed)
        assertFalse(log.messages.joinToString().contains("do-not-log"))
        assertTrue(log.messages.any { it.contains("reason=unknown_failure") })
    }

    // Break caught: a late status for an already-sent pack can be attributed to the newly current
    // target after a source reconfiguration.
    @Test
    fun `late pack status retains the target active when that pack was sent`() {
        val initial = settings(packSet = "old")
        val changed = settings(packSet = "new")
        val oldPack = resolvedPack(uuid = UUID.fromString("11111111-1111-1111-1111-111111111111"))
        val newPack = resolvedPack(uuid = UUID.fromString("22222222-2222-2222-2222-222222222222"))
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
        val client = clients.created.single()
        client.emit(readyState(initial, snapshot(initial, sequence = 1, packs = listOf(oldPack))))
        gateway.emit(changed)
        client.emit(readyState(changed, snapshot(changed, sequence = 2, packs = listOf(newPack))))
        val listener = events.registered.single().second as ResourcePackStatusListener

        listener.onStatus(
            PlayerResourcePackStatusEvent(
                online,
                oldPack.uuid,
                PlayerResourcePackStatusEvent.Status.DOWNLOADED,
                null,
            )
        )

        assertTrue(log.messages.last().contains("targetId=v1.0.1"))
        assertFalse(log.messages.last().contains("targetId=v1.0.2"))
    }

    // Break caught: when two overlapping requests reuse a pack UUID, a late event for A can be
    // falsely attributed to B even though Velocity exposes no request identity.
    @Test
    fun `same pack uuid across targets makes late status attribution unknown`() {
        val initial = settings(packSet = "old")
        val changed = settings(packSet = "new")
        val samePack = resolvedPack(uuid = UUID.fromString("11111111-1111-1111-1111-111111111111"))
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
        val client = clients.created.single()
        client.emit(readyState(initial, snapshot(initial, sequence = 1, packs = listOf(samePack))))
        gateway.emit(changed)
        client.emit(readyState(changed, snapshot(changed, sequence = 2, packs = listOf(samePack))))
        val listener = events.registered.single().second as ResourcePackStatusListener

        listener.onStatus(
            PlayerResourcePackStatusEvent(
                online,
                samePack.uuid,
                PlayerResourcePackStatusEvent.Status.SUCCESSFUL,
                null,
            )
        )

        assertTrue(log.messages.last().contains("targetId=unknown"))
        assertFalse(log.messages.last().contains("targetId=v1.0.1"))
        assertFalse(log.messages.last().contains("targetId=v1.0.2"))
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
        assertTrue(transitions.first().contains("sourceChannel=stable"))
        assertTrue(transitions.last().contains(second.fingerprint))
    }

    @Test
    fun `applied settings log the effective source channel`() {
        val edge = settings().copy(source = settings().source.copy(channel = "edge"))
        val gateway = FakeConfigGateway(ConfigRegistrationResult.ready(), edge)
        val clients = FakeClientFactory()
        val log = FakeResourcePackLog()
        val plugin = plugin(gateway, clients, log = log)

        plugin.onInitialize(ProxyInitializeEvent())

        assertTrue(log.messages.any { it.contains("settings applied (channel=edge") })
    }

    private fun plugin(
        gateway: ResourcePackConfigGateway,
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

internal class FakeResourcePackConfigBackend(
    initial: ResourcePackSettings?,
    private val registrationResult: ConfigRegistrationResult = ConfigRegistrationResult.ready(),
) : ResourcePackConfigBackend {
    @Volatile private var current = initial
    private var listener: (() -> Unit)? = null
    private val pauseCurrentRead = AtomicBoolean(false)
    private var duringSubscription: ResourcePackSettings? = null
    private var beforeListenerInstallation: ResourcePackSettings? = null
    private var currentReadFailure: RuntimeException? = null
    var currentReads = 0
        private set

    val currentReadEntered = CountDownLatch(1)
    val releaseCurrentRead = CountDownLatch(1)

    override fun register(
        definition: ConfigDefinition<ResourcePackSettings>,
        app: String,
        environment: String,
        mode: ConfigStartupMode,
    ): ConfigRegistrationResult = registrationResult

    override fun current(definition: ConfigDefinition<ResourcePackSettings>): ResourcePackSettings {
        currentReads += 1
        currentReadFailure?.let { throw it }
        val captured = current
        if (pauseCurrentRead.compareAndSet(true, false)) {
            currentReadEntered.countDown()
            releaseCurrentRead.await(1, TimeUnit.SECONDS)
        }
        return captured ?: throw ConfigDefinitionNotReadyException(definition)
    }

    override fun onChange(
        definition: ConfigDefinition<ResourcePackSettings>,
        listener: () -> Unit,
    ) {
        beforeListenerInstallation?.let { current = it }
        this.listener = listener
        duringSubscription?.let {
            current = it
            listener()
        }
    }

    fun pauseNextCurrentRead() {
        pauseCurrentRead.set(true)
    }

    fun publishDuringSubscription(settings: ResourcePackSettings) {
        duringSubscription = settings
    }

    fun becomeAvailableBeforeListenerInstallation(settings: ResourcePackSettings) {
        beforeListenerInstallation = settings
    }

    fun failCurrentRead(failure: RuntimeException) {
        currentReadFailure = failure
    }

    fun captureChange(settings: ResourcePackSettings): () -> Unit {
        current = settings
        return checkNotNull(listener)
    }

    fun publishChange(settings: ResourcePackSettings) {
        current = settings
        checkNotNull(listener).invoke()
    }
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
    var registrations = 0
    var registeredDefinition: ConfigDefinition<ResourcePackSettings>? = null
    var observedDefinition: ConfigDefinition<ResourcePackSettings>? = null
    var currentReads = 0
    var subscribed = false
    var listenerClosed = false
    private var listener: ((ResourcePackSettings) -> Unit)? = null

    override fun register(
        definition: ConfigDefinition<ResourcePackSettings>,
        app: String,
        environment: String,
        mode: ConfigStartupMode,
    ): ConfigRegistrationResult {
        registrations += 1
        registeredDefinition = definition
        registration = Registration(app, environment, mode)
        return result
    }

    private fun current(): ResourcePackSettings {
        currentReads += 1
        return checkNotNull(current)
    }

    override fun onChange(
        definition: ConfigDefinition<ResourcePackSettings>,
        listener: (ResourcePackSettings) -> Unit,
    ): ResourcePackConfigSubscription {
        observedDefinition = definition
        subscribed = true
        this.listener = listener
        return object : ResourcePackConfigSubscription {
            override fun deliverLatestIfAvailable() {
                currentReads += 1
                current?.let(listener)
            }

            override fun close() {
                listenerClosed = true
                this@FakeConfigGateway.listener = null
            }
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
