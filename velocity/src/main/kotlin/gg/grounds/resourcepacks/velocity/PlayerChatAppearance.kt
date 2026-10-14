package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.network.ProtocolVersion
import gg.grounds.proxy.api.PlayerChatAppearance
import gg.grounds.proxy.api.PlayerChatAppearanceQuery
import gg.grounds.proxy.api.ProxyServiceRegistry
import java.lang.reflect.Method
import java.util.UUID
import org.slf4j.Logger

internal data class ChatAppearanceEnvironment(val fontPackSha1s: Set<String>) {
    companion object {
        private val SHA1 = Regex("[0-9a-f]{40}")

        fun from(environment: Map<String, String>): ChatAppearanceEnvironment {
            val raw = environment["CHAT_FONT_PACK_SHA1S"].orEmpty()
            if (raw.isBlank()) return ChatAppearanceEnvironment(emptySet())
            val values = raw.split(',').map(String::trim)
            require(values.all(SHA1::matches)) {
                "CHAT_FONT_PACK_SHA1S must contain only comma-separated lowercase SHA-1 values"
            }
            return ChatAppearanceEnvironment(values.toSet())
        }
    }
}

internal data class PlayerChatStyleSnapshot(val revision: Long, val ready: Boolean)

internal class PlayerChatAppearanceTracker(private val allowedHashes: Set<String>) {
    private val lock = Any()
    private val players = HashMap<UUID, PlayerState>()
    private var revision = 0L

    fun offered(playerId: UUID, packs: Map<UUID, String?>) {
        synchronized(lock) {
            val state = players.getOrPut(playerId, ::PlayerState)
            state.current = packs
            state.loaded.clear()
            packs.forEach { (packId, hash) ->
                if (hash == null) {
                    state.ambiguous += packId
                } else {
                    val previous = state.seenHashes.putIfAbsent(packId, hash)
                    if (previous != null && previous != hash) state.ambiguous += packId
                }
            }
        }
    }

    fun loaded(playerId: UUID, packId: UUID) {
        synchronized(lock) {
            val state = players[playerId] ?: return@synchronized
            val hash = state.current[packId] ?: return@synchronized
            if (packId !in state.ambiguous && hash in allowedHashes) state.loaded += packId
        }
    }

    fun failed(playerId: UUID, packId: UUID) {
        synchronized(lock) {
            val state = players[playerId] ?: return@synchronized
            if (packId in state.current) state.loaded -= packId
        }
    }

    fun forget(playerId: UUID) {
        synchronized(lock) { players.remove(playerId) }
    }

    fun reset(playerId: UUID) {
        synchronized(lock) { players.remove(playerId) }
    }

    fun disconnectSnapshot(playerId: UUID): PlayerChatStyleSnapshot =
        synchronized(lock) {
            players.remove(playerId)
            revision = nextRevision(revision)
            PlayerChatStyleSnapshot(revision, ready = false)
        }

    fun clear() {
        synchronized(lock) { players.clear() }
    }

    fun chatFontReady(playerId: UUID): Boolean =
        synchronized(lock) { players[playerId]?.loaded?.isNotEmpty() == true }

    fun snapshot(playerId: UUID, eligible: () -> Boolean): PlayerChatStyleSnapshot =
        synchronized(lock) {
            val state = players.getOrPut(playerId, ::PlayerState)
            revision = nextRevision(revision)
            PlayerChatStyleSnapshot(revision, state.loaded.isNotEmpty() && eligible())
        }

    private fun nextRevision(current: Long): Long {
        check(current != Long.MAX_VALUE) { "chat style revision exhausted" }
        return current + 1
    }

    private class PlayerState {
        var current: Map<UUID, String?> = emptyMap()
        val loaded = mutableSetOf<UUID>()
        val seenHashes = mutableMapOf<UUID, String>()
        val ambiguous = mutableSetOf<UUID>()
    }
}

internal fun interface ChatPlatformLookup {
    /** true for Bedrock, false for Java, null when the platform could not be determined. */
    fun isBedrock(playerId: UUID): Boolean?
}

internal class SafeChatPlatformLookup(
    private val lookup: (UUID) -> Boolean,
    private val onFailure: (Exception) -> Unit,
) : ChatPlatformLookup {
    @Volatile private var failed = false

    override fun isBedrock(playerId: UUID): Boolean? {
        if (failed) return null
        return try {
            lookup(playerId)
        } catch (failure: Exception) {
            failed = true
            onFailure(failure)
            null
        }
    }
}

internal object FloodgateChatPlatformLookup {
    private const val API = "org.geysermc.floodgate.api.FloodgateApi"

    fun create(logger: Logger): ChatPlatformLookup =
        try {
            val api = Class.forName(API)
            val getInstance = api.getMethod("getInstance")
            val isFloodgatePlayer = api.getMethod("isFloodgatePlayer", UUID::class.java)
            reflective(logger, getInstance, isFloodgatePlayer)
        } catch (_: ClassNotFoundException) {
            ChatPlatformLookup { false }
        } catch (failure: NoSuchMethodException) {
            logger.warn("Floodgate is installed but {} is not the expected shape", API, failure)
            ChatPlatformLookup { null }
        }

    private fun reflective(
        logger: Logger,
        getInstance: Method,
        isFloodgatePlayer: Method,
    ): ChatPlatformLookup =
        SafeChatPlatformLookup(
            lookup = { playerId ->
                val api =
                    getInstance.invoke(null)
                        ?: throw ReflectiveOperationException(
                            "Floodgate API instance is unavailable"
                        )
                isFloodgatePlayer.invoke(api, playerId) as Boolean
            },
            onFailure = { failure ->
                logger.warn(
                    "Floodgate lookup failed; chat appearance will stay text-only from here on",
                    failure,
                )
            },
        )
}

internal class ResourcePackPlayerChatAppearanceQuery(
    private val protocol: (UUID) -> ProtocolVersion?,
    private val platform: ChatPlatformLookup,
    private val tracker: PlayerChatAppearanceTracker,
) : PlayerChatAppearanceQuery {
    override fun appearanceOf(playerId: UUID): PlayerChatAppearance {
        val protocol = protocol(playerId) ?: return PlayerChatAppearance(false, false)
        if (platform.isBedrock(playerId) != false) return PlayerChatAppearance(false, false)
        return PlayerChatAppearance(
            javaHeadsSupported = protocol >= ProtocolVersion.MINECRAFT_1_21_9,
            chatFontReady = tracker.chatFontReady(playerId),
        )
    }
}

internal interface PlayerChatAppearanceRegistry {
    fun register(query: PlayerChatAppearanceQuery)

    fun unregister()
}

internal object ProxyPlayerChatAppearanceRegistry : PlayerChatAppearanceRegistry {
    override fun register(query: PlayerChatAppearanceQuery) {
        ProxyServiceRegistry.register(PlayerChatAppearanceQuery::class.java, query)
    }

    override fun unregister() {
        ProxyServiceRegistry.unregister(PlayerChatAppearanceQuery::class.java)
    }
}

internal object NoopPlayerChatAppearanceRegistry : PlayerChatAppearanceRegistry {
    override fun register(query: PlayerChatAppearanceQuery) = Unit

    override fun unregister() = Unit
}
