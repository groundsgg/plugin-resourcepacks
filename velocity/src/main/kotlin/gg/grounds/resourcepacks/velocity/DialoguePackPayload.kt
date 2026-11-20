package gg.grounds.resourcepacks.velocity

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/** A pin is trusted only when its exact platform-pack SHA-1 has been loaded. */
internal data class DialogueAppearanceEnvironment(val hashes: Set<String>, val assetPin: String) {
    companion object {
        const val CHANNEL = "grounds:dialogue_pack"
        private val SHA1 = Regex("[0-9a-f]{40}")
        private val PIN = Regex("[0-9a-f]{64}")

        fun eligible(protocol: Int?, bedrock: Boolean?): Boolean =
            protocol == 777 && bedrock == false

        fun from(environment: Map<String, String>): DialogueAppearanceEnvironment {
            val hash = environment["DIALOGUE_PACK_SHA1"].orEmpty()
            val pin = environment["DIALOGUE_ASSET_PIN"].orEmpty()
            if (hash.isEmpty() && pin.isEmpty())
                return DialogueAppearanceEnvironment(emptySet(), "0".repeat(64))
            require(SHA1.matches(hash) && PIN.matches(pin)) {
                "DIALOGUE_PACK_SHA1 and DIALOGUE_ASSET_PIN must both contain lowercase SHA values"
            }
            return DialogueAppearanceEnvironment(setOf(hash), pin)
        }
    }
}

internal object DialoguePackPayload {
    const val SIZE = 90

    fun encode(playerId: UUID, snapshot: PlayerChatStyleSnapshot, assetPin: String): ByteArray {
        require(snapshot.revision > 0) { "dialogue pack revision must be positive" }
        require(assetPin.matches(Regex("[0-9a-f]{64}"))) {
            "dialogue asset pin must be lowercase SHA-256"
        }
        return ByteBuffer.allocate(SIZE)
            .put(1.toByte())
            .putLong(playerId.mostSignificantBits)
            .putLong(playerId.leastSignificantBits)
            .putLong(snapshot.revision)
            .put(if (snapshot.ready) 1.toByte() else 0.toByte())
            .put(assetPin.toByteArray(StandardCharsets.US_ASCII))
            .array()
    }
}
