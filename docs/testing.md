# Testing strategy

What `meshtastic-sdk` tests, where, and why: the contract between a behavior change and the tests that prove it. The CI gates are in [`ci-cd.md`](./ci-cd.md); the failure contracts tests assert are in [`error-taxonomy.md`](./error-taxonomy.md).

## Tiers

Three tiers, narrow at the top:

```text
              ┌─────────────────────────┐
              │  Manual hardware tests  │   docs/manual-tests.md
              │      (real nodes)       │   pre-release
              └────────────┬────────────┘
                           │
          ┌────────────────┴────────────────┐
          │      Integration tests          │   :core commonTest,
          │  (FakeRadioTransport-backed,    │   :samples:cli conformance
          │     full handshake + frames)    │
          └────────────────┬────────────────┘
                           │
   ┌───────────────────────┴───────────────────────┐
   │                  Unit tests                   │   commonTest in
   │ codec bytes ▪ FSM tables ▪ NodeDB ops         │   each module;
   │  ▪ frame assembly ▪ pure-function helpers     │   fast, hermetic
   └───────────────────────────────────────────────┘
```

Anything provable against a pure function or a deterministic fake belongs in the unit tier. Integration tests are for behavior that depends on the engine actor and the transport seam.

The convention plugins in `build-logic/` also have Gradle TestKit tests (`build-logic/convention/src/test/`), which `./gradlew check` runs with the rest of the gate.

## Unit tests

Unit tests live in each module's `commonTest`, with platform source sets (`jvmTest`, `jvmAndroidTest`, `androidHostTest`) where a platform API is involved. Examples:

| Area | Module | Test class | Style |
|---|---|---|---|
| Wire codec round trip | `:core` | [`WireCodecTest`](../core/src/commonTest/kotlin/org/meshtastic/sdk/WireCodecTest.kt) | byte vectors |
| Handshake state machine | `:core` | [`HandshakeFsmTest`](../core/src/commonTest/kotlin/org/meshtastic/sdk/HandshakeFsmTest.kt) | transitions from [`architecture/handshake-fsm.md`](./architecture/handshake-fsm.md) |
| Engine actor contract | `:core` | [`EngineTest`](../core/src/commonTest/kotlin/org/meshtastic/sdk/EngineTest.kt) | `FakeRadioTransport`, virtual time |
| Live NodeDB merge | `:core` | [`LiveNodeDbMergeTest`](../core/src/commonTest/kotlin/org/meshtastic/sdk/LiveNodeDbMergeTest.kt) | `FakeRadioTransport` |
| Bluetooth frame stripping | `:transport-ble` | [`FrameStripTest`](../transport-ble/src/commonTest/kotlin/org/meshtastic/sdk/transport/ble/FrameStripTest.kt) | edge cases |
| Bluetooth drain coordinator | `:transport-ble` | [`DrainCoordinatorTest`](../transport-ble/src/commonTest/kotlin/org/meshtastic/sdk/transport/ble/internal/DrainCoordinatorTest.kt) | virtual-time `runTest` |
| Serial frame assembly | `:transport-serial` | [`SerialFrameAssemblerTest`](../transport-serial/src/commonTest/kotlin/org/meshtastic/sdk/transport/serial/SerialFrameAssemblerTest.kt) | byte-stream vectors |

A handshake failure test asserts the exact `MeshtasticException` subclass from [`error-taxonomy.md`](./error-taxonomy.md).

## Libraries

The `meshtastic.kmp.library` convention plugin ([`KmpLibraryConventionPlugin.kt`](../build-logic/convention/src/main/kotlin/KmpLibraryConventionPlugin.kt)) adds these to every module's `commonTest`:

