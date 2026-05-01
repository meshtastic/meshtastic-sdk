# Migration Guide: `meshtastic-android` to `meshtastic-sdk` (Clean Break Strategy)

This document outlines the **Clean Break** migration path. Rather than building shims to preserve legacy `Meshtastic-Android` patterns (like AIDL, complex repository wrappers, and hybrid databases), this strategy radically simplifies the app by treating the `meshtastic-sdk` as the absolute single source of truth.

The goal is massive code deletion and direct UI-to-SDK binding.

---

## 0. Architectural Vision: Direct Binding

We are discarding the **Service-Pull** architecture and all its intermediate wrapping layers in favor of **Direct SDK Binding**. The app becomes a thin UI shell over the `RadioClient`.

### Before vs. After (Clean Break)
```mermaid
graph TD
    subgraph "Legacy Meshtastic-Android"
        A[UI/Compose] --> B[ViewModel]
        B --> C[NodeRepository / ServiceRepository]
        C --> D[MeshServiceOrchestrator]
        D --> E[SharedRadioInterfaceService]
        E --> F[Room Database (Bloated)]
    end

    subgraph "Clean Break Architecture"
        G[UI/Compose] --> H[ViewModel]
        H --> I[RadioClient (SDK)]
        I --> J[sdk-storage-sqldelight]
        H --> K[DataStore/Room (App Metadata: Favorites/Notes)]
    end
```

---

## Phase 1: Environment & Dependency Alignment
**Goal:** Prepare the build system to import the SDK and resolve the model clash.

1.  **`libs.versions.toml` Alignment:**
    *   Align Wire, Coroutines, and KMP versions with the SDK. Current pinned versions: Wire 6.2.0, Coroutines 1.10.2, Kotlin 2.3.21, Koin 4.2.1 — verify these match SDK requirements before bumping.
    *   Add `:sdk-core`, `:sdk-proto`, `:sdk-transport-ble`, `:sdk-transport-tcp`, `:sdk-transport-serial`, `:sdk-storage-sqldelight`, and `:sdk-testing` modules.
2.  **Model Swap (`core/model` to Wire):**
    *   The app has `core:model:NodeInfo`. The SDK uses `org.meshtastic.proto.NodeInfo` (Wire).
    *   Delete the custom `core/model` protobuf mappings (70 files, ~719 import sites across `app` and `feature` modules — this is the single largest mechanical task in the migration). Perform a surgical package rename across the `app` and `core` modules to use the generated Wire types directly. Keep in mind that Wire generates `snake_case` properties (e.g. `node.user.long_name`).

---

## Phase 2: One-Time Data Migration (Critical Step)
**Goal:** Prevent data loss for existing users when swapping the database.

Before we delete the old Room tables, we must migrate existing data (Nodes, Messages) to the SDK's SQLDelight storage.
1.  **Migration Mechanism:** Use a two-step approach:
    - **Step A — Schema change (Room Migration):** Bump Room to version 37. `MIGRATION_36_37` adds the new `NodeMetadata` table and does nothing else — schema changes only. Do NOT call SQLDelight from inside `migrate()`; the Room callback runs on a raw `SupportSQLiteDatabase` thread and cannot safely invoke the SDK's storage layer.
    ```kotlin
    val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS NodeMetadata (num INTEGER PRIMARY KEY, " +
                "isFavorite INTEGER NOT NULL DEFAULT 0, isIgnored INTEGER NOT NULL DEFAULT 0, notes TEXT)"
            )
            // Legacy tables are dropped in MIGRATION_37_38, after data transfer is confirmed.
        }
    }
    ```
    - **Step B — Data transfer (Application.onCreate coroutine):** On first boot after the version bump, run a coroutine guarded by a SharedPreferences flag. Read `NodeEntity` from Room, write to SQLDelight via `DeviceStorage.saveNode()`, mark the flag done. Show a splash/progress screen while this runs.
    ```kotlin
    if (!prefs.getBoolean("sdk_migration_done", false)) {
        lifecycleScope.launch {
            DataMigration.transferToSdk(roomDb, sdkStorage)
            prefs.edit { putBoolean("sdk_migration_done", true) }
        }
    }
    ```
    > **Why not WorkManager?** WorkManager is for deferrable background work and has retry semantics that can cause duplicate inserts without idempotency guards. This migration must complete before the app is usable and must be guarded by an explicit done-flag.

