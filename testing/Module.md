# Module testing

Testing utilities and fakes for the Meshtastic KMP SDK. Provides in-memory transport
and storage fakes, test builders for common message types, and coroutine test helpers
for use in unit tests across all modules.

This module is published so that SDK consumers can write tests against their own
Meshtastic-based code without depending on real transports or hardware.

Add as a `testImplementation` dependency:

    commonTest.dependencies {
        implementation("org.meshtastic:sdk-testing:<version>")
    }

Key packages:
- `org.meshtastic.sdk.testing` — `FakeRadioTransport` (in-memory `RadioTransport` whose `frames()` flow is driven by tests), `InMemoryStorage` (volatile `DeviceStorage`), `InMemoryStorageProvider` (zero-config `StorageProvider` for tests), and `TestClock` (deterministic `kotlin.time.Clock` for time-sensitive tests — advance virtual time without `runTest`'s scheduler)
