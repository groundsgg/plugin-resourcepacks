package gg.grounds.resourcepacks.velocity

import gg.grounds.config.ConfigDefinition
import gg.grounds.resourcepacks.client.PackSetSource
import gg.grounds.resourcepacks.contract.PackSetChannel
import java.net.URI

data class ResourcePackSourceSettings(
    var baseUrl: String = "https://cdn.grounds.gg",
    var packSet: String = "grounds-global",
    var channel: String = "stable",
    var pin: ResourcePackSourcePinSettings? = null,
)

data class ResourcePackSourcePinSettings(var type: String = "release", var id: String = "")

data class ResourcePackSettings(
    var schemaVersion: Int = 1,
    var enabled: Boolean = true,
    var source: ResourcePackSourceSettings = ResourcePackSourceSettings(),
    var required: Boolean = true,
    var prompt: String = "Grounds benötigt seine Resourcepacks.",
) {
    fun toClientSource(): PackSetSource {
        val channel =
            when (source.channel) {
                "stable" -> PackSetChannel.STABLE
                "edge" -> PackSetChannel.EDGE
                else ->
                    throw IllegalArgumentException("Unsupported PackSet channel: ${source.channel}")
            }
        val pin = source.pin ?: return PackSetSource(URI(source.baseUrl), source.packSet, channel)
        require(pin.type == "release") { "Unsupported PackSet pin type" }
        return PackSetSource.release(URI(source.baseUrl), source.packSet, pin.id)
    }
}

object ResourcePackSettingsDefinition :
    ConfigDefinition<ResourcePackSettings>(
        namespace = "resourcepacks",
        key = "global",
        type = ResourcePackSettings::class.java,
        defaultValue = ResourcePackSettings(),
    )

internal fun resourcePackSettingsDefinition(
    bootstrapChannel: PackSetChannel
): ConfigDefinition<ResourcePackSettings> =
    object :
        ConfigDefinition<ResourcePackSettings>(
            namespace = "resourcepacks",
            key = "global",
            type = ResourcePackSettings::class.java,
            defaultValue =
                ResourcePackSettings(
                    source =
                        ResourcePackSourceSettings(
                            channel =
                                when (bootstrapChannel) {
                                    PackSetChannel.STABLE -> "stable"
                                    PackSetChannel.EDGE -> "edge"
                                }
                        )
                ),
        ) {}

internal fun bootstrapPackSetChannel(environment: Map<String, String>): PackSetChannel =
    when (environment["RESOURCE_PACK_DEFAULT_CHANNEL"] ?: "stable") {
        "stable" -> PackSetChannel.STABLE
        "edge" -> PackSetChannel.EDGE
        else -> throw IllegalArgumentException("Invalid RESOURCE_PACK_DEFAULT_CHANNEL")
    }