2.  **Logic:** Use different database file paths — Room keeps `meshtastic.db`, SQLDelight uses `sdk_meshtastic.db` — to avoid SQLite locking conflicts during the transition window.
3.  **App Metadata Split:** In the same `Application.onCreate` coroutine, copy `isFavorite`, `notes`, and `manuallyVerified` from `NodeEntity` into the new `NodeMetadata` Room table (created in step A).

---

## Phase 3: The Great Deletion
**Goal:** Remove legacy architectures that double-buffer state or require complex synchronization.

1.  **Delete Protocol Databases:** Delete `NodeEntity` (`core/database/.../entity/NodeEntity.kt`) and `PacketEntity` (`core/database/.../entity/Packet.kt`). Note: `ChannelEntity` does not exist in the codebase — skip it. The app no longer owns this schema.
2.  **Delete AIDL:** Delete `IMeshService.aidl` (`core/api/src/main/aidl/org/meshtastic/core/service/IMeshService.aidl`) and all binding logic in `MeshService.kt`. 3rd-party apps (like ATAK) will rely exclusively on the built-in TCP TAK Server.
3.  **Delete Orchestrators:** Delete `MeshServiceOrchestrator.kt` (`core/service/src/commonMain/`), `MeshConnectionManagerImpl.kt` (`core/data/src/commonMain/`), `SharedRadioInterfaceService.kt` (`core/service/src/commonMain/`), and `MeshMessageProcessorImpl.kt` (`core/data/src/commonMain/`). The SDK's `MeshEngine` replaces all of them. **Warning:** These files are in `commonMain` — before deleting, grep for all imports across `:desktop` and `:iosApp` to avoid breaking other KMP targets.
4.  **Delete Intent Broadcasts:** Remove `ServiceBroadcasts.kt` — it exists in two locations: `core/service/src/androidMain/` and `core/repository/src/commonMain/`. Both must be deleted. The app will use Kotlin `Flow`s natively.

---

## Phase 4: RadioClient as the Core Dependency
**Goal:** Inject the `RadioClient` directly into the DI graph.

1.  **Koin Setup:** Create a `RadioClientManager` or a dynamic Koin provider. (Koin 4.2.1 is already in the project — no version bump needed.) Because `RadioClient` is 1:1 with a specific `TransportSpec`, switching radios means rebuilding the client.
    ```kotlin
    // In Application.onCreate():
    AndroidContextHolder.context = applicationContext

    // RadioClientProvider.kt — keep in androidMain (holds Context; not KMP-portable as-is).
    // For `:desktop` support, extract an expect/actual interface in commonMain.
    // RadioPrefs wraps the existing core:prefs DataStore — read the saved transport type
    // (BLE address, TCP host, or serial port) to reconstruct the correct TransportSpec.
    class RadioClientProvider(private val context: Context, private val prefs: RadioPrefs) {
        private var _client = MutableStateFlow<RadioClient?>(null)
        val client: StateFlow<RadioClient?> = _client.asStateFlow()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // suspend fun — disconnect() is a suspending SDK operation; never call it synchronously.
        suspend fun rebuildAndConnect() {
            _client.value?.disconnect()  // awaited correctly
            val newClient = RadioClient.Builder()
                .transport(getTransportSpec(prefs))  // BleTransportSpec / TcpTransportSpec / SerialTransportSpec
                .storage(SqlDelightStorageProvider(baseDir = context.filesDir.absolutePath))
                .build()
            _client.value = newClient
        }

        // Call from a CoroutineScope that outlives the ViewModel (e.g., service scope or app scope).
        fun rebuildAndConnectAsync() = scope.launch { rebuildAndConnect() }
    }
    ```

