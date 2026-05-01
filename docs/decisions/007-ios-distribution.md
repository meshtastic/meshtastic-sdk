# ADR 007 — iOS distribution: KMMBridge

**Status:** Accepted
**Date:** 2026-04-17
**Deciders:** SDK leads
**Supersedes:** none
**Related:** [`../SPEC.md`](../SPEC.md) §5, §6 Phase 3 + Phase 5; [`./003-tooling.md`](./003-tooling.md); [`../samples.md`](../samples.md) `samples/ios-app`

---

## Context

The SDK targets `iosArm64`, `iosX64`, `iosSimulatorArm64` and must ship to Swift consumers as an XCFramework with a Swift Package Manager (SPM) entry point. There are three commonly-adopted distribution flows in the KMP ecosystem:

1. **KMMBridge** (Touchlab's flow, embedded in Touchlab's KMP-from-scratch tooling). A Gradle plugin that builds the XCFramework, uploads it to a binary host (S3, GitHub Packages, GCS, …), and updates a sibling SPM-package repo's `Package.swift` with a versioned URL + checksum on every release. Consumers pin a SPM version.
2. **Hand-rolled XCFramework + SPM repo.** Build the XCFramework with `assembleXCFramework`, upload to GitHub release artifacts, hand-edit a `Package.swift` in a sibling repo to point at the new download URL + SHA. Same end-state as KMMBridge but every step manual.
3. **CocoaPods.** Kotlin Multiplatform's first-party `cocoapods` plugin generates a `.podspec`. Still mainstream in many Apple codebases but losing ground to SPM, has slow integration with Xcode 15+, and Touchlab themselves migrated off it.

`Meshtastic-Apple` (the flagship iOS app, our likely first downstream consumer) uses SPM exclusively. So does the broader iOS ecosystem in 2026. CocoaPods adds friction for no payoff.

The hand-rolled flow works but the upload + checksum + `Package.swift` edit cycle is exactly the kind of release plumbing that breaks subtly and silently. KMMBridge automates it and is widely adopted (Touchlab, JetBrains-recommended pattern, used by Coil, SQLDelight, Kable for their iOS distribution).

Sibling library [`meshtastic/mqtt-client`](https://github.com/meshtastic/mqtt-client) ships iOS via Vanniktech's Maven publish only — it doesn't yet have a SPM story, so we're making the org-wide call here.

## Decision

**Adopt KMMBridge for iOS distribution.** Specifically:

- `:core`, `:transport-ble`, `:transport-tcp`, `:storage-sqldelight` participate in an `iosArm64 + iosX64 + iosSimulatorArm64` XCFramework named `RadioClient.xcframework`.
- A sibling repo `meshtastic/meshtastic-sdk-spm` holds the `Package.swift`. KMMBridge updates it on every release.
- Consumers add the SPM dependency to their Xcode project: `https://github.com/meshtastic/meshtastic-sdk-spm`, pinned to a tag matching this repo's git tag.
- Apply [SKIE](https://skie.touchlab.co/) (also Touchlab) on top of the iOS targets to improve Swift bridging for sealed classes, suspend functions returning sealed types, `Flow`, default args, and exhaustive `switch`. Pinned in `gradle/libs.versions.toml`; configuration documented in `SPEC.md` §5.

### What KMMBridge automates

1. `assembleXCFramework` (release flavor) on every published tag.
2. Uploads the resulting `.xcframework.zip` to the configured binary host (default for us: GitHub Packages on this repo).
3. Computes SHA-256, edits the SPM repo's `Package.swift` to point at the new URL + checksum, commits, tags.

### Acceptance criteria (Phase 5)

Listed in `SPEC.md` §6 Phase 5:

- `samples/ios-app` consumes the SPM package and compiles via `xcodebuild` on a CI runner.
- SKIE-export validation: `RadioClient`, `MessageHandle`, `SendState`, `SendFailure`, `MeshEvent`, `NodeChange`, `ConnectionState`, `TransportSpec`, `MeshtasticException`, `AdminResult` each appear in the generated SKIE Swift surface as natural Swift types (sealed types as enums; suspends as `async throws`; `Flow` as `AsyncSequence`). Verified by a Swift integration test against the framework, run as part of the `samples/ios-app` build.

## Alternatives considered

- **Hand-rolled XCFramework + SPM repo** — rejected. Same outcome with more failure modes during release. KMMBridge is exactly this flow with the sharp edges removed.
- **CocoaPods.** Rejected. Org direction is SPM; consumer base in 2026 is overwhelmingly SPM.
- **Both KMMBridge AND CocoaPods.** Rejected unless an adopter actually requests it. Don't ship distribution channels we can't keep healthy.
- **Skip SKIE; ship raw KMP→Obj-C interop.** Rejected. Sealed hierarchies, suspend-returning-sealed, value classes, and `Flow` all degrade significantly without SKIE; iOS adopters would feel the API is second-class. SKIE is mature, BSD-3-Clause-licensed (GPL-compatible), and is the de-facto standard.

## Consequences

- A second repo (`meshtastic-sdk-spm`) under the org, kept in lockstep with releases. KMMBridge owns the lockstep so this is mostly invisible.
- A new GitHub Packages binary host (or alternative) that consumers' Xcode projects fetch the XCFramework from. Document the host URL in the README iOS section once chosen.
- `gradle/libs.versions.toml` pins `kmmbridge` and `skie`. Renovate updates both.
- The SPM consumer experience matches what `Meshtastic-Apple` already expects, removing one objection to the SDK becoming the eventual protocol layer for that app.
- Phase 0's `build-logic/convention/` gains an `MeshtasticIosFrameworkPlugin` that wires `Framework { baseName = "RadioClient" }` + KMMBridge config. Phase 3 produces the first downloadable XCFramework; Phase 5 publishes the first SPM-tagged release alongside `0.1.0` Maven coordinates.
- If KMMBridge is ever abandoned or breaks, falling back to the hand-rolled flow is straightforward: same artifacts, same SPM repo, just manual. Low lock-in.
