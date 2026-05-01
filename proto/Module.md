# Module proto

Wire-generated Kotlin types for the Meshtastic protobuf protocol. All message types,
enums, and service definitions used in the Meshtastic mesh radio protocol are generated
from the upstream `.proto` files vendored in `proto/src/protobufs/`.

This module is a pure generated-code layer. Consumers use it to construct and inspect
`MeshPacket`, `ToRadio`, `FromRadio`, and the full suite of Meshtastic admin/telemetry/
channel message types. Application code should depend on `:core`, which re-exports this
module's public API.

The generated types use [Square Wire](https://square.github.io/wire/) and are fully
KMP-compatible across JVM, Android, iOS, Linux, and all other supported targets.

Key packages:
- `org.meshtastic.proto` — Full Meshtastic protobuf type tree (`MeshPacket`, `ToRadio`, `FromRadio`, admin, telemetry, paxcounter, …)