| Library | Purpose |
|---|---|
| `kotlin.test` | Assertions (`assertEquals`, `assertFailsWith`) and `@Test`. Runs on JUnit on the JVM, XCTest on iOS and Node.js on JS and Wasm. |
| `kotlinx-coroutines-test` | `runTest { }` with its virtual-time scheduler, `TestScope`, `advanceTimeBy` and `runCurrent`. |
| [Turbine](https://github.com/cashapp/turbine) | `flow.test { awaitItem(); expectNoEvents() }` for asserting on a `Flow`. |
| Kotest assertions | Matchers such as `shouldBe` and `shouldThrow`, used alongside `kotlin.test` where they read better. |
| Power-Assert (compiler plugin) | Expands `assert`, `require` and the `kotlin.test` assertion messages into the full expression tree. |

Conventions:

- One test class per behavior, with descriptive names.
- `runTest { }` for anything touching coroutines, never `runBlocking`, and no `Dispatchers.IO` in `commonTest`.
- Turbine, `runCurrent()` or a collected list for flows, never polling loops.
- `assertFailsWith<MeshtasticException.NotConnected>` for failure contracts; the exception subclass is the contract.

## Integration tests

These exercise the public contract of `RadioClient` against `FakeRadioTransport`, the same fake the `:testing` module publishes for consumers. `:core`'s `commonTest` covers:

| Scenario | Validates |
|---|---|
| Cold start to connected | `ConnectionState` reaches `Connected` only after the matching `config_complete_id` |
| Bytes before the handshake | Frames that arrive before the handshake completes never reach `events` |
| Send before connect | `sendText` throws `MeshtasticException.NotConnected` |
| Disconnect during the handshake | The state returns to `Disconnected` and partial state is cleared |
| Reconnect with the same client | The engine actor survives and `connect()` can be called again |

`FakeRadioTransport` isn't a mock. It is a scripted transport: `injectFrame` and `injectPacket` deliver inbound traffic, `outboundFrames()` and `outboundPackets()` return what the engine sent, and `simulateError` drops the link. Tests assert on the recorded traffic, not on engine internals.

### Using the fixtures

`:testing` is published so consumers can use the same fixtures. Inside this repository, add it as a project dependency:

```kotlin
// build.gradle.kts
kotlin.sourceSets.commonTest.dependencies {
    implementation(project(":testing"))   // brings :core, Turbine and kotlinx-coroutines-test
}
```

| Fixture | Purpose | Module |
|---|---|---|
| [`FakeRadioTransport`](../testing/src/commonMain/kotlin/org/meshtastic/sdk/testing/FakeRadioTransport.kt) | A scripted `RadioTransport`: pre-load frames, inject frames, packets and canned admin, telemetry, routing and store-and-forward responses, and record outbound frames. With `autoHandshake = true` it answers the two-stage handshake and the owner request, so `RadioClient.connect()` reaches `Connected` without a node; `nodeNum` sets the reported NodeNum. | `:testing` |
| [`TestClock`](../testing/src/commonMain/kotlin/org/meshtastic/sdk/testing/TestClock.kt) | A `kotlin.time.Clock` the test moves with `advance` or `set`, for `RadioClient.Builder.clock(...)`. | `:testing` |
| [`InMemoryStorage`](../core/src/commonMain/kotlin/org/meshtastic/sdk/InMemoryStorage.kt) and `InMemoryStorageProvider` | RAM-backed `DeviceStorage` and `StorageProvider` that honor the SQLDelight backend's contract, including identity-rebind clearing in `recordOwnNode`, with no JDBC or JNI. `:testing` keeps deprecated aliases. | `:core` |

#### Engine example

```kotlin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.InMemoryStorageProvider
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectTest {
    @Test
    fun connectCompletesAgainstTheFake() = runTest {
        val transport = FakeRadioTransport(identity = TransportIdentity("fake:test"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

        client.connect()

        assertEquals(ConnectionState.Connected, client.connection.value)
        assertTrue(transport.outboundFrames().isNotEmpty())   // the handshake went out on the wire
    }

    @Test
    fun recoverableTransportErrorReconnects() = runTest {
        val transport = FakeRadioTransport(identity = TransportIdentity("fake:test"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoReconnect()
            .build()
        client.connect()

        transport.simulateError(IllegalStateException("link lost"), recoverable = true)
        runCurrent()

        assertIs<ConnectionState.Reconnecting>(client.connection.value)
    }
}
```

More examples sit beside the engine in [`core/src/commonTest/kotlin/org/meshtastic/sdk/`](../core/src/commonTest/kotlin/org/meshtastic/sdk/), notably `EngineTest`, `HandshakeFsmTest`, `HandshakeAndReconnectTest` and `MeshEngineEdgeCasesTest`.

#### Storage example

For storage behavior above the `DeviceStorage` interface, `InMemoryStorage` is faster than an in-memory SQLite driver, needs no native code and behaves the same on every target:

```kotlin
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.InMemoryStorage
import org.meshtastic.sdk.NodeId
import kotlin.test.Test
import kotlin.test.assertTrue

class IdentityRebindExampleTest {
    @Test
    fun recordingADifferentNodeNumClearsPriorData() = runTest {
        val storage = InMemoryStorage()
        storage.recordOwnNode(NodeId(0xAABBCC), "2.7.0")
        storage.saveNode(NodeInfo.Builder().also { it.num = 0xAABBCC }.build())

        // A different node behind the same transport identity
        storage.recordOwnNode(NodeId(0x112233), "2.7.0")

        assertTrue(storage.loadNodes().isEmpty())
    }
}
```

To exercise SQL itself (migrations, PRAGMA selection, transaction isolation), use the real SQLDelight driver with an in-memory database in `:storage-sqldelight`'s `jvmTest`, as `StorageDriverTest` does. That tier covers the platform `createDriver()` factories and the schema in [`Mesh.sq`](../storage-sqldelight/src/commonMain/sqldelight/org/meshtastic/sdk/storage/sqldelight/internal/Mesh.sq); `verifyMigrations = true` checks the migrations at build time.

## Manual hardware tests

[`manual-tests.md`](./manual-tests.md) has the matrix. A maintainer runs it before a release with real hardware, partly scripted through `cli conformance`. A self-hosted `hw-loop.yml` runner is planned for after 1.0 ([`ci-cd.md`](./ci-cd.md#roadmap)).

A manual test needs a documented reason it can't run against `FakeRadioTransport`: real timing, real Bluetooth GATT behavior, real USB serial enumeration.

## Coverage

Kover measures JVM coverage, and the `coverage` CI job uploads it to Codecov. Codecov's project status fails when overall coverage drops by more than 1%, and its patch status is advisory; neither is a required check. iOS and other Native execution isn't counted.

Behavior is the contract, not a percentage:

- Every public type in `:core` has at least one test of its main path.
- Every documented `MeshtasticException` subclass has a test showing the engine throws it under the documented condition.
- Every handshake state transition has a unit test.
- A wire-format change (a new `oneof` case, a new `PortNum`) ships with an updated byte vector in the same PR.

A `MeshtasticException` subclass without a test is a real gap; a coverage dip from a refactor isn't.

## Local commands

```shell
./gradlew jvmTest                                        # every module on the JVM
./gradlew :core:jvmTest                                  # one module
./gradlew :transport-ble:jvmTest --tests "*FrameStrip*"  # one class
./gradlew iosSimulatorArm64Test                          # iOS (macOS)
./gradlew jsNodeTest wasmJsNodeTest                      # JS and Wasm under the Node.js on PATH
./gradlew koverXmlReport                                 # JVM coverage report
./gradlew check                                          # full gate
```

## Anti-patterns

- Real `Dispatchers.Default` in tests. Let `runTest` control the scheduler; a test that hangs for seconds usually broke this rule.
- `Thread.sleep` or `delay` polling in `commonTest`. Use Turbine's `awaitItem()` or `runTest`'s virtual time.
- Mocked transports. `FakeRadioTransport` is the seam; if it can't express a case, extend the fake rather than reaching for MockK.
- Tests of the implementation. A test that breaks on a refactor that changes no behavior is the wrong test; assert on the public contract.
