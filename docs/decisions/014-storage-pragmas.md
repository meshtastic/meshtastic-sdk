# ADR-014: SQLite PRAGMA Configuration for Storage

**Date:** 2025
**Status:** Accepted
**Decision Makers:** Core team
**Context:** Storage best-practices audit — P1 findings

## Problem

By default, SQLite uses `FULL` synchronous mode and disabled WAL (write-ahead logging), which leads to:
- **Reduced concurrency:** Without WAL, readers block writers and vice versa.
- **Reduced performance:** `FULL` synchronous mode forces disk flushes on every commit.
- **Less suitable for mobile/messaging apps:** These use cases tolerate minor data loss on crash (e.g., unsent messages) in favor of better UX and throughput.

## Decision

**Enable these pragmas on all platforms (JVM, Android, iOS/macOS):**

1. **`PRAGMA journal_mode=WAL;`**
   Enables Write-Ahead Logging, which:
   - Allows concurrent reads while a write is in progress
   - Improves throughput for apps with mixed read/write workloads
   - Is the default recommendation in modern SQLite documentation
   - WAL is persisted in the database file header, so it is inherited by every
     connection opened against a given file.

2. **`PRAGMA synchronous=NORMAL;`**
   Sets synchronous level to `NORMAL` instead of `FULL`:
   - **FULL (default):** Syncs to disk after every commit (slowest, safest)
   - **NORMAL:** Syncs after commits in a batch; acceptable data loss on OS/app crash
   - **OFF:** No syncs; data loss on power failure or app crash

   For a messaging app, NORMAL is acceptable because:
   - Unsent messages can be recovered or re-sent
   - Config changes can be re-applied on reconnect
   - Node info is ephemeral
   - Trade-off: Better UX + throughput vs. tolerance for minor data loss

3. **`PRAGMA foreign_keys=ON;`**
   SQLite defaults FK constraints to off. Our schema has no cross-table FKs today,
   but enabling this is cheap, protects any future schema evolution, and mirrors
   Room's default so behaviour is predictable across platforms.

## Implementation

Per-connection PRAGMAs (`synchronous`, `foreign_keys`) are NOT persisted in the
database file — they must be applied for every connection the driver opens.
`journal_mode=WAL` is persisted in the file header, so setting it once suffices
for a file-backed database. The implementation therefore differs per driver:

- **JVM (`JdbcSqliteDriver`)** — uses a `ThreadLocal` connection pool; a post-ctor
  `driver.execute("PRAGMA …")` only affects the creating thread. We pass the
  PRAGMAs via xerial's JDBC URL properties
  (`synchronous=NORMAL`, `foreign_keys=true`, `journal_mode=WAL`) so every
  connection the pool opens inherits them.
  See `storage-sqldelight/src/jvmMain/…/SqlDelightStorageProvider.jvm.kt`.

- **Android (`AndroidSqliteDriver`)** — the framework manages a
  `SupportSQLiteOpenHelper` connection pool. We pass an
  `AndroidSqliteDriver.Callback` whose `onConfigure(db)` is invoked per-connection
  and issues `db.setForeignKeyConstraintsEnabled(true)` plus the WAL and
  synchronous pragmas.
  See `storage-sqldelight/src/androidMain/…/SqlDelightStorageProvider.android.kt`.

- **iOS / macOS (`NativeSqliteDriver`)** — uses a single shared connection per
  driver instance, so the shared `applyStoragePragmas(driver, fileBacked)` helper
  in `commonMain` runs `driver.execute("PRAGMA …")` once after construction.
  See `storage-sqldelight/src/appleMain/…/SqlDelightStorageProvider.apple.kt`.

`StorageDriverTest` (jvmTest) asserts `journal_mode=wal` after a fresh
file-backed driver is created; other PRAGMAs are harder to assert from a
different thread because they are connection-scoped.

## Rationale

- **Consistency:** Same semantics across all platforms, even though the hook
  differs per driver.
- **Performance:** WAL + NORMAL synchronous mode reduce latency on storage
  operations.
- **Durability trade-off:** For a messaging app, the trade-off is acceptable;
  lost messages are recoverable.
- **Best practices:** SQLite documentation and community guidance recommend WAL
  for concurrent access patterns. Per-driver pragma application is the
  correctness-preserving idiom for each of our drivers' connection models.

## Related

- **ADR-005:** API shape and storage design
- **docs/error-taxonomy.md:** Data loss scenarios
- **docs/testing.md:** Storage layer testing

## References

- SQLite documentation: https://www.sqlite.org/wal.html
- SQLite PRAGMA synchronous: https://www.sqlite.org/pragma.html#pragma_synchronous