---

## Phase 5: The "Thin" Foreground Service
**Goal:** Reduce `MeshService.kt` to an Android-specific lifecycle holder.

1.  **Foreground Anchor:** `MeshService` (currently 405 lines, 13 injected dependencies) no longer processes bytes or orchestrates handshakes. Its only job is to call `startForeground()` to keep the app alive and hold Android 14+ FGS permissions. Verify `AndroidManifest.xml` declares the correct type: `<service android:foregroundServiceType="connectedDevice|location" />` — missing this crashes on Android 14+. The existing `startForegroundSafely()` code already handles the API call correctly.
2.  **Notification Binding:** `MeshService` collects `client.connection` and `client.ownNode` using `SharingStarted.Eagerly` (not `WhileSubscribed`) so the subscription persists while the service is alive — this ensures background messages are not dropped. Throttle notification updates to at most once per second to avoid battery drain.
3.  **Lifecycle:** When `MeshService` is destroyed, it launches `client.disconnect()` in a `lifecycleScope` — `disconnect()` is suspending and must not be called synchronously from `onDestroy()`.

---

## Phase 6: Downstream Architectural Impacts (UI & Domain)
**Goal:** Radically flatten the app's Information Architecture (IA) by eliminating the fragmented repository and UseCase layers.

### 6.1 ViewModel Simplification (Direct Injection)
*   **Legacy:** `NodeListViewModel` injects 9 dependencies: `SavedStateHandle`, `NodeRepository`, `RadioConfigRepository`, `ServiceRepository`, `RadioController`, `RadioInterfaceService`, `NodeManagementActions`, `GetFilteredNodesUseCase`, `NodeFilterPreferences`. Six of these become redundant with `RadioClient`.
*   **Clean Break:** ViewModels inject **only** the `RadioClientProvider` (and a lightweight app metadata store). ViewModels transition from "State Reconcilers" to pure "State Observers".

```kotlin
// Before: NodeListViewModel.kt (9 constructor params)
class NodeListViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val nodeRepository: NodeRepository,
    private val radioConfigRepository: RadioConfigRepository,
    private val serviceRepository: ServiceRepository,
    private val radioController: RadioController,
    private val radioInterfaceService: RadioInterfaceService,
    private val nodeManagementActions: NodeManagementActions,
    private val getFilteredNodesUseCase: GetFilteredNodesUseCase,
    private val nodeFilterPreferences: NodeFilterPreferences,
) : ViewModel()

// After: NodeListViewModel.kt (3 constructor params)
// Note: inject AppMetadataRepository (thin wrapper over NodeMetadataDao), not the DAO directly.
// This preserves the data layer contract and allows swapping Room → DataStore later without ViewModel churn.
class NodeListViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val radioClientProvider: RadioClientProvider,
    private val appMetadataRepository: AppMetadataRepository,
) : ViewModel()
```

### 6.2 UseCase Decimation
*   **Legacy:** The `core:domain` layer is filled with UseCases (e.g., `AdminActionsUseCase`, `EnsureRemoteAdminSessionUseCase`) that manually coordinate state updates and track packet IDs.
*   **Clean Break:** These UseCases become entirely redundant.
    *   Instead of calling `AdminActionsUseCase.reboot()`, the ViewModel directly calls the atomic, suspending SDK method: `client.admin.reboot()`.
    *   Session and state coordination are handled internally by the SDK's `MeshEngine` actor.
    *   **Action:** Delete approximately 60-70% of the 23 `core:domain` UseCases. Clear candidates: `SetContrastLevelUseCase`, `SetLocaleUseCase`, `SetThemeUseCase`, `SetNotificationSettingsUseCase`, `ToggleAnalyticsUseCase`, `SetAppIntroCompletedUseCase`, `SetDatabaseCacheLimitUseCase`, `SetMeshLogSettingsUseCase`, `SetProvideLocationUseCase`, `ToggleHomoglyphEncodingUseCase`, `ProcessRadioResponseUseCase` (11 UCs). Delete in Phase 3; ViewModels call `client.admin.*` directly instead. Keep temporarily until SDK absorbs them: `RadioConfigUseCase`, `MeshLocationUseCase`, `IsOtaCapableUseCase`, `CleanNodeDatabaseUseCase`, and the export/import UCs.

