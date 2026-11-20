# Module testing

Test doubles for code built on the Meshtastic KMP SDK, so a consumer can test against `RadioClient` without a transport or hardware.

Add it to your test source set:

    commonTest.dependencies {
        implementation("org.meshtastic:sdk-testing:<version>")
    }

`:testing` brings `:core`, Turbine and kotlinx-coroutines-test with it through `api`.

Key packages:
- `org.meshtastic.sdk.testing`: `FakeRadioTransport`, an in-memory `RadioTransport` driven by the test. It replays pre-loaded frames, injects packets and canned admin, telemetry, routing and store-and-forward responses, records outbound frames, and with `autoHandshake = true` answers the handshake so `RadioClient.connect()` completes. `TestClock` is a `kotlin.time.Clock` the test advances by hand. `FromRadio.toFrame()` wraps a message as a framed `Frame`. `InMemoryStorage` and `InMemoryStorageProvider` live in `:core` as `org.meshtastic.sdk.InMemoryStorage(Provider)`; the aliases here are deprecated.
