# Testing strategy

> Companion to [`SPEC.md`](./SPEC.md), [`protocol.md`](./protocol.md),
> [`error-taxonomy.md`](./error-taxonomy.md), and the gates documented
> in [`ci-cd.md`](./ci-cd.md).

This document describes **what we test, where, and why** — the test
pyramid for `meshtastic-sdk`. It is the contract between a behavioral
change and the test suite that proves it.

## Pyramid

Three tiers, narrow at the top:

```
              ┌─────────────────────────┐
              │  Manual hardware tests  │   docs/manual-tests.md
              │   (real radios, DUTs)   │   ── pre-release only
              └────────────┬────────────┘
                           │
          ┌────────────────┴────────────────┐
          │      Integration tests          │   :samples:cli + jvmTest
          │  (FakeRadioTransport-backed,    │      end-to-end engine
          │     full handshake + frames)    │      flows
          └────────────────┬────────────────┘
                           │
   ┌───────────────────────┴───────────────────────┐
   │                  Unit tests                   │   commonTest in
   │ codec golden-bytes ▪ FSM tables ▪ NodeDB ops  │   each module;
   │  ▪ frame assembly ▪ pure-function helpers     │   fast, hermetic
   └───────────────────────────────────────────────┘
```

The pyramid is intentionally **wide at the bottom**. Anything that can
be proven against a pure function or a deterministic fake belongs in
the unit tier; integration tests exist only for behavior that depends
on the engine actor + transport seam.

## Tier 1 — unit tests (`*/commonTest`, fast, hermetic)

Coverage targets per module:

| Area | Module | Test class(es) | Style |
|---|---|---|---|
| Wire codec round-trip | `:core` | [`WireCodecTest`](../core/src/commonTest/kotlin/org/meshtastic/sdk/WireCodecTest.kt) | golden-byte vectors |
| Engine actor contract | `:core` | [`EngineTest`](../core/src/commonTest/kotlin/org/meshtastic/sdk/EngineTest.kt) | Turbine on `events` flow |
| BLE frame stripping | `:transport-ble` | [`FrameStripTest`](../transport-ble/src/commonTest/kotlin/org/meshtastic/sdk/transport/ble/FrameStripTest.kt) | property-style edge cases |
| BLE drain coordinator | `:transport-ble` | [`DrainCoordinatorTest`](../transport-ble/src/commonTest/kotlin/org/meshtastic/sdk/transport/ble/internal/DrainCoordinatorTest.kt) | virtual-time `runTest` |
| Serial frame assembly | `:transport-serial` | [`SerialFrameAssemblerTest`](../transport-serial/src/commonTest/kotlin/org/meshtastic/sdk/transport/serial/internal/SerialFrameAssemblerTest.kt) | byte-stream fuzz vectors |

### Required additions (open work — see Track D)

- **`HandshakeFsmTest`** — table-driven coverage of every documented
  state transition in [`architecture/handshake-fsm.md`](./architecture/handshake-fsm.md).
  Each row is `(state, event) → next_state, side_effects`. Failure rows
  must assert the exact `MeshtasticException` subclass per
  [`error-taxonomy.md`](./error-taxonomy.md).
- **`CodecGoldenTest` expansion** — currently round-trips a small set;
  needs a fixture per `FromRadio` and `ToRadio` variant, encoded once
  by a known-good implementation (Python `meshtastic`) and committed
  as hex strings under `core/src/commonTest/resources/golden/`.
- **`NodeDbTest`** — `:core`'s in-memory NodeDB updates: insert,
  upsert-on-NodeInfo, position update, telemetry merge, expiry. Pure-
  function tier, no engine, no flows.

### Conventions

- Use [`runTest`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/run-test.html)
  with the implicit virtual scheduler — no `Dispatchers.IO` references
  in `commonTest`.
