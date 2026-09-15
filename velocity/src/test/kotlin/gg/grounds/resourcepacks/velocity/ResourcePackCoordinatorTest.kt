package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.proxy.Player
import gg.grounds.resourcepacks.client.PackSetClientState
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourcePackCoordinatorTest {
    @Test
    fun `owned snapshot expiry removes prior sent attribution before session is lost`() {
        val configured = settings()
        var state = readyState(configured, snapshot(configured))
        val online = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        var sends = 0
        val coordinator =
            ResourcePackCoordinator(
                { configured },
                { state },
                PackSender { _, _ -> sends++ },
                VelocityPackRequestFactory(),
            )
        coordinator.onLogin(online)
        state = state.copy(current = null)
        val pending = coordinator.newInitialSession(online)
        assertEquals(InitialPackDelivery.WAITING_FOR_SNAPSHOT, coordinator.onLogin(online, pending))

        assertTrue(coordinator.cancelInitial(online.uniqueId, pending))

        assertNull(coordinator.targetId(online.uniqueId, resolvedPack().uuid))
        state = readyState(configured, snapshot(configured))
        coordinator.onLogin(online)
        assertEquals(2, sends)
    }

    @Test
    fun `failed ready replacement cannot leave predecessor pending for late snapshot`() {
        val configured = settings()
        var state = readyState(configured, snapshot(configured)).copy(current = null)
        val predecessor = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val replacement = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        var failNextSend = true
        val sent = mutableListOf<Player>()
        val coordinator =
            ResourcePackCoordinator(
                settings = { configured },
                clientState = { state },
                sender =
                    PackSender { player, _ ->
                        if (failNextSend) {
                            failNextSend = false
                            error("replacement send failed")
                        }
                        sent += player
                    },
                requestFactory = VelocityPackRequestFactory(),
            )
        assertEquals(InitialPackDelivery.WAITING_FOR_SNAPSHOT, coordinator.onLogin(predecessor))
        state = readyState(configured, snapshot(configured))
        val replacementSession = coordinator.newInitialSession(replacement)
        assertFailsWith<IllegalStateException> {
            coordinator.onLogin(replacement, replacementSession)
        }
        coordinator.forget(replacement.uniqueId, replacementSession)

        coordinator.onSnapshot(state)

        assertEquals(emptyList(), sent)
    }

    // Break caught: an unknown operator value must not fall through to a default request.
    @Test
    fun `missing settings sends nothing`() {
        val fallbackSettings = settings()
        var sends = 0
        val coordinator =
            ResourcePackCoordinator(
                { null },
                { readyState(fallbackSettings, snapshot(fallbackSettings)) },
                PackSender { _, _ -> sends += 1 },
                VelocityPackRequestFactory(),
            )

        coordinator.onLogin(player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))

        assertEquals(0, sends)
    }

    // Break caught: login dispatch can accidentally send twice or perform external refresh work.
    @Test
    fun `login reads each in-memory supplier once and sends one request`() {
        val settings = settings()
        val state = readyState(settings, snapshot(settings))
        var settingsReads = 0
        var stateReads = 0
        val sent = mutableListOf<UUID>()
        val coordinator =
            ResourcePackCoordinator(
                settings = {
                    settingsReads += 1
                    settings
                },
                clientState = {
                    stateReads += 1
                    state
                },
                sender = PackSender { player, _ -> sent += player.uniqueId },
                requestFactory = VelocityPackRequestFactory(),
            )
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

        coordinator.onLogin(player)
        coordinator.onLogin(player)

        assertEquals(listOf(player.uniqueId), sent)
        assertEquals(2, settingsReads)
        assertEquals(2, stateReads)
    }

    // Break caught: diagnostics must only claim a request was sent after Velocity accepts it.
    @Test
    fun `successful send reports the prepared target and fingerprint`() {
        val settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        var observed: PreparedPackRequest? = null
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { _, _ -> },
                VelocityPackRequestFactory(),
                ResourcePackDeliveryObserver { _, prepared -> observed = prepared },
            )

        coordinator.onLogin(player)

        assertEquals("v1.0.1", observed?.targetId)
        assertEquals(
            VelocityPackRequestFactory().fingerprint(settings, state),
            observed?.fingerprint,
        )
    }

    // Break caught: a fast client response can arrive before the configuration waiter knows which
    // pack IDs must finish, leaving the login suspended forever.
    @Test
    fun `delivery expectation is registered before sending the request`() {
        val settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        var expected = false
        val coordinator =
            ResourcePackCoordinator(
                settings = { settings },
                clientState = { state },
                sender = PackSender { _, _ -> assertTrue(expected) },
                requestFactory = VelocityPackRequestFactory(),
                deliveryExpectation =
                    ResourcePackDeliveryExpectation { _, prepared ->
                        assertEquals(
                            state.current!!.packs.map { it.uuid }.toSet(),
                            prepared.packIds,
                        )
                        expected = true
                        {}
                    },
            )

        coordinator.onLogin(player)

        assertTrue(expected)
    }

    // Break caught: the same client state notification can otherwise resend an identical offer.
    @Test
    fun `snapshot changes wait for each players next server switch`() {
        val settings = settings()
        var state = readyState(settings, snapshot(settings, sequence = 1))
        val first = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val second = player("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val sent = mutableListOf<Pair<UUID, UUID>>()
        val coordinator = coordinator({ settings }, { state }, listOf(first, second), sent)

        coordinator.onLogin(first)
        coordinator.onLogin(second)
        state = readyState(settings, snapshot(settings, sequence = 2))
        coordinator.onSnapshot(state)

        assertEquals(listOf(first.uniqueId, second.uniqueId), sent.map { it.first })
        coordinator.onServerSwitch(first)
        coordinator.onServerSwitch(first)
        assertEquals(listOf(first.uniqueId, second.uniqueId, first.uniqueId), sent.map { it.first })
        coordinator.onServerSwitch(second)
        assertEquals(
            listOf(first.uniqueId, second.uniqueId, first.uniqueId, second.uniqueId),
            sent.map { it.first },
        )
    }

    @Test
    fun `disabled initial delivery waits until a later server switch after enabling`() {
        var configured = settings().copy(enabled = false)
        val state = readyState(configured, snapshot(configured))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val sent = mutableListOf<Pair<UUID, UUID>>()
        val coordinator = coordinator({ configured }, { state }, emptyList(), sent)

        assertEquals(InitialPackDelivery.NO_REQUEST, coordinator.onLogin(player))
        configured = configured.copy(enabled = true)
        coordinator.onSettingsChanged(configured)
        assertEquals(0, sent.size)
        coordinator.onServerSwitch(player)

        assertEquals(1, sent.size)
    }

    @Test
    fun `server switch before initial configuration sends nothing`() {
        val configured = settings()
        val state = readyState(configured, snapshot(configured))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val sent = mutableListOf<Pair<UUID, UUID>>()
        val coordinator = coordinator({ configured }, { state }, emptyList(), sent)

        coordinator.onServerSwitch(player)

        assertEquals(0, sent.size)
    }

    // Break caught: a stale per-player fingerprint surviving disconnect suppresses the next
    // session.
    @Test
    fun `disconnect cleanup allows the same snapshot on the next session`() {
        val settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val sent = mutableListOf<Pair<UUID, UUID>>()
        val coordinator = coordinator({ settings }, { state }, emptyList(), sent)

        coordinator.onLogin(player)
        coordinator.forget(player.uniqueId)
        coordinator.onLogin(player)

        assertEquals(2, sent.size)
    }

    // Break caught: a snapshot retaining a disconnecting player can reinsert its sent fingerprint.
    @Test
    fun `disconnect cleanup cannot be overtaken by a stale online player snapshot`() {
        val settings = settings()
        var state = readyState(settings, snapshot(settings, sequence = 1))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val playerViewEntered = CountDownLatch(1)
        val releasePlayerView = CountDownLatch(1)
        val forgetCompleted = CountDownLatch(1)
        val sends = AtomicInteger()
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { _, _ -> sends.incrementAndGet() },
                VelocityPackRequestFactory(),
            )
        coordinator.onLogin(player)
        state = readyState(settings, snapshot(settings, sequence = 2))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val snapshot = executor.submit { coordinator.onSnapshot(state) }
            assertEquals(false, playerViewEntered.await(1, TimeUnit.SECONDS))
            val forget =
                executor.submit {
                    coordinator.forget(player.uniqueId)
                    forgetCompleted.countDown()
                }

            assertEquals(true, forgetCompleted.await(150, TimeUnit.MILLISECONDS))
            releasePlayerView.countDown()
            snapshot.get(1, TimeUnit.SECONDS)
            forget.get(1, TimeUnit.SECONDS)
            coordinator.onLogin(player)
        } finally {
            releasePlayerView.countDown()
            executor.shutdownNow()
        }

        assertEquals(2, sends.get())
    }

    // Break caught: recording before the Velocity call returns suppresses retry after send failure.
    @Test
    fun `send failure does not mark a fingerprint as sent`() {
        val settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        var attempts = 0
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { _, _ ->
                    attempts += 1
                    if (attempts == 1) error("send failed")
                },
                VelocityPackRequestFactory(),
            )

        assertFailsWith<IllegalStateException> { coordinator.onLogin(player) }
        coordinator.onLogin(player)

        assertEquals(2, attempts)
    }

    // Break caught: a synchronous Velocity status emitted by sendResourcePacks can arrive before
    // the coordinator records ownership and disappear from diagnostics and metrics.
    @Test
    fun `pack ownership is visible while the request is being sent`() {
        val settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val packId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        var ownedDuringSend = false
        lateinit var coordinator: ResourcePackCoordinator
        coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { sentPlayer, _ ->
                    ownedDuringSend = coordinator.ownsPack(sentPlayer.uniqueId, packId)
                },
                VelocityPackRequestFactory(),
            )

        coordinator.onLogin(player)

        assertTrue(ownedDuringSend)
    }

    // Break caught: provisional ownership can survive a non-Exception send failure and make a
    // pack that was never delivered look owned by the coordinator.
    @Test
    fun `non exception send failure clears provisional attribution`() {
        val settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val packId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { _, _ -> throw AssertionError("send failed") },
                VelocityPackRequestFactory(),
            )

        assertFailsWith<AssertionError> { coordinator.onLogin(player) }

        assertNull(coordinator.targetId(player.uniqueId, packId))
    }

    // Break caught: target attribution can be recorded before a failed send or survive the
    // player's disconnect indefinitely.
    @Test
    fun `target attribution starts after successful send and clears on disconnect`() {
        val settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val packId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        var fail = true
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { _, _ -> if (fail) error("send failed") },
                VelocityPackRequestFactory(),
            )

        assertFailsWith<IllegalStateException> { coordinator.onLogin(player) }
        assertNull(coordinator.targetId(player.uniqueId, packId))
        fail = false
        coordinator.onLogin(player)
        assertEquals("v1.0.1", coordinator.targetId(player.uniqueId, packId))
        coordinator.forget(player.uniqueId)

        assertNull(coordinator.targetId(player.uniqueId, packId))
    }

    // Break caught: an ambiguous same-UUID association can survive session cleanup and prevent a
    // later unambiguous session from reporting its exact target.
    @Test
    fun `disconnect clears same uuid ambiguity for the next session`() {
        val settings = settings()
        var state = readyState(settings, snapshot(settings, sequence = 1))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val packId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { _, _ -> },
                VelocityPackRequestFactory(),
            )
        coordinator.onLogin(player)
        state = readyState(settings, snapshot(settings, sequence = 2))
        coordinator.onServerSwitch(player)
        assertNull(coordinator.targetId(player.uniqueId, packId))

        coordinator.forget(player.uniqueId)
        coordinator.onLogin(player)

        assertEquals("v1.0.2", coordinator.targetId(player.uniqueId, packId))
    }

    // Break caught: disabling must retain ownership for already-requested terminal statuses.
    @Test
    fun `target attribution survives disabling delivery and clears when coordinator closes`() {
        var settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val packId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { _, _ -> },
                VelocityPackRequestFactory(),
            )
        coordinator.onLogin(player)

        settings = settings.copy(enabled = false)
        coordinator.onSettingsChanged(settings)
        assertEquals("v1.0.1", coordinator.targetId(player.uniqueId, packId))

        settings = settings.copy(enabled = true)
        coordinator.onSettingsChanged(settings)
        assertEquals("v1.0.1", coordinator.targetId(player.uniqueId, packId))
        coordinator.clear()

        assertNull(coordinator.targetId(player.uniqueId, packId))
    }

    // Break caught: one pending player's failure must not starve other pending initial players.
    @Test
    fun `pending snapshot delivery isolates failed send and retries only that player`() {
        val settings = settings()
        var state = readyState(settings, snapshot(settings)).copy(current = null)
        val failed = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val healthy = player("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val attempts = mutableListOf<UUID>()
        var failFirst = true
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { player, _ ->
                    attempts += player.uniqueId
                    if (player.uniqueId == failed.uniqueId && failFirst) {
                        failFirst = false
                        error("first pending send failed")
                    }
                },
                VelocityPackRequestFactory(),
            )
        coordinator.onLogin(failed)
        coordinator.onLogin(healthy)
        state = readyState(settings, snapshot(settings))
        coordinator.onSnapshot(state)
        assertEquals(setOf(failed.uniqueId, healthy.uniqueId), attempts.toSet())
        assertEquals(2, attempts.size)
        coordinator.onSnapshot(state)
        coordinator.onSnapshot(state)

        assertEquals(3, attempts.size)
        assertEquals(failed.uniqueId, attempts.last())
    }

    // Break caught: concurrent login/snapshot paths can race and send the same offer twice.
    @Test
    fun `concurrent dispatch serializes duplicate suppression per player`() {
        val settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val calls = AtomicInteger()
        val bothSenders = CountDownLatch(2)
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { _, _ ->
                    calls.incrementAndGet()
                    bothSenders.countDown()
                    bothSenders.await(250, TimeUnit.MILLISECONDS)
                },
                VelocityPackRequestFactory(),
            )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { coordinator.onLogin(player) }
            val second = executor.submit { coordinator.onLogin(player) }
            first.get(1, TimeUnit.SECONDS)
            second.get(1, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, calls.get())
    }

    // Break caught: disabling delivery can complete while an old dispatch has captured settings.
    @Test
    fun `settings transition cannot overtake an in flight send`() {
        var settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val stateReadEntered = CountDownLatch(1)
        val releaseStateRead = CountDownLatch(1)
        val transitionCompleted = CountDownLatch(1)
        val stateReads = AtomicInteger()
        val sent = AtomicInteger()
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                {
                    if (stateReads.incrementAndGet() == 1) {
                        stateReadEntered.countDown()
                        releaseStateRead.await(1, TimeUnit.SECONDS)
                    }
                    state
                },
                PackSender { _, _ -> sent.incrementAndGet() },
                VelocityPackRequestFactory(),
            )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val login = executor.submit { coordinator.onLogin(player) }
            assertTrue(stateReadEntered.await(1, TimeUnit.SECONDS))
            settings = settings.copy(enabled = false)
            val transition =
                executor.submit {
                    coordinator.onSettingsChanged(settings)
                    transitionCompleted.countDown()
                }

            assertEquals(false, transitionCompleted.await(150, TimeUnit.MILLISECONDS))
            releaseStateRead.countDown()
            login.get(1, TimeUnit.SECONDS)
            transition.get(1, TimeUnit.SECONDS)
            coordinator.onLogin(player)
        } finally {
            releaseStateRead.countDown()
            executor.shutdownNow()
        }

        assertEquals(1, sent.get())
    }

    // Break caught: prompt/required/enabled changes can leave players with old offer semantics.
    @Test
    fun `prompt required and enabled changes reconcile the current snapshot`() {
        var settings = settings(prompt = "first", required = true)
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val requests = mutableListOf<Pair<Boolean, String>>()
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                PackSender { _, request ->
                    requests +=
                        request.required() to
                            (request.prompt() as net.kyori.adventure.text.TextComponent).content()
                },
                VelocityPackRequestFactory(),
            )

        coordinator.onLogin(player)
        settings = settings.copy(prompt = "second")
        coordinator.onSettingsChanged(settings)
        settings = settings.copy(required = false)
        coordinator.onSettingsChanged(settings)
        settings = settings.copy(enabled = false)
        coordinator.onSettingsChanged(settings)
        settings = settings.copy(enabled = true)
        coordinator.onSettingsChanged(settings)

        assertEquals(listOf(true to "first"), requests)
    }

    // Break caught: a retained old-source fallback may leak during source reconciliation.
    @Test
    fun `source change never sends old source fallback`() {
        var settings = settings(packSet = "old")
        val oldSnapshot = snapshot(settings)
        var state = readyState(settings, oldSnapshot)
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val sent = mutableListOf<Pair<UUID, UUID>>()
        val coordinator = coordinator({ settings }, { state }, listOf(player), sent)
        coordinator.onLogin(player)

        settings = settings(packSet = "new")
        state = degradedState(settings, oldSnapshot)
        coordinator.onSettingsChanged(settings)

        assertEquals(1, sent.size)
    }

    private fun coordinator(
        settings: () -> ResourcePackSettings?,
        state: () -> PackSetClientState,
        online: Collection<Player>,
        sent: MutableList<Pair<UUID, UUID>>,
    ) =
        ResourcePackCoordinator(
            settings,
            state,
            PackSender { player, request ->
                sent += player.uniqueId to request.packs().first().id()
            },
            VelocityPackRequestFactory(),
        )
}
