package gg.grounds.resourcepacks.velocity

import gg.grounds.resourcepacks.client.PackSetClientState
import gg.grounds.resourcepacks.client.PackSetClientStatus
import gg.grounds.resourcepacks.client.PackSetSnapshot
import gg.grounds.resourcepacks.client.ResolvedPack
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component

class VelocityPackRequestFactory {
    fun request(settings: ResourcePackSettings, state: PackSetClientState): ResourcePackRequest? =
        prepare(settings, state)?.request

    fun fingerprint(settings: ResourcePackSettings, state: PackSetClientState): String? =
        prepare(settings, state)?.fingerprint

    internal fun prepare(
        settings: ResourcePackSettings,
        state: PackSetClientState,
    ): PreparedPackRequest? {
        if (!settings.enabled) return null
        val snapshot = snapshot(settings, state) ?: return null
        val infos =
            snapshot.packs.sortedBy(ResolvedPack::order).map { pack ->
                ResourcePackInfo.resourcePackInfo(pack.uuid, pack.uri, pack.sha1)
            }
        return PreparedPackRequest(
            ResourcePackRequest.resourcePackRequest()
                .packs(infos)
                .required(settings.required)
                .prompt(Component.text(settings.prompt))
                .build(),
            "${snapshot.source.cacheKey}:${snapshot.fingerprint}:${settings.required}:${settings.prompt}",
            snapshot.channel.target.id,
            snapshot.packs.mapTo(linkedSetOf(), ResolvedPack::uuid),
        )
    }

    private fun snapshot(
        settings: ResourcePackSettings,
        state: PackSetClientState,
    ): PackSetSnapshot? {
        val source = settings.toClientSource()
        return when {
            state.current?.source == source && state.status == PackSetClientStatus.READY ->
                state.current
            state.degradedFallback?.source == source &&
                state.status == PackSetClientStatus.DEGRADED -> state.degradedFallback
            else -> null
        }
    }
}

internal data class PreparedPackRequest(
    val request: ResourcePackRequest,
    val fingerprint: String,
    val targetId: String,
    val packIds: Set<java.util.UUID>,
)
