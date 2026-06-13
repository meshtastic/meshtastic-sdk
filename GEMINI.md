# Meshtastic Kotlin SDK - Project Instructions

## Project Overview
`meshtastic-sdk` is a Kotlin Multiplatform (KMP) SDK for [Meshtastic](https://meshtastic.org) mesh-network radios. It enables Android, iOS, and JVM applications to communicate with Meshtastic devices over BLE, TCP, or USB-serial using the device's PhoneAPI protocol.

### Core Technologies
- **Language:** Kotlin 2.x+
- **Platform:** Kotlin Multiplatform (JVM, Android, iOS)
- **Concurrency:** Kotlin Coroutines (Actors, Flows, Channels)
- **Serialization:** `kotlinx.serialization` (Protobuf via Wire)
- **Storage:** SQLDelight (for persistent node/config/channel data)
- **Networking:** Ktor Sockets (TCP), Kable (BLE), usb-serial-for-android / jSerialComm (Serial)
- **Tooling:** Gradle (Kotlin DSL), Spotless (ktlint), Detekt, Kover, Dokka, Binary Compatibility Validator (BCV)
- **iOS Bridging:** SKIE (for Swift-friendly sealed classes and Flows), KMMBridge (for XCFramework distribution)

### Architecture
The SDK follows a strictly defined architecture documented in `docs/SPEC.md`:
- **RadioClient:** The public facade for the SDK. Uses a Builder pattern for configuration.
- **MeshEngine:** Implemented as an **Actor** (a single coroutine draining a `Channel<EngineMessage>`). ALL state mutation must happen within this actor to ensure thread safety without mutexes.
- **HandshakeMachine:** An explicit FSM driving the two-stage protocol handshake.
- **CommandDispatcher:** Allocates `request_id`s and tracks Admin RPC calls.
- **MessageQueue:** Tracks outbound `MessageHandle`s and delivery states (Queued -> Sent -> Acked/Delivered/Failed).
- **Transport Layer:** Decoupled modules (`transport-ble`, `transport-tcp`, etc.) implementing the `RadioTransport` interface.
- **Storage Layer:** Keyed by `TransportIdentity` (derived from transport config).

## Building and Running

### Requirements
- **JDK 21** (e.g., `sdk install java 21-tem`)
- **Android SDK** (API 35 platform)
- **Xcode** (for iOS targets, macOS only)

### Key Commands
- **Full Check (CI baseline):** `./gradlew check`
  - Runs build, unit tests, lint (Spotless + Detekt), ABI validation (BCV), and coverage (Kover).
- **Run Tests:** `./gradlew allTests`
- **Linting:** `./gradlew spotlessCheck` / `./gradlew spotlessApply`
- **Static Analysis:** `./gradlew detekt`
- **API Documentation:** `./gradlew dokkaGenerate` (Dokka V2; output in `core/build/dokka/html`. The legacy `dokkaHtml` task is removed and errors under V2 mode.)
- **ABI Management:** `./gradlew checkKotlinAbi` (validate) or `./gradlew updateKotlinAbi` (after intentional API changes)

### Sample CLI
The project includes a sample CLI for testing:
```bash
./gradlew :samples:cli:run --args="--host meshtastic.local"
```

## Development Conventions

### API Guidelines
- **Response Shapes:**
  - `suspend fun` throwing `MeshtasticException` for fatal/programmer errors.
  - **Typed Sealed Outcomes** (`SendState`, `AdminResult`) for expected radio/mesh failures (timeouts, NAKs).
  - `Flow` / `StateFlow` for reactive streams and state.
- **Proto Types:** Use Wire-generated protobuf types (`org.meshtastic.proto.*`) directly in the public API where possible.
- **Snake Case:** Wire-generated proto fields are `snake_case` (e.g., `user.long_name`), not `camelCase`.

### Engineering Standards
- **Thread Safety:** Never use `Mutex` or atomics inside the `engine` package; rely on the Actor's single-writer invariant.
- **Platform Limits:** No `java.*` or `android.*` in `commonMain`. Use `okio.ByteString` for byte payloads (Wire's runtime type; kotlinx-io is deliberately not a dependency) and `kotlinx-datetime` for time.
- **Documentation:** Every public symbol MUST have a KDoc. Dokka coverage is a CI gate.
- **Testing:**
  - Use `testing/` module fakes (`FakeRadioTransport`, `InMemoryStorage`) for unit tests.
  - Property-based testing (Kotest) is preferred for codec and state machine logic.
- **Commits:** We use the Developer Certificate of Origin (DCO). Sign every commit with `git commit -s`.

## Key Files & Directories
- `docs/SPEC.md`: The authoritative implementation plan and architecture bible.
- `docs/protocol.md`: The wire-level protocol reference (PhoneAPI).
- `core/`: The main SDK engine and public facade.
- Protobuf types: the published `org.meshtastic:protobufs` Maven artifact (no vendored `proto/` dir or submodule); pinned in `gradle/libs.versions.toml`.
- `build-logic/`: Custom Gradle convention plugins for KMP, Android, and Publishing.
- `gradle/libs.versions.toml`: The single source of truth for all dependencies and versions.
