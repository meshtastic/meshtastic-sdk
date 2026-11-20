# Module storage-sqldelight

SQLDelight-backed persistent storage for the Meshtastic KMP SDK. It implements `StorageProvider` from `:core` and keeps the NodeDB, channels, device config and session metadata across sessions. Message history isn't stored.

Drivers:
- Android: `AndroidSqliteDriver`
- JVM: `JdbcSqliteDriver`
- iOS (arm64, x64, Simulator arm64): `NativeSqliteDriver`

Construction: `SqlDelightStorageProvider(baseDir: String = "")`. An empty `baseDir` gives an in-memory database per activation, lost on close. Otherwise `baseDir` holds one `<dbId>.db` per node, keyed by NodeNum rather than transport, plus a `.meshtastic-index` file mapping transport identities and NodeNums to database ids. [`docs/architecture/storage.md`](../docs/architecture/storage.md) describes the layout and schema migrations.

On Android, set `org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder.context = appContext` once in `Application.onCreate()`, before the first `SqlDelightStorageProvider.activate(...)`. There is no `init(...)` method.

Key packages:
- `org.meshtastic.sdk.storage.sqldelight`: `SqlDelightStorageProvider`, and `AndroidContextHolder` on Android.