- Use [Turbine](https://github.com/cashapp/turbine) for any `Flow`
  assertion. No `flow.first()` polling loops.
- Golden vectors live under `src/commonTest/resources/`. The companion
  Python generator (when present) lives under `tools/golden/` with the
  exact `meshtastic` library version pinned in a `requirements.txt`.

## Test framework & libraries

All tests in `commonTest` use the same toolchain — pulled in by the
`meshtastic.kmp.library` convention plugin
([`KmpLibraryConventionPlugin.kt`](../build-logic/convention/src/main/kotlin/KmpLibraryConventionPlugin.kt))
so individual modules don't need to declare them:

| Library | Purpose |
|---|---|
| `kotlin.test` (`kotlin-test`) | Assertions (`assertEquals`, `assertFailsWith`, …) and the `@Test` annotation. Dispatches to JUnit on JVM, XCTest on iOS, no extra config needed. |
| `kotlinx.coroutines.test` | `runTest { … }` virtual-time scheduler, `TestScope`, `advanceTimeBy`, `runCurrent`. |
| [Turbine](https://github.com/cashapp/turbine) | `flow.test { awaitItem(); expectNoEvents() }` — the only sanctioned way to assert against a `Flow`. |
| Kotest assertions | Fluent matchers (`shouldBe`, `shouldThrow`) used alongside `kotlin.test` when they read better. |
| Power-Assert (compiler plugin) | Expands `assert(...)` / `require(...)` failure messages with a full expression tree. Already applied by the convention plugin. |

Patterns we follow:

- One test class per behavioral unit; descriptive `@Test` names in
  back-tick prose: `` `handshake timeout in stage 1 surfaces HandshakeTimeout`. ``
- `runTest { … }` for anything touching coroutines. Never wrap a test
  body in `runBlocking`.
- Turbine for flows; never `.first()` or polling loops.
- `assertFailsWith<MeshtasticException.NotConnected>` for failure
  contracts — the exception subclass IS the contract per
  [`error-taxonomy.md`](./error-taxonomy.md).

## Tier 2 — integration tests (engine + transport seam)

These tests exercise the **public contract** of `RadioClient` against a
deterministic `FakeRadioTransport` — the same fake we ship in the
`:testing` module so consumers can write their own integration tests.

Required flows under `:core`'s `commonTest`:

| Scenario | Validates |
|---|---|
| Cold start → Connected | Handshake gating: `ConnectionState` only reaches `Connected` after matching `config_complete_id` |
| Pre-handshake bytes discarded | Bytes that arrive before handshake completion never reach `events` |
| Send before connect | `MeshtasticException.NotConnected` thrown by `sendText` |
| Disconnect during handshake | State reverts to `Disconnected`; partial state cleared |
| Reconnect with same client | Engine actor survives; idempotent `connect()` |

`FakeRadioTransport` is not a mock — it's a programmable transport
that exposes `incoming.send(bytes)` and records every outbound frame.
Tests assert against the recorded frames, not against engine internals.

### Writing tests with the `:testing` module fakes

The `:testing` module is published so downstream consumers can use the
same fixtures we use internally. Add it to `commonTest`:

```kotlin
// build.gradle.kts
kotlin.sourceSets.commonTest.dependencies {
    implementation(project(":testing"))   // FakeRadioTransport, InMemoryStorage(Provider)
    implementation(libs.turbine)
    implementation(libs.coroutinesTest)
}
```

Two fixtures ship today:

| Fixture | Purpose | Source |
|---|---|---|
| [`FakeRadioTransport`](../testing/src/commonMain/kotlin/org/meshtastic/sdk/testing/FakeRadioTransport.kt) | Script-driven `RadioTransport`. Pre-load frames, inject at runtime, record outbound frames, optionally `autoHandshake = true` to short-circuit Stage 1/2 + admin `getOwner` so `RadioClient.connect()` resolves to `Connected` without a real radio. | `:testing` |
| [`InMemoryStorage`](../testing/src/commonMain/kotlin/org/meshtastic/sdk/testing/InMemoryStorage.kt) / `InMemoryStorageProvider` | RAM-backed `DeviceStorage` + `StorageProvider`. Mirrors the SQLDelight backend's contract (including identity-rebind clearing in `recordOwnNode`), with no JDBC / JNI. | `:testing` |

#### Engine logic example — `FakeRadioTransport`

```kotlin
class HandshakeIntegrationTest {
    @Test
    fun `connect completes after handshake`() = runTest {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("test"),
            autoHandshake = true,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storageProvider(InMemoryStorageProvider())
            .build()

        client.connection.test {
            assertEquals(ConnectionState.Disconnected, awaitItem())
            client.connect()
            // Configuring → Connected after auto-handshake replies
            skipWhile { it is ConnectionState.Configuring }
            assertEquals(ConnectionState.Connected, awaitItem())
        }

        // Assert what the engine actually sent on the wire
        val sent = transport.outboundFrames()
        assertTrue(sent.any { it.containsWantConfigId(NONCE_STAGE1) })
        assertTrue(sent.any { it.containsWantConfigId(NONCE_STAGE2) })
    }

    @Test
    fun `transport error surfaces as Reconnecting`() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("test"), autoHandshake = true)
        val client = buildClient(transport)
        client.connect()

        transport.simulateError(IOException("link dropped"), recoverable = true)

        client.connection.test {
            // Engine maps recoverable transport errors to Reconnecting
            assertIs<ConnectionState.Reconnecting>(awaitItem())
        }
    }
}
```

Real examples live alongside the engine in
[`core/src/commonTest/kotlin/org/meshtastic/sdk/`](../core/src/commonTest/kotlin/org/meshtastic/sdk/) —
notably `EngineTest`, `HandshakeFsmTest`, and the cold-connect
scenarios.

#### Storage example — `InMemoryStorage`

For storage tests that don't need to verify SQL behavior (anything
above the `DeviceStorage` interface), prefer `InMemoryStorage` over
spinning up an in-memory SQLite driver — it's faster, has no native
deps, and runs identically on every KMP target:

```kotlin
class IdentityRebindTest {
    @Test
    fun `recording a different node num clears prior data`() = runTest {
        val storage = InMemoryStorage()
        storage.saveNode(NodeInfo(num = 0xAABBCC, ...))
        storage.recordOwnNode(NodeId(0xAABBCC), firmwareVersion = "2.5.0")

        // Plug in a different radio: same identity slot, different NodeNum
        storage.recordOwnNode(NodeId(0x112233), firmwareVersion = "2.5.0")

        assertTrue(storage.loadNodes().isEmpty())  // factory-rebind cleared
    }
}
```

When you DO need to exercise SQL behavior — migrations, PRAGMA
selection, transaction isolation — use the actual SQLDelight driver
with an in-memory database (`:memory:`) under `:storage-sqldelight`'s
`jvmTest`. That tier validates the `verifyMigrations = true`
SQLDelight check (see [`Mesh.sq`](../storage-sqldelight/src/commonMain/sqldelight/org/meshtastic/kmp/storage/sqldelight/Mesh.sq))
and the platform-specific `createDriver()` factories.

## Tier 3 — manual hardware

See [`manual-tests.md`](./manual-tests.md) for the full matrix. These
are run **pre-release** by a maintainer with real hardware (or, post-1.0,
the `hw-loop.yml` self-hosted runner described in [`ci-cd.md`](./ci-cd.md)).

Anything in this tier must have a documented reason it cannot be
expressed against `FakeRadioTransport` (real timing, real BLE GATT
quirks, real serial USB enumeration, etc.).

## Coverage targets

We do not enforce a numeric percentage gate (no JaCoCo, no Kover) —
attempting to do so on KMP across native + JVM + Android targets has
historically been more pain than signal. The contract is **behavioral**:

- **Every public type in `:core`** must have at least one test exercising
  its happy path.
- **Every documented `MeshtasticException` subclass** must have a test
  proving the engine throws it under the documented condition.
- **Every state transition in the handshake FSM** must have a unit test
  row.
- **Every wire-format change** (new `oneof` arm, new PortNum) must ship
  with an updated golden-byte vector in the same PR.

### Aspirational thresholds (informational, not gated)

If/when we wire Kover, the targets we'd start from:

| Module | Line coverage aim | Notes |
|---|---|---|
| `:core` | ≥ 85 % | Pure logic; the engine + codec live here. Hot reconnect paths still under-covered today. |
| `:storage-sqldelight` | ≥ 70 % | SQL is exercised but identity-rebind & migration paths are thin. |
| `:transport-tcp` | ≥ 70 % | Reader loop + heartbeat. |
| `:transport-ble` | ≥ 60 % | Bonded by Kable mocking limits; weighted toward `commonMain` helpers. |
| `:transport-serial` | ≥ 60 % | Frame assembler is well-covered; jSerialComm side-effects are not. |
| `:testing` | n/a | Fixtures are exercised transitively by every other module's tests. |

Treat these as guidance, not enforcement. A behavioral gap (e.g., a
`MeshtasticException` subclass without a test) is a real regression;
a coverage-percentage drop on a refactor is not.

## Local commands

```bash
./gradlew jvmTest                           # everything fast (default)
./gradlew :core:jvmTest                     # one module
./gradlew :transport-ble:jvmTest --tests "*FrameStrip*"  # one class
./gradlew iosSimulatorArm64Test             # macOS only — KMP iOS unit tests
./gradlew check                             # full gate (test + lint + checkKotlinAbi + arch)
```

## Anti-patterns

- **No real `Dispatchers.Default` in tests.** Use `runTest` and let it
  control the scheduler. Tests that hang for 5+ seconds are almost
  always making this mistake.
- **No `Thread.sleep` / `delay` polling in `commonTest`.** Use Turbine's
  `awaitItem()` or `runTest`'s virtual time.
- **No mocks for transports.** `FakeRadioTransport` is the seam; if it
  can't express what you need, fix the fake, don't reach for MockK.
- **No "test the implementation" tests.** If a test breaks on a non-
  behavioral refactor, it was the wrong test. Assert against the public
  contract.
