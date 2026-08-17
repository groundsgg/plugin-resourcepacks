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
    // Break caught: an unknown operator value must not fall through to a default request.
    @Test
    fun `missing settings sends nothing`() {
        val fallbackSettings = settings()
        var sends = 0
        val coordinator =
            ResourcePackCoordinator(
                { null },
                { readyState(fallbackSettings, snapshot(fallbackSettings)) },
                OnlinePlayerView { emptyList() },
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
                players = OnlinePlayerView { emptyList() },
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

    // Break caught: the same client state notification can otherwise resend an identical offer.
    @Test
    fun `same fingerprint is suppressed and changed snapshot resends all online players once`() {
        val settings = settings()
        var state = readyState(settings, snapshot(settings, sequence = 1))
        val first = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val second = player("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val sent = mutableListOf<Pair<UUID, UUID>>()
        val coordinator = coordinator({ settings }, { state }, listOf(first, second), sent)

        coordinator.onSnapshot(state)
        coordinator.onSnapshot(state)
        state = readyState(settings, snapshot(settings, sequence = 2))
        coordinator.onSnapshot(state)
        coordinator.onSnapshot(state)

        assertEquals(
            listOf(first.uniqueId, second.uniqueId, first.uniqueId, second.uniqueId),
            sent.map { it.first },
        )
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
                OnlinePlayerView {
                    playerViewEntered.countDown()
                    releasePlayerView.await(1, TimeUnit.SECONDS)
                    listOf(player)
                },
                PackSender { _, _ -> sends.incrementAndGet() },
                VelocityPackRequestFactory(),
            )
        coordinator.onLogin(player)
        state = readyState(settings, snapshot(settings, sequence = 2))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val snapshot = executor.submit { coordinator.onSnapshot(state) }
            assertTrue(playerViewEntered.await(1, TimeUnit.SECONDS))
            val forget =
                executor.submit {
                    coordinator.forget(player.uniqueId)
                    forgetCompleted.countDown()
                }

            assertEquals(false, forgetCompleted.await(150, TimeUnit.MILLISECONDS))
            releasePlayerView.countDown()
            snapshot.get(1, TimeUnit.SECONDS)
            forget.get(1, TimeUnit.SECONDS)
            coordinator.onLogin(player)
        } finally {
            releasePlayerView.countDown()
            executor.shutdownNow()
        }

        assertEquals(3, sends.get())
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
                OnlinePlayerView { emptyList() },
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
                OnlinePlayerView { emptyList() },
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

    // Break caught: successful-send attribution can outlive disabled delivery or plugin shutdown.
    @Test
    fun `target attribution clears when delivery disables and when coordinator closes`() {
        var settings = settings()
        val state = readyState(settings, snapshot(settings))
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val packId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                OnlinePlayerView { listOf(player) },
                PackSender { _, _ -> },
                VelocityPackRequestFactory(),
            )
        coordinator.onLogin(player)

        settings = settings.copy(enabled = false)
        coordinator.onSettingsChanged(settings)
        assertNull(coordinator.targetId(player.uniqueId, packId))

        settings = settings.copy(enabled = true)
        coordinator.onSettingsChanged(settings)
        assertEquals("v1.0.1", coordinator.targetId(player.uniqueId, packId))
        coordinator.clear()

        assertNull(coordinator.targetId(player.uniqueId, packId))
    }

    // Break caught: one stale player throwing during snapshot fanout can starve every later online
    // player and can be incorrectly marked as delivered.
    @Test
    fun `snapshot fanout isolates a failed player and retries only that player`() {
        val settings = settings()
        val state = readyState(settings, snapshot(settings))
        val failed = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val healthy = player("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val attempts = mutableListOf<UUID>()
        val coordinator =
            ResourcePackCoordinator(
                { settings },
                { state },
                OnlinePlayerView { listOf(failed, healthy) },
                PackSender { player, _ ->
                    attempts += player.uniqueId
                    if (player.uniqueId == failed.uniqueId) error("stale player")
                },
                VelocityPackRequestFactory(),
            )

        coordinator.onSnapshot(state)
        coordinator.onSnapshot(state)

        assertEquals(listOf(failed.uniqueId, healthy.uniqueId, failed.uniqueId), attempts)
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
                OnlinePlayerView { emptyList() },
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
                OnlinePlayerView { listOf(player) },
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

        assertEquals(0, sent.get())
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
                OnlinePlayerView { listOf(player) },
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

        assertEquals(
            listOf(true to "first", true to "second", false to "second", false to "second"),
            requests,
        )
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
            OnlinePlayerView { online },
            PackSender { player, request ->
                sent += player.uniqueId to request.packs().first().id()
            },
            VelocityPackRequestFactory(),
        )
}
