package gg.grounds.resourcepacks.velocity

import gg.grounds.config.ConfigDefinition
import gg.grounds.resourcepacks.client.PackSetSource
import gg.grounds.resourcepacks.contract.PackSetChannel
import java.net.URI

data class ResourcePackSourceSettings(
    val baseUrl: String = "https://cdn.grounds.gg",
    val packSet: String = "grounds-global",
    val channel: String = "stable",
)

data class ResourcePackSettings(
    val schemaVersion: Int = 1,
    val enabled: Boolean = true,
    val source: ResourcePackSourceSettings = ResourcePackSourceSettings(),
    val required: Boolean = true,
    val prompt: String = "Grounds benötigt seine Resourcepacks.",
) {
    fun toClientSource(): PackSetSource =
        PackSetSource(
            URI(source.baseUrl),
            source.packSet,
            when (source.channel) {
                "stable" -> PackSetChannel.STABLE
                "edge" -> PackSetChannel.EDGE
                else ->
                    throw IllegalArgumentException("Unsupported PackSet channel: ${source.channel}")
            },
        )
}

object ResourcePackSettingsDefinition :
    ConfigDefinition<ResourcePackSettings>(
        namespace = "resourcepacks",
        key = "global",
        type = ResourcePackSettings::class.java,
        defaultValue = ResourcePackSettings(),
    )
