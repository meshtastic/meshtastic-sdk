# Module storage-sqldelight

SQLDelight-backed persistent storage for the Meshtastic KMP SDK. Implements the
`StorageProvider` interface from `:core` to persist node info, channels, config, the
admin session passkey and per-node last-heard times across sessions.

Uses [SQLDelight](https://cashapp.github.io/sqldelight/) for type-safe SQL with KMP
drivers on all supported targets.

Supported targets and drivers:
- Android — `AndroidSqliteDriver`
- JVM — `JdbcSqliteDriver`
- iOS (arm64, x64, Simulator arm64) — `NativeSqliteDriver`

On Android, set `org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder.context = appContext`
once in `Application.onCreate()` (or any `Context`-providing entry point) before the first
call to `SqlDelightStorageProvider.activate(...)`. There is no `init(...)` method.

Construction: `SqlDelightStorageProvider(baseDir: String = "")`. An empty `baseDir`
uses an in-memory database (a fresh one per activation, lost on close); a non-empty value
keeps one SQLite file per radio (an opaque `<dbId>.db`) plus a `.meshtastic-index` file in
`$baseDir` mapping transport identities and NodeNums to those ids, so the same radio reached
over BLE, TCP or serial shares one database. On the JVM the database files are
`$baseDir/<dbId>.db`; on Android and Apple they are in the platform driver's default databases
directory (see `docs/architecture/storage.md`).

Key packages:
- `org.meshtastic.sdk.storage.sqldelight` — `SqlDelightStorageProvider`, `AndroidContextHolder` (Android only)
