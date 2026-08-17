package gg.grounds.resourcepacks.velocity

import gg.grounds.resourcepacks.contract.PackSetChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun `definition identifies the global resourcepacks document`() {
        assertEquals("resourcepacks", ResourcePackSettingsDefinition.namespace)
        assertEquals("global", ResourcePackSettingsDefinition.key)
        assertEquals(ResourcePackSettings::class.java, ResourcePackSettingsDefinition.type)
        assertEquals(ResourcePackSettings(), ResourcePackSettingsDefinition.defaultValue)
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
