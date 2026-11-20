# Security policy

## Reporting a vulnerability

Do not open a public GitHub issue. Use one of:

- A private [GitHub Security Advisory](https://github.com/meshtastic/meshtastic-sdk/security/advisories/new) on this repository.
- The Meshtastic security contact listed on [meshtastic.org](https://meshtastic.org).

Maintainers aim to acknowledge a report within five business days and to ship a fix or mitigation within 90 days, depending on severity. The advisory credits the reporter unless they prefer to stay anonymous.

## Supported versions

The SDK is pre-1.0. Only the latest published release receives security fixes, and there is no long-term support branch. A support window will be published here with 1.0.

## Scope

In scope:

- The SDK modules: `:core`, the `:transport-*` modules, `:storage-sqldelight` and `:testing`.
- Build infrastructure: `build-logic/`, the GitHub Actions workflows and dependency sources.
- Code the SDK generates or handles from the `org.meshtastic:protobufs` schema. Report schema-level vulnerabilities to [`meshtastic/protobufs`](https://github.com/meshtastic/protobufs).

Out of scope, so report to the upstream owner:

- The Meshtastic firmware (`meshtastic/firmware`).
- The `Meshtastic-Android` and `Meshtastic-Apple` clients.
- The sibling library `meshtastic/MQTTastic-Client-KMP`.
- Third-party libraries the SDK depends on (Kable, Ktor, jSerialComm, usb-serial-for-android, JmDNS, SQLDelight).

[`docs/security.md`](docs/security.md) has the detail. The SDK is responsible for protocol-correct framing, handshake handling, channel-key handling and keeping key material out of logs. End-to-end encryption is a property of the firmware and the channel keys: the SDK carries `MeshPacket.encrypted` payloads and decrypts on receive, but implements no cryptography of its own.

## Disclosure

After a fix ships, the advisory is published, with a CVE ID where one applies. Disclosure is coordinated with the reporter on a timeline.