---

## Phase 7: UI / ViewModel Direct Binding Examples & Error Handling
**Goal:** Concrete examples of ViewModels consuming the SDK directly.

### 7.1 Node List & App Metadata Join
ViewModels combine the SDK's `client.nodes` flow with a lightweight App Metadata store to handle UI-only features (like "Favorites"). **Note:** `NodeChange` is a new sealed class introduced by the SDK — the codebase currently uses `NodeRepository` (Room DAO + `StateFlow`) with no existing `NodeChange` hierarchy. No migration of the pattern is needed; it simply replaces the Room queries.

```kotlin
// UiNode — never expose Wire-generated NodeInfo directly to Compose.
// Wire classes are not @Stable; wrapping them causes full recomposition on every change.
// Unwrap to primitives so Compose can diff efficiently.
@Immutable
data class UiNode(
    val num: Int,
    val longName: String,
    val shortName: String,
    val lastHeard: Int,
    val snr: Float,
    val hopsAway: Int,
    val isFavorite: Boolean,
    val notes: String?,
)

// NodeViewModel.kt
// flowOn(Dispatchers.Default) is required: SDK emits on a background dispatcher.
// stateIn transitions to viewModelScope (Main.immediate on Android).
val nodes: StateFlow<List<UiNode>> = combine(
    client.nodes
        .scan(emptyMap<NodeId, NodeInfo>()) { acc, change ->
            when (change) {
                // Snapshot = full replacement (emitted on first connection and after reconnect)
                is NodeChange.Snapshot -> change.nodes
                is NodeChange.Added   -> acc + (change.node.num to change.node)
                is NodeChange.Updated -> acc + (change.node.num to change.node)
                is NodeChange.Removed -> acc - change.nodeId
            }
        }
        .flowOn(Dispatchers.Default), // ← required; SDK emits off-main
    appMetadataRepository.getAllFlow()
) { sdkNodes, appMetadata ->
    sdkNodes.values.map { sdkNode ->
        val meta = appMetadata.find { it.num == sdkNode.num }
        UiNode(
            num       = sdkNode.num,
            longName  = sdkNode.user?.long_name ?: "",
            shortName = sdkNode.user?.short_name ?: "",
            lastHeard = sdkNode.last_heard,
            snr       = sdkNode.snr,
            hopsAway  = sdkNode.hops_away,
            isFavorite = meta?.isFavorite == true,
            notes     = meta?.notes,
        )
    }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

### 7.2 Compose: Handle-Based UI State & Messaging
Instead of global intent broadcasts, the UI tracks individual messages.
```kotlin
// Sending a message
val handle = client.sendText(text, channel)

