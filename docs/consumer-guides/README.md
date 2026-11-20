# Consumer integration guides

Recipes for integration patterns in host apps. Pick the ones that match your platform, DI framework, and UI stack.

| Guide | When to read |
|---|---|
| [`reactive-lifecycle-management.md`](./reactive-lifecycle-management.md) | You're collecting `RadioClient.nodes` / `events` / `connection` from any UI lifecycle (Android Fragment, Compose, SwiftUI). Avoid leaks and missed updates. |
| [`hilt-integration.md`](./hilt-integration.md) | Android app using Hilt. Where to bind `RadioClient`, scoping, multi-radio session handling. |
| [`mvvm-integration.md`](./mvvm-integration.md) | Android app using MVVM + Compose + StateFlow. Wiring SDK flows into `ViewModel` state. |
| [`r8-proguard-setup.md`](./r8-proguard-setup.md) | Android release builds with R8 / Proguard. Required `-keep` rules for proto reflection, Wire, SqlDelight drivers. |
| [`error-handling.md`](./error-handling.md) | Deciding what to retry and what to surface, with exhaustive `when` over `SendFailure`, `AdminResult`, `MeshEvent`, and `MeshtasticException`. Companion to [error-taxonomy.md](../error-taxonomy.md). |

These guides cover the host side. The SDK's internals (threading model, engine actor, transport isolation) are in [architecture/](../architecture/).

## Related

- [api-reference.md](../api-reference.md): signatures
- [integration-guide.md](../integration-guide.md): platform setup, such as Bluetooth permissions, USB host, and the Android `Context`
- [samples.md](../samples.md): runnable samples in `samples/`
