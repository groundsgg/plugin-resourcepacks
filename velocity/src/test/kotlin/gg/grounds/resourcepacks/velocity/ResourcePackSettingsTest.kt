package gg.grounds.resourcepacks.velocity

import gg.grounds.resourcepacks.contract.PackSetChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

class ResourcePackSettingsTest {
    @Test
    fun `defaults provide the stable Grounds pack set`() {
        val settings = ResourcePackSettings()

        assertEquals(1, settings.schemaVersion)
        assertEquals(true, settings.enabled)
        assertEquals("https://cdn.grounds.gg", settings.source.baseUrl)
        assertEquals("grounds-global", settings.source.packSet)
        assertEquals("stable", settings.source.channel)
        assertEquals(true, settings.required)
        assertEquals("Grounds benötigt seine Resourcepacks.", settings.prompt)
    }

    // Break caught: plugin-config deserializes consumer-owned classes without Kotlin metadata
    // when Velocity isolates plugin class loaders.
    @Test
    fun `plain Jackson applies the configured nested edge channel`() {
        val settings =
            ObjectMapper()
                .readValue("""{"source":{"channel":"edge"}}""", ResourcePackSettings::class.java)

        assertEquals("edge", settings.source.channel)
    }

    @Test
    fun `definition identifies the global resourcepacks document`() {
        assertEquals("resourcepacks", ResourcePackSettingsDefinition.namespace)
        assertEquals("global", ResourcePackSettingsDefinition.key)
        assertEquals(ResourcePackSettings::class.java, ResourcePackSettingsDefinition.type)
        assertEquals(ResourcePackSettings(), ResourcePackSettingsDefinition.defaultValue)
    }

    // Break caught: the stage bootstrap can silently select Stable, or an unrelated setting can
    // drift while deriving the Edge default.
    @Test
    fun `bootstrap channel creates an edge definition with only the source channel changed`() {
        val definition = resourcePackSettingsDefinition(PackSetChannel.EDGE)

        assertEquals("resourcepacks", definition.namespace)
        assertEquals("global", definition.key)
        assertEquals(ResourcePackSettings::class.java, definition.type)
        assertEquals(
            ResourcePackSettings(
                source =
                    ResourcePackSourceSettings(
                        baseUrl = "https://cdn.grounds.gg",
                        packSet = "grounds-global",
                        channel = "edge",
                    )
            ),
            definition.defaultValue,
        )
    }

    // Break caught: an absent bootstrap variable can prevent normal Stable deployments from
    // registering their approved default.
    @Test
    fun `bootstrap channel defaults to stable when absent`() {
        assertEquals(PackSetChannel.STABLE, bootstrapPackSetChannel(emptyMap()))
    }

    // Break caught: the deployment value can be normalized instead of enforcing the exact
    // configuration contract.
    @Test
    fun `bootstrap channel accepts only exact stable and edge values`() {
        assertEquals(
            PackSetChannel.STABLE,
            bootstrapPackSetChannel(mapOf("RESOURCE_PACK_DEFAULT_CHANNEL" to "stable")),
        )
        assertEquals(
            PackSetChannel.EDGE,
            bootstrapPackSetChannel(mapOf("RESOURCE_PACK_DEFAULT_CHANNEL" to "edge")),
        )

        listOf("", " Edge", "EDGE", "preview").forEach { value ->
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    bootstrapPackSetChannel(mapOf("RESOURCE_PACK_DEFAULT_CHANNEL" to value))
                }
            assertTrue(failure.message.orEmpty().contains("Invalid RESOURCE_PACK_DEFAULT_CHANNEL"))
        }
    }

    @Test
    fun `environment accepts a non-blank deployment value`() {
        assertEquals(
            ResourcePackEnvironment("stage"),
            ResourcePackEnvironment.from(mapOf("GROUNDS_ENVIRONMENT" to "stage")),
        )
    }

    @Test
    fun `environment rejects missing or unsafe deployment values`() {
        listOf(null, "", " ", " stage", "stage ", "st age", "stage/prod", "stage\\prod").forEach {
            value ->
            assertFailsWith<IllegalArgumentException> {
                ResourcePackEnvironment.from(
                    value?.let { mapOf("GROUNDS_ENVIRONMENT" to it) } ?: emptyMap()
                )
            }
        }
    }

    @Test
    fun `source conversion maps stable and edge channels`() {
        assertEquals(PackSetChannel.STABLE, ResourcePackSettings().toClientSource().channel)
        assertEquals(
            PackSetChannel.EDGE,
            ResourcePackSettings(source = ResourcePackSourceSettings(channel = "edge"))
                .toClientSource()
                .channel,
        )
    }

    @Test
    fun `source conversion rejects channels outside the exact contract`() {
        assertFailsWith<IllegalArgumentException> {
            ResourcePackSettings(source = ResourcePackSourceSettings(channel = "Stable"))
                .toClientSource()
        }
    }

    @Test
    fun `source conversion delegates base URI safety to the client source`() {
        assertFailsWith<IllegalArgumentException> {
            ResourcePackSettings(
                    source = ResourcePackSourceSettings(baseUrl = "http://cdn.grounds.gg")
                )
                .toClientSource()
        }
    }
}
