package gg.grounds.resourcepacks.velocity

import gg.grounds.resourcepacks.client.PackSetClientState
import gg.grounds.resourcepacks.client.PackSetClientStatus
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kyori.adventure.text.TextComponent

class VelocityPackRequestFactoryTest {
    private val factory = VelocityPackRequestFactory()

    // Break caught: sorting by manifest insertion order sends overlays before their dependencies.
    @Test
    fun `request preserves pack order and maps exact Adventure fields`() {
        val settings = settings(required = false, prompt = "Download the Grounds packs?")
        val snapshot =
            snapshot(
                settings,
                sequence = 7,
                packs =
                    listOf(
                        resolvedPack(
                            order = 20,
                            uuid = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                            uri = URI("https://assets.example.test/overlay.zip"),
                            sha1 = "2".repeat(40),
                        ),
                        resolvedPack(
                            order = 10,
                            uuid = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                            uri = URI("https://assets.example.test/base.zip"),
                            sha1 = "1".repeat(40),
                        ),
                    ),
            )
        val request = factory.request(settings, readyState(settings, snapshot))!!

        assertEquals(
            listOf(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
            ),
            request.packs().map { it.id() },
        )
        assertEquals(
            listOf(
                URI("https://assets.example.test/base.zip"),
                URI("https://assets.example.test/overlay.zip"),
            ),
            request.packs().map { it.uri() },
        )
        assertEquals(listOf("1".repeat(40), "2".repeat(40)), request.packs().map { it.hash() })
        assertEquals(false, request.required())
        assertEquals("Download the Grounds packs?", (request.prompt() as TextComponent).content())
    }

    // Break caught: an unavailable or disabled runtime must not offer stale packs.
    @Test
    fun `disabled and unavailable states produce no request`() {
        val enabled = settings()
        val current = snapshot(enabled)

        assertNull(factory.request(enabled.copy(enabled = false), readyState(enabled, current)))
        listOf(
                PackSetClientStatus.STARTING,
                PackSetClientStatus.UNAVAILABLE,
                PackSetClientStatus.CLOSED,
            )
            .forEach { status ->
                assertNull(
                    factory.request(
                        enabled,
                        PackSetClientState(enabled.toClientSource(), current, current, status, null),
                    )
                )
            }
    }

    // Break caught: degraded startup would be unusable if its matching cached snapshot were
    // ignored.
    @Test
    fun `matching source degraded fallback is allowed`() {
        val settings = settings()
        val fallback = snapshot(settings)
        val state =
            PackSetClientState(
                settings.toClientSource(),
                null,
                fallback,
                PackSetClientStatus.DEGRADED,
                "offline",
            )

        assertEquals(
            fallback.packs.single().uuid,
            factory.request(settings, state)!!.packs().single().id(),
        )
    }

    // Break caught: reconfiguration must never send a fallback retained from the old source.
    @Test
    fun `old source fallback is never sent after source change`() {
        val oldSettings = settings(packSet = "old")
        val newSettings = settings(packSet = "new")
        val oldSnapshot = snapshot(oldSettings)
        val state =
            PackSetClientState(
                newSettings.toClientSource(),
                null,
                oldSnapshot,
                PackSetClientStatus.DEGRADED,
                "new source unavailable",
            )

        assertNull(factory.request(newSettings, state))
        assertNull(factory.fingerprint(newSettings, state))
        assertTrue(oldSnapshot.source != newSettings.toClientSource())
    }

    @Test
    fun `pinned release request uses canonical publication target and immutable pack fields`() {
        val settings = settings(pin = ResourcePackSourcePinSettings(id = "v1.2.3"))
        val snapshot = releaseSnapshot(settings)
        val state = readyState(settings, snapshot)

        val prepared = factory.prepare(settings, state)!!

        assertEquals("v1.2.3", prepared.targetId)
        assertEquals(
            listOf(
                UUID.fromString("44591d5b-71f5-5c2a-a5b2-d3ee7be47e53"),
                UUID.fromString("8da7cffe-bb04-55e0-9868-7789ce5de362"),
            ),
            prepared.request.packs().map { it.id() },
        )
        assertEquals(
            listOf(
                URI(
                    "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/grounds-content-pack-v1.2.3.zip"
                ),
                URI(
                    "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/grounds-platform-pack-v1.2.3.zip"
                ),
            ),
            prepared.request.packs().map { it.uri() },
        )
        assertEquals(
            listOf("b".repeat(40), "d".repeat(40)),
            prepared.request.packs().map { it.hash() },
        )
        assertEquals(prepared.fingerprint, factory.fingerprint(settings, state))
        assertNull(
            factory.request(settings(pin = ResourcePackSourcePinSettings(id = "v1.2.4")), state)
        )
    }
}
