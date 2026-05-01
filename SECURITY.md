# Security policy

## Reporting a vulnerability

**Do not open a public GitHub issue.** Use one of:

- File a private [GitHub Security Advisory](https://github.com/meshtastic/meshtastic-sdk/security/advisories/new) on this repo.
- Email the Meshtastic security address (see [meshtastic.org](https://meshtastic.org)).

We aim to acknowledge reports within 5 business days and to ship a fix or mitigation within 90 days, depending on severity. We will credit you in the advisory unless you prefer to remain anonymous.

## Supported versions

`meshtastic-sdk` is pre-1.0. Only the latest published release receives security fixes; there is no LTS branch. Once 1.0 ships, we will publish a support window in this file.

## Scope

In scope:

- The SDK code (`:core`, `:proto`, `:transport-*`, `:storage-sqldelight`, `:testing`).
- Build infrastructure (`build-logic/`, GitHub Actions workflows, dependency sources).
- The contents of the vendored `meshtastic/protobufs` submodule **only insofar as we generate code from it** — schema-level vulnerabilities should be reported upstream.

Out of scope (report to the upstream owner):

- The Meshtastic firmware (`meshtastic/firmware`).
- The `Meshtastic-Android` and `Meshtastic-Apple` flagship apps.
- Sibling library `meshtastic/mqtt-client`.
- Third-party transports the SDK depends on (Kable, Ktor, jSerialComm, usb-serial-for-android, SQLDelight).

## What's in scope vs. out of scope, in detail

See [`docs/security.md`](docs/security.md). In short: the SDK is responsible for protocol-correct framing, handshake handling, channel-key handling, and not leaking key material in logs. End-to-end encryption is a property of the firmware + channel keys; the SDK ferries `MeshPacket.encrypted` blobs and decrypts on receive but does not invent crypto.

## Disclosure

After a fix ships, we publish the advisory with a CVE ID where applicable. Coordinated disclosure preferred; we will work with you on a timeline.
