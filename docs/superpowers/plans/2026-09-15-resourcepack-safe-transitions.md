# Resourcepack Safe Transitions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver initial packs in Velocity configuration and changed packs only at a later backend-server switch, never by broadcasting a new snapshot to online players.

**Architecture:** The coordinator keeps per-player delivery fingerprints and a small backlog containing only players whose initial configuration is still suspended. Snapshot notifications service that backlog, not the proxy's online-player collection. Initial delivery retains the existing terminal-status waiter; later server switches check the current in-memory snapshot and send only a changed fingerprint.

**Tech Stack:** Kotlin/JVM, Velocity 3.5 API, Adventure resource-pack requests, Gradle, kotlin.test.

**Spec:** `/home/lukas/grounds/.worktrees/docs-packset-portal-observability/docs/superpowers/specs/2026-09-14-packset-selection-and-rollback-design.md` (approved commit `3499e25`). This plan implements only its independently deployable Velocity delivery semantics. Release pins, Config Service authorization/concurrency, Core infrastructure, Forge, and Portal have separate subsequent plans.

## Global Constraints

- No snapshot/config-change broadcast to already configured online players.
- Initial delivery stays in `PlayerConfigurationEvent`, including the existing pack-status completion waiter.
- A later `ServerPostConnectEvent` sends only a changed fingerprint and only after that player's initial configuration has completed.
- Fingerprints already bind source, manifest, `required`, and `prompt`; retain them across settings changes instead of clearing them.
- Initial snapshot readiness has a 15-second deadline. This deadline does not limit pack downloads or the existing terminal-status waiter.
- Timeout, disconnect, shutdown, and exception cleanup remove pending initial players; late READY notifications cannot send after cancellation.
- An initially disabled pack must be eligible for delivery on a later server switch after enabling.
- Keep current healthy/degraded source validation, attribution bounds, send failure retry semantics, metrics, and status ownership.
- Do not change resourcepacks-client, dependency pins, Config documents, Stage/Production workloads, or releases in this slice.
- No online-player scan, polling loop, generic scheduler framework, or test-only production cleanup methods.
- Use `apply_patch` for edits; preserve dirty original checkouts. Releases remain Release Please-owned.

---

### Task 1: Safe initial delivery and server-transition activation

