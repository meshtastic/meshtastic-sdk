# SKIE and iOS framework integration test

Compiling the iOS sample is the integration test for the SKIE-exported surface.

`samples/parity-app/iosApp/iosApp/ContentView.swift` calls `MainViewControllerKt.MainViewController()`, which SKIE generates from `samples/parity-app/src/iosMain/kotlin/org/meshtastic/sample/MainViewController.kt`. If SKIE drops the export, generates the wrong signature or fails to apply, the Xcode build fails. CI's `test-ios` job runs these two steps:

```shell
./gradlew :samples:parity-app:linkReleaseFrameworkIosSimulatorArm64
xcodebuild -project samples/parity-app/iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

## What the build checks

- `MainViewControllerKt.MainViewController()` returns a `UIViewController`, so SKIE bridges the Kotlin `fun MainViewController(): UIViewController` return type correctly.
- The app embeds `ComposeApp.framework`, so the static framework export works.

## What it doesn't check

No `XCTestCase` target asserts that each SDK type SKIE exports (`RadioClient`, `MessageHandle`, `SendState`, `SendFailure`, `MeshEvent`, `NodeChange`, `ConnectionState`, `MeshtasticException`, `AdminResult`) appears under its expected Swift name with the expected signatures. That needs either a test target in `iosApp.xcodeproj` or a separate Swift package depending on an SPM-distributed XCFramework, and the SDK publishes no XCFramework ([ADR-007](../../../../docs/decisions/007-ios-distribution.md), [`docs/roadmap.md`](../../../../docs/roadmap.md)).
