# Storage architecture

> How `DeviceStorage` is shaped, what the SDK persists, and what is
> intentionally **the consumer's responsibility**. Authoritative source
> for the storage interface contract is the KDoc on
> [`DeviceStorage`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Storage.kt);
> this doc explains the rationale and the deliberate gaps.

## What the SDK persists

Databases are **keyed by radio NodeNum**, not transport identity: one physical
radio = one database, regardless of whether you reach it over BLE, TCP, or
serial. The built-in [`SqlDelightStorageProvider`](../../storage-sqldelight/src/commonMain/kotlin/org/meshtastic/sdk/storage/sqldelight/SqlDelightStorageProvider.kt)
maintains an index file (`<baseDir>/.meshtastic-index`) that maps both
[`TransportIdentity`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt)
aliases **and** NodeNums to opaque per-radio DB ids. The on-disk shape is:

```
<baseDir>/
  .meshtastic-index                ← identity ↔ nodeNum ↔ dbId bindings
  <dbId>.db                        ← one SQLDelight DB per radio (JVM)
```

Where the `<dbId>.db` files live depends on the platform driver:

| Platform | Database files |
|---|---|
| JVM | `<baseDir>/<dbId>.db` |
| Android | `AndroidSqliteDriver` opens the bare file name, so the files are in the app's default databases directory (`Context.getDatabasePath("<dbId>.db")`), not in `baseDir`. |
| Apple (iOS / macOS) | `NativeSqliteDriver` (SQLiter) opens the bare file name in its default `databases` directory under the app's Application Support directory, not in `baseDir`. |

So on Android and Apple, `baseDir` holds only the index. Deleting `baseDir`
there forgets the bindings but leaves the database files behind; the next
activation allocates new ones. The dbIds are random 128-bit values, so two
providers with different `baseDir`s never pick the same file. The location is
kept as is because moving it would orphan every existing database (the index
would point at a new, empty file).

`baseDir = ""` selects in-memory mode on every platform: each activation gets
its own in-memory database, no index and no file.

On `activate(identity)`:

1. If the index has seen this `identity` before → open `<dbId>.db` directly.
2. Otherwise the returned storage is **deferred**: pre-handshake reads return
   empty defaults and writes buffer into memory until the engine calls
   `recordOwnNode(nodeNum, …)`. At that point the provider either adopts
   an existing `<dbId>.db` for that NodeNum (the radio was previously
   reached via another transport) or allocates a fresh opaque `dbId`,
   replays the buffered state, and writes both bindings to the index.

Per-row contents (SQLDelight backend) are captured in [`Mesh.sq`](../../storage-sqldelight/src/commonMain/sqldelight/org/meshtastic/sdk/storage/sqldelight/internal/Mesh.sq):

| Concept | Table / mechanism | Purpose |
|---|---|---|
| Nodes (NodeDB cache) | `nodes` | Surface as `RadioClient.nodes` `Flow<NodeChange>` after seed-load. |
| Channels | `channels` | Restore PSKs + names across reconnects. PSK column holds ciphertext only — wrap via the consumer-supplied `KeyVault` per ADR-005. |
| Device config bundle | `config_blobs` | Replays `LocalConfig` / `LocalModuleConfig` / `MyNodeInfo` / `DeviceMetadata`. |
| Session metadata | `sessions` (key-value) | Tracks `(NodeNum, firmwareVersion)` for factory-reset detection. |
| SQLite tuning | PRAGMAs | See [ADR-014](../decisions/014-storage-pragmas.md). |

## Schema versioning

The SQLDelight schema is currently at **version 3**. Migrations live
under [`storage-sqldelight/src/commonMain/sqldelight/databases/`](../../storage-sqldelight/src/commonMain/sqldelight/databases/)
using the SQLDelight 2.x naming convention — `<n>.sqm` migrates *from*
version `n` to `n+1` — and are verified at build time
(`verifyMigrations = true`):

| File | Transition | What it does |
|---|---|---|
| `1.sqm` | v1 → v2 | Drops the orphaned `messages` table (see "Message history" below). |
| `2.sqm` | v2 → v3 | Adds `nodes.last_heartbeat_at INTEGER` so engine-observed presence (last frame heard from a node) survives process death. Populated by the engine on flush; `NULL` for any pre-migration row until the engine next observes that node. |

Existing on-disk databases migrate automatically the next time the SDK
opens them; in-memory databases start at the latest schema. **No
consumer action is required.**

Every platform driver is built with `MeshDatabase.Schema`, which tracks the
version in `PRAGMA user_version`. Older JVM builds created the tables
without setting `user_version`, so their files stayed at 0 and were never
migrated. When the JVM driver finds such a file (tables present,
`user_version = 0`), it infers the version from the tables (`messages`
still present → v1, no `nodes.last_heartbeat_at` → v2, otherwise v3),
stamps it, and then runs the remaining migrations.

## Message history is **not** stored

Schema v1 contained an unused `messages` table. Schema v2 (migration
`1.sqm`) drops it; existing on-disk databases migrate automatically
when the SDK opens them.

**Why:** message history is a product/UX decision, not a protocol one.
Different consumers have wildly different needs (no history at all,
ring-buffer of N, full archive, server-side store, etc.) and any policy
the SDK picked would be wrong for someone. The SDK exposes packets via
the `RadioClient.packets` flow; consumers persist whatever subset they
need on their side.

