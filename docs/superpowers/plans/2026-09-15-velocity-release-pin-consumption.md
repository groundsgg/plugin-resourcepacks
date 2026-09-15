# Velocity Release Pin Consumption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consume immutable release pins through the existing Velocity configuration and safe-transition delivery path.

**Architecture:** Add a nullable typed source pin and map it to the published resourcepacks client selection. Replace channel-only SDK access in delivery/logging with explicit selection/publication metadata. Preserve the existing coordinator, initial-configuration deadline, Config Manager subscription and server-switch deduplication.

**Tech Stack:** Kotlin/JVM, Jackson 3 plain Java-bean deserialization, Velocity, Adventure, Gradle locked dependencies.

**Spec:** `/home/lukas/grounds/.worktrees/docs-packset-portal-observability/docs/superpowers/specs/2026-09-14-packset-selection-and-rollback-design.md`; approved client/Velocity subsection only.

## Global Constraints

- Baseline plugin main `fbbb7510e9aad4caa5d24bbaaa86c9a6e5f7c02a` (released 0.1.11). Original checkout remains untouched.
- Use `gg.grounds:resourcepacks-client:0.7.0` only after its automatic Maven publication succeeds. No Maven Local/composite override, manual release or tag.
- Stored `source.pin` is null or `{ "type": "release", "id": "v<SemVer>" }`; non-null pin overrides the channel, which remains a valid explicit stable/edge fallback.
- Plain Jackson must populate consumer-owned mutable classes without Kotlin metadata. Keep existing source constructor calls compatible by appending the nullable default field.
- Canonical ID validation belongs to `PackSetSource.release`, never a new SemVer regex. Reject unsupported type, malformed ID and invalid fallback channel even when pinned. Invalid updates leave last healthy settings/source active.
- Use real release snapshots from the published resolver in new pin tests; never fabricate a ChannelDocument for a release.
- No online-player broadcast on activation/config changes. Existing login and genuine backend-switch sends and fingerprint deduplication remain unchanged.
- No new executor/cache/lifecycle abstraction, no live document mutation, bundle/deployment pins, Forge flag activation or cluster-dependent tests.
- Focused tests only at changed settings, request and plugin integration boundaries. Keep existing 90-test safe-transition matrix and packaging checks.
- Scoped implementation uses Terra medium; primary owns architecture/integration/security, final whole-branch review uses Astra high.

## Task 1: Pin-aware settings, SDK consumers and focused integration

**Files:**
- Modify `velocity/build.gradle.kts` and `velocity/gradle.lockfile`: published SDK version and only necessary transitive locks.
- Modify `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackSettings.kt`: typed pin plus mapping.
- Modify `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/VelocityPackRequestFactory.kt`: publication target ID.
- Modify `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/GroundsResourcePacksPlugin.kt`: the two source log entries, no delivery rewrite.
- Test `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackSettingsTest.kt`, `VelocityPackRequestFactoryTest.kt`, `GroundsResourcePacksPluginTest.kt`.
- Modify `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackTestFixtures.kt`: one resolver-backed release snapshot helper; existing channel fixture stays channel-only.
- Modify `README.md`: nullable pin, override/unpin behavior, immutable cache/no polling, rollout gate.

**Interfaces:** Consumes published `PackSetSource.release(URI,String,String)`, `PackSetSelection.Channel/Release`, `PackSetSnapshot.publication: ChannelTarget`, and public `PackSetClient.refreshNow(): CompletionStage<RefreshResult>` with injected transport/config. Produces `ResourcePackSourcePinSettings(var type:String="release",var id:String="")`, `ResourcePackSourceSettings.pin: ResourcePackSourcePinSettings? = null`, unchanged `ResourcePackSettings.toClientSource(): PackSetSource`, unchanged prepared target ID and coordinator/public event contracts.

- [ ] **Step 1: Tests first / capture RED.** Add the following settings tests before production changes. Existing SDK 0.3.1 lacks selection; an expected new-API compile failure is valid initial RED. Run `./gradlew :velocity:test --tests '*ResourcePackSettingsTest'`; record relevant output honestly. Once the published dependency is available, upgrade dependency plumbing and rerun to expose missing pin support; do not alter settings implementation before RED.

```kotlin
@Test fun `plain Jackson pin overrides edge and keeps the fallback for unpin`() {
    val value = ObjectMapper().readValue(
        """{"source":{"channel":"edge","pin":{"type":"release","id":"v0.7.0"}}}""",
        ResourcePackSettings::class.java)
    assertEquals(PackSetSelection.Release("v0.7.0"), value.toClientSource().selection)
    assertEquals("edge", value.source.channel)
    value.source.pin = null
    assertEquals(PackSetSelection.Channel(PackSetChannel.EDGE), value.toClientSource().selection)
}
@Test fun `pin rejects invalid kind ID and fallback channel`() {
    listOf("""{"type":"build","id":"v0.7.0"}""",
        """{"type":"release","id":"0.7.0"}""",
        """{"type":"release","id":"v01.7.0"}""",
        """{"type":"release","id":"v0.7.0/other"}""",
        """{"type":"release","id":""}""").forEach { pin ->
        val value = ObjectMapper().readValue("""{"source":{"pin":$pin}}""", ResourcePackSettings::class.java)
        assertFailsWith<IllegalArgumentException> { value.toClientSource() }
    }
    assertFailsWith<IllegalArgumentException> {
        ResourcePackSettings(source=ResourcePackSourceSettings(channel="invalid",
            pin=ResourcePackSourcePinSettings(id="v0.7.0"))).toClientSource()
    }
}
```