**Files:**
- Modify: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackCoordinator.kt` — per-player delivery/initial backlog and synchronized lifecycle.
- Modify: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/GroundsResourcePacksPlugin.kt` — configuration suspension, backend-switch handler, lifecycle wiring.
- Modify: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackConfigurationWaiter.kt` — only if required to expose production lifecycle completion; preserve status semantics.
- Create: `velocity/src/main/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackSnapshotDeadline.kt` — owned, cancelable one-shot snapshot deadline.
- Modify: `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackCoordinatorTest.kt`.
- Modify: `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/GroundsResourcePacksPluginTest.kt`.
- Modify: `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackConfigurationWaiterTest.kt` only if its behavior changes.
- Modify: `velocity/src/test/kotlin/gg/grounds/resourcepacks/velocity/ResourcePackTestFixtures.kt` — player disconnect observation/test scheduler, if needed.
- Modify: `README.md` — operator-visible activation timing.

**Interfaces:**
- Consumes: `ResourcePackSettings`, `PackSetClientState`, `VelocityPackRequestFactory.prepare(settings, state): PreparedPackRequest?`, and existing waiter `begin/expect/seal/forget/clear`.
- Produces: `InitialPackDelivery` enum with `SENT`, `WAITING_FOR_SNAPSHOT`, `NO_REQUEST`.
- Produces: coordinator `onLogin(player: Player): InitialPackDelivery`, retaining existing call sites' ability to ignore the return value; `onServerSwitch(player: Player): Unit`; `onSnapshot(state: PackSetClientState): Unit` services only pending initial players.
- Produces: `ResourcePackSnapshotDeadline : AutoCloseable`, with `schedule(action: () -> Unit): AutoCloseable`. Default production implementation owns one daemon scheduled executor; injected deterministic fake captures callbacks in tests. Scheduling returns a cancellation handle, and close cancels/shuts down outstanding tasks.
- Produces: coordinator callback `initialDeliveryCompleted(playerId: UUID)` to seal the plugin waiter after a delayed request/no-request completion, and a bounded initial failure callback to disconnect an unresolved player and release its waiter. Keep callback lock ordering consistent; user-facing callbacks must not reinsert removed pending entries.

- [ ] **Step 1: Add failing no-broadcast and switch-deduplication tests**

Replace the old test that requires changed snapshots to resend all online players. Using the existing fixtures, the new observable sequence is:

```kotlin
@Test
fun `snapshot changes wait for each players next server switch`() {
    val configured = settings()
    var state = readyState(configured, snapshot(configured, sequence = 1))
    val first = player("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    val second = player("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    val sent = mutableListOf<UUID>()
    val coordinator = ResourcePackCoordinator(
        settings = { configured },
        clientState = { state },
        sender = PackSender { player, _ -> sent += player.uniqueId },
        requestFactory = VelocityPackRequestFactory(),
    )
    coordinator.onLogin(first)
    coordinator.onLogin(second)
    state = readyState(configured, snapshot(configured, sequence = 2))
    coordinator.onSnapshot(state)
    assertEquals(listOf(first.uniqueId, second.uniqueId), sent)
    coordinator.onServerSwitch(first)
    coordinator.onServerSwitch(first)
    assertEquals(listOf(first.uniqueId, second.uniqueId, first.uniqueId), sent)
    coordinator.onServerSwitch(second)
    assertEquals(listOf(first.uniqueId, second.uniqueId, first.uniqueId, second.uniqueId), sent)
}
```

Retain sender/request/attribution tests; update those whose expected broadcast was an intentional old policy. Add one disabled-initial case: known disabled settings complete with `NO_REQUEST`, enabling does not send, the next server switch sends once. A switch before any initial configuration sends nothing.

- [ ] **Step 2: Verify RED**

Run `./gradlew :velocity:test --tests '*ResourcePackCoordinatorTest'` and record the missing switch API/old-broadcast failure. Compile-time missing-API failures establish the new interface; rerun after the interface exists to observe the old behavioral assertion failing before removing broadcast logic.

- [ ] **Step 3: Implement minimal coordinator policy**

Replace online-player enumeration with a map of pending initial players and an initial-completion set. Remove `OnlinePlayerView` from the coordinator and production plugin wiring if it no longer has a consumer; update corresponding constructor test helpers rather than retaining dead production dependencies.

```kotlin
internal enum class InitialPackDelivery { SENT, WAITING_FOR_SNAPSHOT, NO_REQUEST }

// Policy at the synchronized delivery boundary:
// onLogin: known disabled -> mark initially completed; return NO_REQUEST.
// onLogin: matching READY/DEGRADED snapshot -> dispatch; mark completed; return SENT.
// onLogin: unknown settings or no matching snapshot -> register only this initial player;
//          return WAITING_FOR_SNAPSHOT and keep configuration suspended.
// onSnapshot/reconcileSettings: dispatch pending initial players only.
// onServerSwitch: require completed initial configuration, then ordinary fingerprint dispatch.
```

Preserve provisional attribution registration before `sendResourcePacks`, and record a sent fingerprint only after send succeeds. Do not clear fingerprints merely because settings changed. Prevent delayed completion from winning against forget/clear/timeout; pending entries use a generation/identity check so an old deadline cannot cancel a new session for the same UUID. No network request runs on a login/switch path.

- [ ] **Step 4: Add failing plugin lifecycle/readiness tests**

Use `FakeConfigGateway`, `FakeClientFactory`, fake event registry and a deterministic snapshot-deadline fake. Exercise these exact sequences:

```text
STARTING -> PlayerConfigurationEvent -> returned EventTask remains suspended
READY -> one initial request -> terminal pack status -> continuation resumes

initial request completed -> new READY -> zero sends
ServerPostConnectEvent -> one changed request -> repeated switch -> zero duplicate sends

STARTING -> initial configuration -> deadline callback -> disconnect/release
late READY -> zero sends

STARTING -> initial configuration -> disconnect or shutdown -> late READY -> zero sends
```

Retain the existing test proving a ServerPostConnect before PlayerConfiguration cannot replace the initial configuration prompt. Add a fake scheduler assertion based on observable canceled actions, not a 15-second real sleep. The deadline affects only snapshot readiness; prove that once a request has been sent its deadline is canceled and terminal pack statuses still control completion.

- [ ] **Step 5: Verify readiness RED**

Run `./gradlew :velocity:test --tests '*GroundsResourcePacksPluginTest'`; record that missing-snapshot initial configuration currently seals immediately and/or lacks a deadline. Do not weaken initial-prompt assertions to make the new behavior pass.

- [ ] **Step 6: Wire configuration, deadline, switch, and lifecycle**

Keep the PCE handler's existing begin/expect-before-send semantics. Seal only `SENT` and `NO_REQUEST`, or via the delayed completion callback. WAITING schedules a one-shot deadline; successful delayed delivery cancels it. A readiness timeout disconnects with `Component.text("Resource packs are currently unavailable. Please try again.")`, removes pending initial state, and completes the suspended waiter. Do not enforce this timeout on download/status completion.

```kotlin
@Subscribe
fun onServerPostConnect(event: ServerPostConnectEvent) {
    if (!stopped.get()) coordinator.onServerSwitch(event.player)
}
```

Own and close the deadline scheduler through the plugin lifecycle. Exception paths forget both coordinator initial state and waiter. Disconnect does the same; shutdown closes state and deadlines before late client callbacks can dispatch. Preserve current source-matching factory behavior and metrics/status logging. README states that new snapshots are adopted on backend-server switches, not live broadcasts.

- [ ] **Step 7: Verify focused tests, full Velocity tests, and packaging**

Run:

```bash
./gradlew :velocity:test --tests '*ResourcePackCoordinatorTest' --tests '*ResourcePackConfigurationWaiterTest' --tests '*GroundsResourcePacksPluginTest'
./gradlew :velocity:test :velocity:shadowJar
git diff --check
```

Expected: zero failed tests, assembled shadow JAR, no diff whitespace errors. Preserve existing legitimate annotation-processor warning evidence rather than claiming warning-free output. Inspect the patch for online-player scans, dropped status attribution, uncanceled deadlines and callbacks under inconsistent lock ordering.

- [ ] **Step 8: Commit and produce the review report**

```bash
git add velocity/src/main velocity/src/test README.md
git commit -m "fix: apply changed resource packs at server transitions"
```

Report RED commands/results, GREEN commands/results, touched files, commit IDs, and any unresolved concerns. Do not push, merge, deploy, or create a release from the implementer. Controller runs task review and independent critical correctness review before delivery.
