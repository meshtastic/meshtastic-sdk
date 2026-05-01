# Protocol Reference Notes

Canonical reference material for the Meshtastic device PhoneAPI wire protocol, extracted from the five authoritative upstream sources. Each file in this directory is a **behavioral / structural summary** of one upstream repository — never a verbatim copy of code (the upstream repos are GPL-3.0; the protocol itself is not copyrightable, but their implementations are).

These notes feed the canonical synthesis at [`../protocol.md`](../protocol.md), which is the single source of truth that the SDK implementation targets.

| File | Source | Status |
|---|---|---|
| [`meshtastic-protobufs-spec.md`](meshtastic-protobufs-spec.md) | [`meshtastic/protobufs`](https://github.com/meshtastic/protobufs) | Schema inventory (messages, enums, constants) |
| [`meshtastic-firmware-behavior.md`](meshtastic-firmware-behavior.md) | [`meshtastic/firmware`](https://github.com/meshtastic/firmware) | Device-side behavioral spec (framing, FSM, GATT, encryption) |
| [`meshtastic-android-protocol-notes.md`](meshtastic-android-protocol-notes.md) | [`meshtastic/Meshtastic-Android`](https://github.com/meshtastic/Meshtastic-Android) | Android phone-side reference behavior |
| [`meshtastic-apple-protocol-notes.md`](meshtastic-apple-protocol-notes.md) | [`meshtastic/Meshtastic-Apple`](https://github.com/meshtastic/Meshtastic-Apple) | Apple phone-side reference behavior |
| [`meshtastic-docs-site-notes.md`](meshtastic-docs-site-notes.md) | [`meshtastic/meshtastic`](https://github.com/meshtastic/meshtastic) | Official Docusaurus docs site — curated human-readable spec (HTTP API, channels, roles, regions, presets) |

## Rules

1. **No verbatim source quotes from GPL-3.0 upstream code.** Behavior described in our own words; constants, UUIDs, and protocol field names are fact, not implementation.
2. **Every claim cites a file path** in the upstream repo it came from.
3. When upstream behavior conflicts (Apple vs Android), the conflict is flagged and resolved in `protocol.md` against the firmware ground truth.
4. These notes are a snapshot — re-run the research agents (see `docs/decisions/006-protocol-research-process.md` if it exists) when upstream protocol versions advance.
