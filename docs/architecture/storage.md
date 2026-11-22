# Storage architecture

This page explains how `DeviceStorage` is shaped, what the SDK persists, and what it leaves to the consumer. The KDoc on [`DeviceStorage`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Storage.kt) is the authoritative contract; this page gives the rationale and the deliberate gaps.

## What the SDK persists

Databases are keyed by the node, not the transport: one physical node has one database, whether it's reached over BLE, TCP or serial. The built-in [`SqlDelightStorageProvider`](../../storage-sqldelight/src/commonMain/kotlin/org/meshtastic/sdk/storage/sqldelight/SqlDelightStorageProvider.kt) keeps an index file (`<baseDir>/.meshtastic-index`) that maps both [`TransportIdentity`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Transport.kt) aliases and NodeNums to opaque per-node database ids:

```text
<baseDir>/
  .meshtastic-index                ← identity ↔ nodeNum ↔ dbId bindings
  <dbId>.db                        ← one SQLDelight DB per node
```

On `activate(identity)`:

1. If the index already maps this `identity`, the provider opens `<dbId>.db` directly.
2. Otherwise the returned storage is deferred: reads before the handshake return empty defaults and writes buffer in memory until the engine calls `recordOwnNode(nodeNum, …)`. The provider then adopts the existing database for that NodeNum (the node was reached before over another transport) or allocates a new `dbId`, replays the buffered state, and writes both bindings to the index.
3. When a later `recordOwnNode` reports a different NodeNum, the provider moves the NodeNum binding to the new value, so the old NodeNum no longer resolves to this database. Whether the contents are kept is covered in [Identity-rebind detection](#identity-rebind-detection-s-p0-2).

The tables are defined in [`Mesh.sq`](../../storage-sqldelight/src/commonMain/sqldelight/org/meshtastic/sdk/storage/sqldelight/internal/Mesh.sq):

| Concept | Table or mechanism | Purpose |
|---|---|---|
| NodeDB cache | `nodes` | Seeds `RadioClient.nodes` on connect. |
| Channels | `channels` | Restores names and PSKs across reconnects. PSKs are stored as received, unencrypted (see [`security.md`](../security.md#storage-at-rest)). |
| Config | `configs` | One row per config or module-config section, as raw proto bytes. |
| Session values | `session` (key-value) | `MyNodeInfo`, `DeviceMetadata`, the own NodeNum, firmware version and `device_id` used for rebind detection. |
| SQLite tuning | PRAGMAs | See [ADR-014](../decisions/014-storage-pragmas.md). |

## Schema versioning

The SQLDelight schema is at version 3. Migrations live under [`storage-sqldelight/src/commonMain/sqldelight/databases/`](../../storage-sqldelight/src/commonMain/sqldelight/databases/) with the SQLDelight 2.x naming convention (`<n>.sqm` migrates from version `n` to `n+1`) and are verified at build time (`verifyMigrations = true`):

| File | Transition | What it does |
|---|---|---|
| `1.sqm` | v1 → v2 | Drops the unused `messages` table (see "Message history is not stored"). |
| `2.sqm` | v2 → v3 | Adds `nodes.last_heartbeat_at INTEGER`, so engine-observed presence (the last frame heard from a node) survives process death. The engine fills it on flush; a row migrated from v2 stays `NULL` until the engine next hears that node. |

Databases on disk migrate automatically when the SDK next opens them, and in-memory databases start at the latest schema. No consumer action is needed.

## Message history is not stored

Message history is a product decision, not a protocol one. Consumers want anything from no history to a ring buffer to a full archive or a server-side store, and any policy the SDK picked would be wrong for some of them. The SDK delivers packets on `RadioClient.packets`, and consumers persist what they need.

## Identity-rebind detection (S-P0-2)

The engine calls `recordOwnNode(nodeNum, firmwareVersion)` right after the handshake. The contract in the [`DeviceStorage` KDoc](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Storage.kt):

1. The first call for an identity stores `(nodeNum, firmwareVersion)`.
2. A later call with the same `nodeNum` does nothing.
3. A later call with a different `nodeNum` (factory reset, a swapped node, a hostname pointed at different hardware) atomically clears everything and stores the new tuple, except for the renumbered device described next.

The engine then rebuilds its state from the fresh handshake, so consumers never see NodeDB rows carried across an identity boundary.

### Renumbered device (same `device_id`)

Firmware 2.8 derives `my_node_num` from the public key, so a device that upgrades or regenerates its key reports a new NodeNum while staying the same hardware. `MyNodeInfo.device_id` is the stable hardware identifier. The engine passes it to the three-argument `recordOwnNode(nodeNum, firmwareVersion, deviceId)` only when it is trustworthy: exactly 16 bytes and not all zero. Firmware leaves it empty on hardware without a unique id and zeroes it for unauthenticated clients in lockdown mode.

When the NodeNum differs and the trusted incoming id equals the stored one, storage is not cleared. The row for the previous own NodeNum is removed, the new tuple is stored, and the fresh handshake supplies the new own-node row. In every other case (no stored id, no trusted incoming id, or different ids) storage is cleared as described earlier. The first connect that records an id into a database that had none still clears on a NodeNum change.

`SqlDelightStorage` keeps the id in the `session` table under `identity_device_id`; `InMemoryStorage` keeps it in a field. A custom `DeviceStorage` that overrides neither `recordOwnNode(nodeNum, firmwareVersion, deviceId)` nor `loadOwnDeviceId()` clears on every NodeNum change.

### Consumer-observable signal

When the node reports a NodeNum different from the persisted one, the engine emits [`MeshEvent.IdentityRebound(previousNodeNum, newNodeNum, reason)`][rebind] on `RadioClient.events`. It does so before calling `DeviceStorage.recordOwnNode` and before the fresh `NodeChange.Snapshot` reaches `RadioClient.nodes`, so subscribers can save any in-memory state between the event and the next snapshot.

The engine actor does the detection. At `connect()` it reads the previously recorded `MyNodeInfo.my_node_num` from `storage.loadConfig()` and the previous hardware id from `storage.loadOwnDeviceId()`. When Stage 2 commits the new `MyNodeInfo`, it compares them on the actor and emits the event before any state change consumers can observe. `reason` is `"identity_mismatch"` when storage is cleared and `"node_renumbered"` when the same trusted `device_id` came back under a new NodeNum and storage was kept. Session passkeys are dropped in both cases.

[rebind]: ../../core/src/commonMain/kotlin/org/meshtastic/sdk/Node.kt

See also: [`error-taxonomy.md` § identity rebind](../error-taxonomy.md#identity-rebind).

## Thread-safety expectations

The engine calls implementations only from its actor's single-writer context ([ADR-002](../decisions/002-architecture.md), [`engine-actor.md`](./engine-actor.md)). An implementation may lock internally for its own correctness, but the SDK never calls one `DeviceStorage` instance concurrently.

### Main-safety of suspend methods

`SqlDelightStorage` wraps every suspend method in `withContext(dispatcher)`, so blocking driver calls (`JdbcSqliteDriver`'s thread-local pool on JVM, `AndroidSqliteDatabase` syscalls, `sqlite3_step` on Apple) never run on the caller's dispatcher, which is usually the engine actor on `Dispatchers.Default`.

The default `dispatcher` is a fixed pool of four threads named `sqldelight-storage`:

| Platform | Default |
|---|---|
| JVM, Android | `Executors.newFixedThreadPool(4)` with daemon threads, as a coroutine dispatcher |
| iOS | `newFixedThreadPoolContext(4, "sqldelight-storage")`. `NativeSqliteDriver` serializes through one connection anyway, so the pool size barely affects throughput. |

A custom `DeviceStorage` should follow the same rule: blocking I/O must not run on the caller's dispatcher.

## Failure semantics

A storage failure while activating storage at `connect()` fails the connect with `MeshtasticException.StorageUnavailable`. A write that fails during a session (persisting the handshake snapshot, a heartbeat flush) puts the engine in degraded mode: it emits `MeshEvent.StorageDegraded(reason)` once, skips storage writes for the rest of the session, and keeps the session running from memory. The next `connect()` tries storage again. Some config writes fail silently and are only logged. See [`error-taxonomy.md`](../error-taxonomy.md).

## Related

- [`Storage.kt`](../../core/src/commonMain/kotlin/org/meshtastic/sdk/Storage.kt): the interface and its KDoc contract
- [`Mesh.sq`](../../storage-sqldelight/src/commonMain/sqldelight/org/meshtastic/sdk/storage/sqldelight/internal/Mesh.sq): the schema
- [ADR-014](../decisions/014-storage-pragmas.md): SQLite PRAGMAs
- [ADR-005](../decisions/005-api-shape.md): the three response shapes
- [`error-taxonomy.md`](../error-taxonomy.md): failure mapping
- [`roadmap.md`](../roadmap.md): deferred items, including R-10 (message history)
