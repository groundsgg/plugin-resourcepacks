package gg.grounds.resourcepacks.velocity

import gg.grounds.proxy.api.PlayerHudAppearance
import gg.grounds.proxy.api.PlayerHudAppearanceQuery
import gg.grounds.proxy.api.ProxyServiceRegistry
import java.util.UUID

internal object HudAppearanceEnvironment {
    fun from(environment: Map<String, String>): Set<String> {
        val hashes = environment["HUD_FONT_PACK_SHA1S"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        require(hashes.all { it.matches(Regex("[0-9a-f]{40}")) }) { "HUD_FONT_PACK_SHA1S must contain lowercase SHA-1 hashes" }
        return hashes.toSet()
    }
    fun eligible(protocol: Int?, bedrock: Boolean?): Boolean = protocol == 777 && bedrock == false
}

internal class ResourcePackPlayerHudAppearanceQuery(
    private val tracker: PlayerChatAppearanceTracker,
    private val hashes: Set<String>,
    private val protocol: (UUID) -> Int?,
    private val platform: ChatPlatformLookup,
    private val appliedHashes: (UUID) -> Set<String>,
) : PlayerHudAppearanceQuery {
    override fun appearanceOf(playerId: UUID): PlayerHudAppearance = PlayerHudAppearance(
        HudAppearanceEnvironment.eligible(protocol(playerId), platform.isBedrock(playerId)) &&
            tracker.chatFontReady(playerId) && appliedHashes(playerId).any { it in hashes }
    )
}

internal interface PlayerHudAppearanceRegistry {
    fun register(query: PlayerHudAppearanceQuery)
    fun unregister()
}
internal object ProxyPlayerHudAppearanceRegistry : PlayerHudAppearanceRegistry {
    override fun register(query: PlayerHudAppearanceQuery) { ProxyServiceRegistry.register(PlayerHudAppearanceQuery::class.java, query) }
    override fun unregister() { ProxyServiceRegistry.unregister(PlayerHudAppearanceQuery::class.java) }
}
internal object NoopPlayerHudAppearanceRegistry : PlayerHudAppearanceRegistry {
    override fun register(query: PlayerHudAppearanceQuery) = Unit
    override fun unregister() = Unit
}
