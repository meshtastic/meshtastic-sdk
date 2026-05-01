# Consumer integration guides

> Recipes for common host-app integration patterns. These are
> orthogonal to the SDK itself — pick the ones relevant to your
> platform / DI framework / UI stack.

| Guide | When to read |
|---|---|
| [`reactive-lifecycle-management.md`](./reactive-lifecycle-management.md) | You're collecting `RadioClient.nodes` / `events` / `connection` from any UI lifecycle (Android Fragment, Compose, SwiftUI). Avoid leaks and missed updates. |
| [`hilt-integration.md`](./hilt-integration.md) | Android app using Hilt. Where to bind `RadioClient`, scoping, multi-radio session handling. |
| [`mvvm-integration.md`](./mvvm-integration.md) | Android app using MVVM + Compose + StateFlow. Wiring SDK flows into `ViewModel` state. |
| [`r8-proguard-setup.md`](./r8-proguard-setup.md) | Android release builds with R8 / Proguard. Required `-keep` rules for proto reflection, Wire, SqlDelight drivers. |
| [`error-handling.md`](./error-handling.md) | You're deciding what to retry, what to surface, and how to write exhaustive `when` over `SendFailure` / `MeshEvent` / `MeshtasticException`. Companion to [`../error-taxonomy.md`](../error-taxonomy.md). |

## Scope

These guides cover *consumer-side* concerns. SDK-internal concerns
(threading model, engine actor, transport isolation) live in
[`../architecture/`](../architecture/).

If you wish to add a guide, follow the existing format: short Problem /
Solution / Example / Pitfalls structure. PRs welcome.

## Related

- [`../api-reference.md`](../api-reference.md) — full Kotlin signatures
- [`../integration-guide.md`](../integration-guide.md) — platform setup (BLE permissions, USB host, `Context` wiring)
- [`../samples.md`](../samples.md) — runnable samples in `samples/`
