package gg.grounds.resourcepacks.velocity

import com.velocitypowered.api.proxy.Player
import gg.grounds.resourcepacks.client.PackSetClient
import gg.grounds.resourcepacks.client.PackSetClientConfig
import gg.grounds.resourcepacks.client.PackSetClientState
import gg.grounds.resourcepacks.client.PackSetClientStatus
import gg.grounds.resourcepacks.client.PackSetHttpResponse
import gg.grounds.resourcepacks.client.PackSetHttpTransport
import gg.grounds.resourcepacks.client.PackSetSnapshot
import gg.grounds.resourcepacks.client.RefreshResult
import gg.grounds.resourcepacks.client.ResolvedPack
import gg.grounds.resourcepacks.contract.ChannelDocument
import gg.grounds.resourcepacks.contract.ChannelManifestReference
import gg.grounds.resourcepacks.contract.ChannelTarget
import gg.grounds.resourcepacks.contract.ManifestCatalog
import gg.grounds.resourcepacks.contract.ManifestMinecraft
import gg.grounds.resourcepacks.contract.ManifestProvenance
import gg.grounds.resourcepacks.contract.ManifestPublication
import gg.grounds.resourcepacks.contract.PackSetChannel
import gg.grounds.resourcepacks.contract.PackSetManifest
import gg.grounds.resourcepacks.contract.PublicationType
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun settings(
    packSet: String = "global",
    required: Boolean = true,
    prompt: String = "Grounds packs",
    pin: ResourcePackSourcePinSettings? = null,
) =
    ResourcePackSettings(
        source =
            ResourcePackSourceSettings(
                baseUrl = "https://assets.example.test",
                packSet = packSet,
                channel = "stable",
                pin = pin,
            ),
        required = required,
        prompt = prompt,
    )

internal fun snapshot(
    settings: ResourcePackSettings,
    sequence: Long = 1,
    packs: List<ResolvedPack> = listOf(resolvedPack()),
): PackSetSnapshot {
    val source = settings.toClientSource()
    val version = "1.0.$sequence"
    val targetId = "v$version"
    return PackSetSnapshot(
        source,
        ChannelDocument(
            schemaVersion = 2,
            packSet = settings.source.packSet,
            channel = PackSetChannel.STABLE,
            sequence = sequence,
            target = ChannelTarget(PublicationType.RELEASE, targetId),
            manifest =
                ChannelManifestReference(
                    "https://assets.example.test/$targetId/manifest.json",
                    "a".repeat(64),
                    100,
                ),
        ),
        PackSetManifest(
            schemaVersion = 2,
            packSet = settings.source.packSet,
            publication = ManifestPublication(PublicationType.RELEASE, targetId),
            version = version,
            minecraft = ManifestMinecraft("1.21.8", 55),
            catalog =
                ManifestCatalog(
                    "global",
                    version,
                    "gg.grounds:catalog:$version",
                    "catalog.jar",
                    "b".repeat(64),
                    100,
                ),
            packs = emptyList(),
            provenance = ManifestProvenance("grounds/resourcepacks", "c".repeat(40)),
        ),
        packs,
    )
}

internal fun releaseSnapshot(
    settings: ResourcePackSettings,
    version: String = "1.2.3",
): PackSetSnapshot {
    val source = settings.toClientSource()
    val root = "${source.baseUri}/resourcepacks/packsets/${source.packSet}/releases/v$version"
    val bytes =
        """
        {
          "catalog": {
            "coordinate": "gg.grounds:resourcepacks-catalog:$version",
            "file": "grounds-resourcepack-catalog-v$version.jar",
            "id": "grounds:resourcepacks",
            "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "size": 3,
            "version": "$version"
          },
          "minecraft": {
            "resourcePackFormat": 88,
            "version": "26.2"
          },
          "packSet": "${source.packSet}",
          "packs": [
            {
              "id": "grounds-content",
              "order": 0,
              "required": true,
              "resourcePackFormat": 88,
              "role": "content",
              "sha1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
              "size": 4,
              "url": "$root/grounds-content-pack-v$version.zip",
              "uuid": "44591d5b-71f5-5c2a-a5b2-d3ee7be47e53"
            },
            {
              "id": "grounds-platform",
              "order": 1,
              "required": true,
              "resourcePackFormat": 88,
              "role": "platform",
              "sha1": "dddddddddddddddddddddddddddddddddddddddd",
              "sha256": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
              "size": 5,
              "url": "$root/grounds-platform-pack-v$version.zip",
              "uuid": "8da7cffe-bb04-55e0-9868-7789ce5de362"
            }
          ],
          "provenance": {
            "commit": "1969c1e6a3799e976de46eab019a16b2ee257ea7",
            "repository": "groundsgg/resourcepacks"
          },
          "publication": {
            "id": "v$version",
            "type": "release"
          },
          "schemaVersion": 2,
          "version": "$version"
        }
        """
            .trimIndent()
            .plus("\n")
            .encodeToByteArray()
    val transport =
        object : PackSetHttpTransport {
            override fun get(
                uri: URI,
                ifNoneMatch: String?,
                timeout: Duration,
            ): PackSetHttpResponse {
                check(uri == source.requestUri && ifNoneMatch == null)
                return PackSetHttpResponse(200, null, bytes.inputStream())
            }
        }
    val cacheDirectory = Files.createTempDirectory("resourcepacks-release-snapshot-test")
    try {
        PackSetClient(PackSetClientConfig(source, cacheDirectory), transport).use { client ->
            val result = client.refreshNow().toCompletableFuture().get(5, TimeUnit.SECONDS)
            check(result is RefreshResult.Activated) { "Release fixture did not activate: $result" }
            return result.snapshot
        }
    } finally {
        Files.walk(cacheDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

internal fun resolvedPack(
    order: Int = 1,
    uuid: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111"),
    uri: URI = URI("https://assets.example.test/base.zip"),
    sha1: String = "1".repeat(40),
) =
    ResolvedPack(
        order = order,
        role = "global",
        id = "pack-$order",
        uuid = uuid,
        uri = uri,
        sha1 = sha1,
        sha256 = "e".repeat(64),
        size = 100,
        required = true,
    )

internal fun readyState(settings: ResourcePackSettings, snapshot: PackSetSnapshot) =
    PackSetClientState(settings.toClientSource(), snapshot, null, PackSetClientStatus.READY, null)

internal fun degradedState(settings: ResourcePackSettings, fallback: PackSetSnapshot) =
    PackSetClientState(
        settings.toClientSource(),
        null,
        fallback,
        PackSetClientStatus.DEGRADED,
        "offline",
    )

internal fun player(
    uuid: String,
    identified: () -> Unit = {},
    disconnected: () -> Unit = {},
): Player {
    val id = UUID.fromString(uuid)
    return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
        proxy,
        method,
        args ->
        when (method.name) {
            "getUniqueId" -> {
                identified()
                id
            }
            "disconnect" -> {
                disconnected()
                null
            }
            "identity" -> net.kyori.adventure.identity.Identity.identity(id)
            "equals" -> proxy === args?.singleOrNull()
            "hashCode" -> id.hashCode()
            "toString" -> "TestPlayer($id)"
            else -> defaultValue(method.returnType)
        }
    } as Player
}

internal fun defaultValue(type: Class<*>): Any? =
    when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