Preserve missing-pin compatibility through existing default/plain-Jackson tests; assert null pin there. Existing channel selection assertions use `selection` rather than deprecated SDK channel getters.

- [ ] **Step 2: Minimal production mapping and published locks.** After controller confirms publication, change dependency to 0.7.0 and run `./gradlew :velocity:dependencies --write-locks` (or targeted `--update-locks gg.grounds:resourcepacks-client,gg.grounds:resourcepacks-contract` preserving unrelated locks). Resolve channel first, then pin:

```kotlin
data class ResourcePackSourcePinSettings(var type: String = "release", var id: String = "")
// append to ResourcePackSourceSettings: var pin: ResourcePackSourcePinSettings? = null
fun toClientSource(): PackSetSource {
    val channel = when (source.channel) {
        "stable" -> PackSetChannel.STABLE
        "edge" -> PackSetChannel.EDGE
        else -> throw IllegalArgumentException("Unsupported PackSet channel")
    }
    val pin = source.pin ?: return PackSetSource(URI(source.baseUrl), source.packSet, channel)
    require(pin.type == "release") { "Unsupported PackSet pin type" }
    return PackSetSource.release(URI(source.baseUrl), source.packSet, pin.id)
}
```

- [ ] **Step 3: Real release fixture and consumer RED/GREEN.** Add `releaseSnapshot(settings, version="1.2.3")` to test fixtures, using a literal validated manifest, selected source and real `PackSetResolver`, not an SDK internal constructor. Adapt the existing published transport signatures:

```kotlin
val source = settings.toClientSource()
val root = "${source.baseUri}/resourcepacks/packsets/${source.packSet}/releases/v$version"
val bytes = """
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
""".trimIndent().plus("\n").encodeToByteArray()
val transport = object : PackSetHttpTransport {
    override fun get(uri: URI, ifNoneMatch: String?, timeout: Duration): PackSetHttpResponse {
        check(uri == source.requestUri && ifNoneMatch == null)
        return PackSetHttpResponse(200, null, bytes.inputStream())
    }
}
val directory = Files.createTempDirectory("velocity-release-fixture")
try {
    return PackSetClient(PackSetClientConfig(source, directory), transport).use { client ->
        assertIs<RefreshResult.Activated>(
            client.refreshNow().toCompletableFuture().get(5, TimeUnit.SECONDS)
        ).snapshot
    }
} finally {
    Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}
```

Add factory test with pinned settings v1.2.3 and real fixture: prepared request targetId literal v1.2.3, UUID/URI/SHA1 ordered fields, fingerprint unchanged for same snapshot and no request for mismatched other-pin source fallback. Run focused factory test before changing `snapshot.channel.target.id`; expect controlled channel getter failure. Replace with `snapshot.publication.id` only after observed RED.

Plugin integration tests reuse existing FakeConfigGateway/FakeClientFactory/event fixtures: initialize stable, emit READY, finish initial configuration and pack terminal statuses as existing server-switch tests do; configure pin v1.2.3 and emit its real READY snapshot, assert same client reconfigured and no send on activation, then genuine switch sends once/deduplicates. Include subsequent pin v1.2.4 and unpin edge mapping, and one invalid pin update preserving old source with no reconfigure/send and sanitized rejection log. Source settings/snapshot logs are exercised by valid pin initialization/change; capture expected channel-only log getter failure before migration. Replace both log accesses with bounded source-mode/channel-or-canonical-release metadata via `selection`, never deprecated `.channel`. No arbitrary raw settings or tokens in logs. No new lifecycle code.

- [ ] **Step 4: Consumer docs and focused GREEN.** README default JSON adds `"pin": null`; add release pin JSON v0.7.0 retaining fallback channel. Explain null/missing pin follows channel, release pin overrides channel, initial validation/offline reuse/no periodic polling, config updates resolve in background and adopt only at next safe transition. Explain pin-aware runtime rollout prerequisite and default-off later operator mutation. Do not claim deployed or fully complete Phase 8. Run focused settings/factory/plugin tests then `./gradlew :velocity:check` once; preserve packaging/metadata checks and old safe-transition cases. Record warnings with actual ownership, no unrelated suppression/dependency upgrades. Run diff check, inspect locks for unrelated churn.

- [ ] **Step 5: Signed commit, independent reviews and integration.** Commit only mapped files, no force-add scratch. Task-scoped Terra review then broad Astra review; fix verified issues through original worker and scoped re-review. Primary reruns `./gradlew check` at final exact head, checks CI. Push/create normal PR under existing user authority; plugin main does not mutate live PackSet channels or deploy proxies. Merge only exact reviewed/tested head with required CI green. Release Please owns version/tag generation. Bundle/deployment migration follows published plugin version; live pin activation still gated on every target runtime verified.

## Plan self-review

One cohesive task produces settings-to-request-to-event integration; no second independently rejectable subsystem is introduced. Published SDK boundary is a prerequisite, not local-source substitution. Types match the approved client and plain-Jackson repository style. Existing safe-transition delivery remains unchanged; only metadata logging/access is migrated. Core/Forge/Portal controls and rollout are separate dependent slices.
