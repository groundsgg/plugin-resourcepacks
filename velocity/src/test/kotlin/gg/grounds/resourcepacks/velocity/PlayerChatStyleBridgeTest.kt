package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.ChannelIdentifier
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

class PlayerChatStyleBridgeTest {
    @Test
    fun `payload is exact version UUID revision and readiness in twenty six big endian bytes`() {
        val playerId = UUID.fromString("12345678-1234-4321-8765-123456789abc")

        val ready =
            PlayerChatStylePayload.encode(
                playerId,
                PlayerChatStyleSnapshot(0x0102030405060708, ready = true),
            )
        val unavailable =
            PlayerChatStylePayload.encode(playerId, PlayerChatStyleSnapshot(9, ready = false))

        assertEquals(PlayerChatStylePayload.SIZE, ready.size)
        assertEquals(PlayerChatStylePayload.SIZE, unavailable.size)
        assertContentEquals(
            byteArrayOf(
                1,
                0x12,
                0x34,
                0x56,
                0x78,
                0x12,
                0x34,
                0x43,
                0x21,
                0x87.toByte(),
                0x65,
                0x12,
                0x34,
                0x56,
                0x78,
                0x9a.toByte(),
                0xbc.toByte(),
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                1,
            ),
            ready,
        )
        assertEquals(0, unavailable.last().toInt())

        val decoded = ByteBuffer.wrap(ready).order(ByteOrder.BIG_ENDIAN)
        assertEquals(PlayerChatStylePayload.VERSION, decoded.get())
        assertEquals(playerId.mostSignificantBits, decoded.long)
        assertEquals(playerId.leastSignificantBits, decoded.long)
        assertEquals(0x0102030405060708, decoded.long)
        assertEquals(1, decoded.get().toInt())
        assertFailsWith<IllegalArgumentException> {
            PlayerChatStylePayload.encode(playerId, PlayerChatStyleSnapshot(0, ready = false))
        }
    }

    @Test
    fun `receiver ordering rejects an older ready snapshot delivered after newer false`() {
        val playerId = UUID.fromString("12345678-1234-4321-8765-123456789abc")
        val olderReady =
            PlayerChatStylePayload.encode(playerId, PlayerChatStyleSnapshot(4, ready = true))
        val newerFalse =
            PlayerChatStylePayload.encode(playerId, PlayerChatStyleSnapshot(5, ready = false))
        var lastRevision = 0L
        var ready = false

        listOf(newerFalse, olderReady).forEach { payload ->
            val decoded = decode(payload)
            if (decoded.revision > lastRevision) {
                lastRevision = decoded.revision
                ready = decoded.ready
            }
        }

        assertEquals(5, lastRevision)
        assertFalse(ready)
    }

    @Test
    fun `transport skips absent server and encodes a fresh player bound snapshot`() {
        val player = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val packets = mutableListOf<ByteArray>()
        val channels = mutableListOf<ChannelIdentifier>()
        val connections = mutableListOf<Int>()
        var current: ServerConnection? = null
        fun server(index: Int) =
            Proxy.newProxyInstance(
                ServerConnection::class.java.classLoader,
                arrayOf(ServerConnection::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "sendPluginMessage" -> {
                        connections += index
                        channels += args!![0] as ChannelIdentifier
                        packets += args[1] as ByteArray
                        true
                    }
                    "equals" -> proxy === args?.singleOrNull()
                    "hashCode" -> 1
                    else -> defaultValue(method.returnType)
                }
            } as ServerConnection
        var registrations = 0
        var unregistrations = 0
        val bridge =
            VelocityPlayerChatStyleBridge(
                registerChannel = { registrations++ },
                unregisterChannel = { unregistrations++ },
                currentServer = { current },
            )

        bridge.register()
        bridge.send(player, PlayerChatStyleSnapshot(1, ready = true))
        assertEquals(emptyList(), packets)

        current = server(1)
        bridge.send(player, PlayerChatStyleSnapshot(2, ready = true))
        current = server(2)
        bridge.send(player, PlayerChatStyleSnapshot(3, ready = false))
        bridge.unregister()
        bridge.send(player, PlayerChatStyleSnapshot(4, ready = true))

        assertEquals(listOf(1, 2), connections)
        assertEquals(listOf(bridge.identifier, bridge.identifier), channels)
        assertEquals(2, packets.size)
        assertEquals(player.uniqueId, decodePlayer(packets[0]))
        assertEquals(2, decode(packets[0]).revision)
        assertEquals(3, decode(packets[1]).revision)
        assertEquals(1, packets[0].last().toInt())
        assertEquals(0, packets[1].last().toInt())
        assertNotSame(packets[0], packets[1])
        assertEquals(1, registrations)
        assertEquals(1, unregistrations)
    }

    private fun decodePlayer(payload: ByteArray): UUID {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        return UUID(buffer.long, buffer.long)
    }

    private fun decode(payload: ByteArray): PlayerChatStyleSnapshot {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        buffer.long
        buffer.long
        return PlayerChatStyleSnapshot(buffer.long, buffer.get().toInt() == 1)
    }
}
