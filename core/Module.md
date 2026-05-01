# Module core

Core Meshtastic KMP client SDK. Provides the primary `RadioClient` API for connecting to
and communicating with Meshtastic mesh radio nodes over any supported transport.

`:core` is transport-agnostic — it depends only on `:proto` (Wire-generated message types)
and the Kotlin standard library. Transport implementations (BLE, TCP, Serial) are separate
modules that plug in at application-wiring time.

All observable state is exposed as `StateFlow`; all inbound packet streams as `Flow<MeshPacket>`.

Key packages:
- `org.meshtastic.sdk` — Public `RadioClient`, builders, state types, transport SPI, storage SPI, logging hook
- `org.meshtastic.sdk.internal.*` — Engine actor (single-writer, see ADR-002) and codec; not part of the API surface