// In a Compose component \u2014 use collectAsStateWithLifecycle() to prevent background leaks
val sendState by handle.state.collectAsStateWithLifecycle()
when (sendState) {
    is SendState.Queued -> Text("Queued...")
    is SendState.Sent -> Text("Sent to radio")
    is SendState.Acked, is SendState.Delivered -> Text("Delivered \u2713\u2713")
    is SendState.Failed -> Text("Failed: ${(sendState as SendState.Failed).reason}")
}
```

### 7.3 Admin, Config & Error Handling
The SDK provides strongly-typed outcomes. ViewModels must handle them idiomatically.
```kotlin
// AdminResult must be a sealed class in commonMain for exhaustive when to compile without else.
// If it is an interface or open class, add an else branch.
viewModelScope.launch {
    when (val result = client.admin.reboot()) {
        is AdminResult.Success     -> uiState.value = "Rebooting..."
        is AdminResult.Timeout     -> alertManager.show("Radio didn't respond in time")
        is AdminResult.Unauthorized -> alertManager.show("Invalid admin channel")
        // No catch block needed; routine errors are returned as sealed subtypes, not thrown.
    }
}
```

### 7.4 Flow Error Boundaries
Add `.catch {}` before `scan()` to intercept SDK exceptions without crashing. The snippet below shows placement within the full chain from 7.1:
```kotlin
// .catch{} must come before .scan() so it can re-emit a safe NodeChange into the accumulator.
val nodes: StateFlow<List<UiNode>> = combine(
    client.nodes
        .catch { e ->
            Timber.e(e, "SDK nodes flow error")
            emit(NodeChange.Snapshot(emptyMap())) // resets accumulator to empty; UI shows empty list
        }
        .scan(emptyMap<NodeId, NodeInfo>()) { acc, change -> /* ... see 7.1 ... */ }
        .flowOn(Dispatchers.Default),
    appMetadataRepository.getAllFlow()
) { sdkNodes, appMetadata -> /* ... */ }
.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

---

## Phase 8: Feature Integrations (Locations & TAK)
**Goal:** Re-wire background features directly to the SDK.

1.  **Device Location:** A dedicated `LocationWorker` or coroutine collects Android's `LocationManager` and calls `client.admin.editSettings { setPosition(lat, lon) }` or `client.send(PositionPacket)`. No `LocationWorker` currently exists — it must be created; the current abstraction is `LocationService` (`core/repository/src/commonMain/`) returning a one-shot `getCurrentLocation()`.
2.  **TAK Server:** A substantial `core:takserver` module already exists (`TAKServer.kt`, `TAKServerManager.kt`, `TAKMeshIntegration.kt`, `CoTConversion.kt`, `TAKClientConnection.kt`). **Do not delete this module.** Refactor it to consume packets from `client.packets` rather than the legacy radio interface. The CoT models and XML logic are reusable.
3.  **Telemetry:** `client.telemetry.observe(nodeId)` replaces legacy polling logic.

---

## Phase 9: Testing Strategy
**Goal:** Leverage the SDK's `:testing` module for robust UI testing.

1.  **Fakes:** Replace Koin `RadioClientProvider` with the SDK's `FakeRadioClient` in all ViewModel and Compose UI tests.
2.  **Predictability:** The `FakeRadioClient` allows you to instantly simulate NAKs, Timeouts, and incoming `NodeChange` events without complex Mockito mocking.
3.  **Scope:** You no longer need to test handshake logic, protocol framing, or database synchronization. Your tests only verify that the UI reacts correctly to SDK states.

---

## Phase 10: Pull Request Execution Sequence
A "Clean Break" touches 50,000+ lines of code. It **cannot** be merged as a single PR. Follow this sequence:

*   **PR 1: SDK Integration & Model Swap.** Add the SDK dependencies. Change all imports from `core:model` to `org.meshtastic.proto`. Fix compile errors. (Massive, but mechanically simple).
*   **PR 2: One-Time Data Migration & App Metadata DB.** Add `MIGRATION_36_37` (schema only), the `NodeMetadata` entity, and the `Application.onCreate` coroutine that transfers legacy Room data to SQLDelight.
*   **PR 3: Domain Decimation.** Delete redundant UseCases and refactor ViewModels to consume `RadioClient` and handle errors.
*   **PR 4: The Great Deletion.** Delete `MeshServiceOrchestrator`, `SharedRadioInterfaceService`, `MeshConnectionManagerImpl`, `MeshMessageProcessorImpl`, `ServiceBroadcasts`, `NodeEntity`, `PacketEntity`, and `IMeshService.aidl`. Run `MIGRATION_37_38` to drop legacy Room tables. Strip `MeshService.kt` to its bare bones.
*   **PR 5: Feature Re-Wiring.** Attach TAK, Location, and Notification features to the SDK flows.

