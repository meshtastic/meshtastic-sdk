# Module storage-sqldelight

SQLDelight-backed persistent storage for the Meshtastic KMP SDK. Implements the
`StorageProvider` interface from `:core` to persist node info, channel config, and
message history across sessions.

Uses [SQLDelight](https://cashapp.github.io/sqldelight/) for type-safe SQL with KMP
drivers on all supported targets.

Supported targets and drivers:
- Android — `AndroidSqliteDriver`
- JVM — `JdbcSqliteDriver`
- iOS (arm64, Simulator arm64) — `NativeSqliteDriver`

On Android, set `org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder.context = appContext`
once in `Application.onCreate()` (or any `Context`-providing entry point) before the first
call to `SqlDelightStorageProvider.activate(...)`. There is no `init(...)` method.

Construction: `SqlDelightStorageProvider(baseDir: String = "")`. An empty `baseDir`
uses an in-memory database (per-process, lost on close); a non-empty value places
SQLite files at `$baseDir/<identity>.db`.

Key packages:
- `org.meshtastic.sdk.storage.sqldelight` — `SqlDelightStorageProvider`, `AndroidContextHolder` (Android only)
