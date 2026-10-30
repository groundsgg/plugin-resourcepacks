package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.ChannelIdentifier
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal object PlayerChatStylePayload {
    const val CHANNEL = "grounds:chat_style"
    const val VERSION: Byte = 1
    const val SIZE = 26

    fun encode(playerId: UUID, snapshot: PlayerChatStyleSnapshot): ByteArray {
        require(snapshot.revision > 0) { "chat style revision must be positive" }
        return ByteBuffer.allocate(SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VERSION)
            .putLong(playerId.mostSignificantBits)
            .putLong(playerId.leastSignificantBits)
            .putLong(snapshot.revision)
            .put(if (snapshot.ready) 1.toByte() else 0.toByte())
            .array()
    }
}

internal interface PlayerChatStyleBridge {
    val identifier: ChannelIdentifier

    fun register()

    fun unregister()

    fun send(player: Player, snapshot: PlayerChatStyleSnapshot)
}

internal class VelocityPlayerChatStyleBridge(
    private val registerChannel: (ChannelIdentifier) -> Unit,
    private val unregisterChannel: (ChannelIdentifier) -> Unit,
    private val currentServer: (Player) -> ServerConnection? = { player ->
        player.currentServer.orElse(null)
    },
    channelId: String = PlayerChatStylePayload.CHANNEL,
) : PlayerChatStyleBridge {
    private val lifecycle = Any()
    private var active = false

    override val identifier: ChannelIdentifier = MinecraftChannelIdentifier.from(channelId)

    override fun register() {
        synchronized(lifecycle) {
            if (active) return
            registerChannel(identifier)
            active = true
        }
    }

    override fun unregister() {
        synchronized(lifecycle) {
            if (!active) return
            unregisterChannel(identifier)
            active = false
        }
    }

    override fun send(player: Player, snapshot: PlayerChatStyleSnapshot) {
        synchronized(lifecycle) {
            if (!active) return
            val server = currentServer(player) ?: return
            server.sendPluginMessage(
                identifier,
                PlayerChatStylePayload.encode(player.uniqueId, snapshot),
            )
        }
    }
}

internal object NoopPlayerChatStyleBridge : PlayerChatStyleBridge {
    override val identifier: ChannelIdentifier =
        MinecraftChannelIdentifier.from(PlayerChatStylePayload.CHANNEL)

    override fun register() = Unit

    override fun unregister() = Unit

    override fun send(player: Player, snapshot: PlayerChatStyleSnapshot) = Unit
}