---

## Benefits of the Clean Break

1.  **Massive Code Reduction:** Thousands of lines of boilerplate, state synchronization, AIDL marshalling, and database migrations are deleted.
2.  **Elimination of TOCTOU Bugs:** By removing `MeshConnectionManager` and `ServiceRepository`, we eliminate Time-Of-Check-To-Time-Of-Use bugs where the UI thinks the radio is connected but the background transport dropped.
3.  **True KMP Alignment:** The Android UI architecture becomes identical to the future iOS Compose Multiplatform architecture. Both platforms simply observe the SDK.
4.  **Performance:** No more double-deserialization of Protobufs or copying data between the networking layer and the Room database before it reaches the UI.

---

## Agent Guidance & Hooks

If an autonomous agent is executing this migration plan, adhere to the following guidelines:

### System Hooks
- `<activated_skill>` `kmp-architecture`: Remember that `commonMain` code must never reference Android-specific `java.*` or `android.*` APIs.
- `<activated_skill>` `compose-ui`: When building the new handle-based UI (Phase 7.2), ensure you use `collectAsStateWithLifecycle()` in Compose functions instead of plain `collectAsState()` to prevent background flow collection leaks.

### Execution Prompt Templates

When executing Phase 3 (The Great Deletion), utilize this mental model:
```markdown
> "You are executing Phase 3 of the Clean Break migration. Your objective is deletion, not refactoring. If you encounter a compilation error because a ViewModel references `MeshConnectionManager`, DO NOT try to fix the manager. Instead, remove the reference from the ViewModel and prepare it for `RadioClient` injection (Phase 6)."
```

When executing Phase 7 (UI / ViewModel Direct Binding):
```markdown
> "You are binding the UI directly to the SDK. You MUST NOT introduce intermediate `StateFlow` wrappers unless necessary for filtering or sorting. Expose the SDK's `client.nodes` directly. For error handling, use the exhaustive `when(result)` pattern on SDK sealed classes (like `AdminResult`). NEVER use `try/catch` for routine expected errors like NAKs or timeouts; the SDK does not throw exceptions for these."
```

### Critical Context Markers
- The SDK uses Wire Protobufs. When converting UI files in Phase 1, remember the mapping: `com.geeksville.mesh.NodeInfo` -> `org.meshtastic.proto.NodeInfo`.
- **Snake Case:** All Wire-generated fields are `snake_case`. `node.longName` is now `node.long_name`.
- **Concurrency:** The SDK's `MeshEngine` uses an Actor model. Do NOT introduce `Mutex` or `synchronized` blocks when observing the SDK.
- **ChannelEntity does not exist** in `core/database` — do not attempt to delete it.
- **ServiceBroadcasts.kt** is in two locations (`core/service/src/androidMain/` and `core/repository/src/commonMain/`) — both must be removed.
- **`:desktop` module** is included in `settings.gradle.kts` as a KMP target. Assess impact separately; this plan covers Android and the shared `commonMain` only.
- **`NodeChange` sealed class** is introduced by the SDK — it does not exist in the current codebase. This is additive, not a rename of an existing pattern.
- **`AdminResult` must be `sealed`** in `commonMain` for `when` to be exhaustive without an `else` branch. Verify this before relying on compiler exhaustiveness checking.
- **Wire types are not `@Stable`** — never pass `NodeInfo` or other Wire-generated objects directly to Compose. Always unwrap to an `@Immutable data class` with primitive fields (see Phase 7.1 `UiNode`).
- **`disconnect()` is suspending** — never call `client?.disconnect()` from a non-suspend context. Always `await` it in a coroutine scope (see Phase 4 `rebuildAndConnect()`).
- **`commonMain` deletions affect all KMP targets** — before deleting any file in `commonMain`, check imports in `:desktop` and `:iosApp`.