If you previously relied on the orphaned `messages` table, see the
[CHANGELOG breaking-changes section](../../CHANGELOG.md#unreleased) for
guidance.

## Identity-rebind detection (S-P0-2)

`recordOwnNode(nodeNum, firmwareVersion)` is called by the engine
immediately after handshake. The contract (per
[`DeviceStorage` KDoc](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Storage.kt))
is:

1. First call for an identity → store `(nodeNum, firmwareVersion)`.
2. Subsequent calls where `nodeNum` matches → no-op.
3. Subsequent calls where `nodeNum` **differs** (factory reset, radio
   swap, hostname re-pointed at a different physical device) →
   atomically `clear()` everything and store the new tuple.

The SqlDelight implementation honours all three; the engine then
rebuilds `MeshState` from the fresh handshake payload, so consumers
never observe stale NodeDB rows leaked across an identity boundary.

### Consumer-observable signal (R-9)

When the engine detects that the device reports a different `NodeNum`
than the one previously persisted, it emits
[`MeshEvent.IdentityRebound(previousNodeNum, newNodeNum, reason)`][rebind]
on `RadioClient.events` **before** invoking
`DeviceStorage.recordOwnNode` (which performs the `clear()`) and
**before** the subsequent fresh `NodeChange.Snapshot` lands on
`RadioClient.nodes`. Subscribers can snapshot any in-memory state they
care about between observing the event and the next snapshot emission.

Detection is performed by the engine actor: at `connect()` time, the
engine calls `storage.loadConfig()` once and remembers the previously
recorded `MyNodeInfo.my_node_num`. When Stage 2 of the handshake
commits the new `MyNodeInfo`, the engine compares the two values
synchronously on the actor and emits the event before any state
mutation that consumers can observe.

[rebind]: ../../core/src/commonMain/kotlin/org/meshtastic/sdk/Node.kt

See also: [`error-taxonomy.md` § identity rebind](../error-taxonomy.md#identity-rebind).

## Thread-safety expectations

The engine actor itself is single-writer (see
[ADR-002](../decisions/002-architecture.md) and
[`engine-actor.md`](./engine-actor.md)), but it does **not** serialise its
storage calls. Writes run in coroutines the actor launches so disk I/O never
blocks it: the post-handshake persist (`recordOwnNode`, `saveNode`,
`saveHeartbeat`, `saveChannels`, `saveConfig`), the `saveConfig` after a
config edit or an external config change, and the periodic heartbeat flush.
These can overlap with each other and with calls the actor makes directly
(`loadConfig` / `loadHeartbeats` at connect, the final heartbeat flush and
`close()` at disconnect). There is no ordering guarantee between two launched
blocks.

So a `DeviceStorage` **must tolerate concurrent calls** from several
coroutines and threads. Each call should be atomic on its own; a later call
may still land before an earlier one. Implementations may use internal locks
for this; that is storage-side code, not engine state, so ADR-002's rule
against locks does not apply.

The built-in storage does this with a `Mutex` per storage: `SqlDelightStorage`
runs one call at a time, and the node-keyed wrapper returned by
`SqlDelightStorageProvider` holds its own lock across the first
`recordOwnNode`, so a write that races the first bind is either buffered and
replayed or goes straight to the database, never lost. `close()` is not
suspending and does not take the lock.

### Main-safety of suspend methods

The built-in `SqlDelightStorage` wraps every suspend method in
`withContext(dispatcher)` so the underlying blocking driver calls
(`JdbcSqliteDriver`'s thread-local pool on JVM, `AndroidSqliteDatabase`
syscalls, `sqlite3_step` on Apple) never run on the caller's
dispatcher — typically the engine actor pinned to `Dispatchers.Default`.

The default `dispatcher` is a platform-specific view with
`limitedParallelism(4, "sqldelight-storage")`:

| Platform | Default |
|---|---|
| JVM / Android | `Dispatchers.IO.limitedParallelism(4, …)` |
| Apple (iOS / macOS) | `Dispatchers.Default.limitedParallelism(4, …)` — `Dispatchers.IO` on Native is shadowed by an internal member, unreachable from shared code. `NativeSqliteDriver` serialises through a single connection anyway, so the dispatcher identity has minimal effect on throughput. |

Custom `DeviceStorage` implementations SHOULD follow the same
main-safety rule: blocking IO must not run on the caller's dispatcher.

## Failure semantics

A `MeshtasticException.StorageUnavailable` raised from any
`DeviceStorage` method during a connected session triggers
`MeshEvent.ProtocolWarning` + one engine-internal retry. A second
failure escalates to disconnect-and-reconnect (see
[`error-taxonomy.md`](../error-taxonomy.md)).

## Related

- [`Storage.kt`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Storage.kt) — interface + KDoc contract
- [`Mesh.sq`](../../storage-sqldelight/src/commonMain/sqldelight/org/meshtastic/sdk/storage/sqldelight/internal/Mesh.sq) — schema
- [ADR-014](../decisions/014-storage-pragmas.md) — SQLite PRAGMAs
- [ADR-005](../decisions/005-api-shape.md) — `KeyVault`, three response shapes
- [`error-taxonomy.md`](../error-taxonomy.md) — failure mapping
- [`roadmap.md`](../roadmap.md) — deferred items including R-9 (rebind warning), R-10 (message history)
