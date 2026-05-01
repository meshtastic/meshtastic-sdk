# Migration Guide: Meshtastic-Apple to meshtastic-sdk (Clean Break Strategy)

This document outlines a **Clean Break** migration path for Meshtastic-Apple. Rather than building shims to preserve legacy Core Data models, custom BLE wrappers, or hybrid persistence, this strategy radically simplifies the app by treating the `meshtastic-sdk` as the single source of truth.

The goal is massive code deletion and direct SwiftUI-to-SDK binding.

---

## 0. Architectural Vision: Direct Binding

We are discarding the legacy repository/service architecture in favor of **Direct SDK Binding**. The app becomes a thin SwiftUI shell over the SDK's `RadioClient`.

### Before vs. After (Clean Break)
```mermaid
graph TD
    subgraph "Legacy Meshtastic-Apple"
        A[SwiftUI Views] --> B[ViewModel]
        B --> C[PeripheralModel / CoreData]
        C --> D[BLE/Serial/TCP Wrappers]
        D --> E[Custom Protocol Logic]
    end

    subgraph "Clean Break Architecture"
        F[SwiftUI Views] --> G[ViewModel]
        G --> H[RadioClient (SDK)]
        H --> I[sdk-storage-sqldelight]
        G --> J[Core Data (App Metadata: Favorites/Notes)]
    end
```

---

## Phase 1: Environment & Dependency Alignment
**Goal:** Prepare the Xcode project to import the SDK and resolve model clashes.

1. **Swift Package/Framework Alignment:**
    - Integrate the KMP SDK as an XCFramework or Swift Package.
    - Align dependency versions (Wire, Coroutines, etc.) with the SDK.
2. **Model Swap:**
    - Replace custom Swift models with SDK Wire types (`org.meshtastic.proto.NodeInfo`). Note: `Peripheral` (`Meshtastic/Model/PeripheralModel.swift`) is a 24-line struct wrapping `CBPeripheral` — trivial to delete. The real migration target is `AccessoryManager` (the central BLE/protocol class, 879+ lines). Custom protobuf definitions live in the `MeshtasticProtobufs` submodule (`MeshtasticProtobufs/Sources/meshtastic/*.pb.swift`).
    - Delete custom protobuf mappings. Update all usages to reference SDK types (note: Wire uses `snake_case`).

---

## Phase 2: One-Time Data Migration (Critical Step)
**Goal:** Prevent data loss for existing users when swapping persistence layers.

1. **Migration Routine:** On first launch with the new version, read legacy Core Data entities (e.g., `NodeInfoEntity`, `TelemetryEntity`), map to SDK types, and call `DeviceStorage.saveNode()` or equivalent.

Run migration on a **background context** — never block the main thread on launch. Show a splash/progress screen until migration completes.

**Example:**
```swift
// async throws — migration is I/O bound; blocking the main thread risks watchdog kills on large datasets.
func migrateLegacyNodes(context: NSManagedObjectContext, sdkStorage: DeviceStorage) async throws {
    // context.perform(_:) (iOS 15+) runs the closure on the context's private queue.
    try await context.perform {
        let fetchRequest = NSFetchRequest<NodeInfoEntity>(entityName: "NodeInfoEntity")
        let legacyNodes = try context.fetch(fetchRequest)
        for legacy in legacyNodes {
            // NodeInfoEntity.num (Int64) maps to Wire NodeInfo.num (Int32)
            // longName/shortName live on UserEntity (relationship), not directly on NodeInfoEntity
            let sdkNode = NodeInfo(
                num: Int32(legacy.num),
                user: legacy.user.map { u in
                    User(
                        id: u.userId ?? "",
                        long_name: u.longName ?? "",
                        short_name: u.shortName ?? ""
                    )
                },
                last_heard: Int32(legacy.lastHeard?.timeIntervalSince1970 ?? 0),
                snr: Float(legacy.snr),
                hops_away: Int32(legacy.hopsAway)
                // ... map remaining protocol fields ...
            )
            try sdkStorage.saveNode(sdkNode)
        }
    }
}

// Migrate UI-only metadata separately — do NOT pass favorite/ignored to the SDK
func migrateNodeMetadata(context: NSManagedObjectContext, metadataStore: AppMetadataStore) async throws {
    try await context.perform {
        let fetchRequest = NSFetchRequest<NodeInfoEntity>(entityName: "NodeInfoEntity")
        for legacy in try context.fetch(fetchRequest) {
            metadataStore.upsert(NodeMetadata(
                num: legacy.num,
                isFavorite: legacy.favorite,   // NodeInfoEntity.favorite (Bool)
                isIgnored: legacy.ignored      // NodeInfoEntity.ignored (Bool)
                // notes: nil — field does not exist in legacy data; add as net-new
            ))
        }
    }
}
```
2. **App Metadata Split:** Extract `NodeInfoEntity.favorite` (Bool) and `NodeInfoEntity.ignored` (Bool) — the only confirmed UI-only fields — into the new metadata store. For iOS 17+ targets, **SwiftData** is a better fit than a new Core Data store for simple metadata; `@AppStorage` is suitable for standalone booleans. Core Data is acceptable for iOS 16 support. Note: a `notes` field for nodes does not exist in the current codebase; add it to the new metadata store as a net-new feature if desired. Also migrate `RouteEntity.notes` (user-created routes) as-is.
3. **iCloud Backup:** Mark the new SDK SQLDelight database file as excluded from iCloud backup immediately after creation — protocol state should not sync across devices:
    ```swift
    var url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("sdk_meshtastic.db")
    var resourceValues = URLResourceValues()
    resourceValues.isExcludedFromBackup = true
    try url.setResourceValues(resourceValues)
    ```

---

## Phase 3: The Great Deletion
**Goal:** Remove legacy architectures that double-buffer state or require complex synchronization.

1. **Delete Protocol Entities:** The Core Data model (`Meshtastic.xcdatamodeld`) has 42 entities. Delete the ~26 protocol-state entities including all config variants (`AmbientLightingConfigEntity`, `BluetoothConfigEntity`, `CannedMessageConfigEntity`, `DeviceConfigEntity`, `DisplayConfigEntity`, `ExternalNotificationConfigEntity`, `LoRaConfigEntity`, `MQTTConfigEntity`, `MyInfoEntity`, `NetworkConfigEntity`, `PaxCounterConfigEntity`, `PositionConfigEntity`, `PowerConfigEntity`, `RangeTestConfigEntity`, `SecurityConfigEntity`, `SerialConfigEntity`, `StoreForwardConfigEntity`, `TAKConfigEntity`, `TelemetryConfigEntity`, `TelemetryEntity`, `TraceRouteEntity`, `TraceRouteHopEntity`, etc.). Keep: `NodeInfoEntity` (reduced to metadata fields only), `UserEntity`, `MessageEntity`, `RouteEntity`, `WaypointEntity`, `LocationEntity`, and the firmware/hardware entities.
2. **Delete Custom BLE/Serial Wrappers:** Remove `BLETransport.swift`, `BLEConnection.swift`, `SerialTransport.swift`, `SerialConnection.swift`, `TCPTransport.swift`, `TCPConnection.swift` (all in `Meshtastic/Accessory/Transports/`). The SDK provides equivalent transports.
3. **Delete Protocol Logic:** Primary targets are `MeshPackets.swift` (~2000 lines, actor-based singleton in `Meshtastic/Helpers/`), `AccessoryManager+FromRadio.swift`, `AccessoryManager+ToRadio.swift`, `AccessoryManager+Connect.swift`, `AccessoryManager+Discovery.swift`, and `AccessoryManager+Position.swift` (location-to-radio forwarding, replaced by Phase 8 `LocationsHandler` binding). The SDK's `MeshEngine` replaces all of this. Also delete `UpdateCoreData.swift` (`Meshtastic/Persistence/`) once Core Data protocol entities are gone. **Preserve:** `AccessoryManager+TAK.swift`, `AccessoryManager+MQTT.swift` — these are refactored in Phase 5, not deleted.

---

## Phase 4: RadioClient as the Core Dependency
**Goal:** Inject the `RadioClient` directly into the SwiftUI environment or dependency graph.

- Create a `RadioClientProvider` (ObservableObject or EnvironmentObject) to replace `AccessoryManager` as the central connection object. `MeshtasticAppleApp` currently injects `AccessoryManager` as `@EnvironmentObject`; replace that injection with `RadioClientProvider`.
- Rebuild the client when switching radios (BLE/TCP/Serial).

**Example:**
```swift
import SwiftUI

// ObservableObject pattern shown here for iOS 16 compatibility.
// For iOS 17+ targets, prefer @Observable (no @Published needed; property setters trigger updates automatically).
class RadioClientProvider: ObservableObject {
    @Published var client: RadioClient? = nil

    // async — disconnect() is a suspending SDK operation; calling it synchronously loses the coroutine.
    func connect(transport: TransportSpec) async {
        await client?.disconnect()
        let baseDir = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)[0].path
        client = RadioClient.Builder()
            .transport(transport)
            .storage(SqlDelightStorageProvider(baseDir: baseDir))
            .build()
    }
}

// Call site — always dispatch to a Task since connect() is async:
// Button("Connect") { Task { await radioProvider.connect(transport: spec) } }

// Before: MeshtasticAppleApp injected AccessoryManager as the central connection object.
// After: replace AccessoryManager with RadioClientProvider; keep remaining objects unchanged.
@main
struct MeshtasticAppleApp: App {
    // Replaces: @StateObject var accessoryManager = AccessoryManager()
    @StateObject var radioProvider = RadioClientProvider()
    @StateObject var appState = AppState()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(radioProvider)   // was: .environmentObject(accessoryManager)
                .environmentObject(appState)
                .environmentObject(appState.router)
                .environmentObject(MeshtasticAPI.shared)
                // managedObjectContext only needed until metadata Core Data migration is complete
        }
    }
}
```
---

## Phase 5: Platform Service Simplification
**Goal:** Reduce background services to minimal lifecycle holders.

- Background tasks should hold SDK client references only. Specific services to preserve/migrate:
  - **`LocationsHandler`** (`Meshtastic/Helpers/LocationsHandler.swift`) — background Core Location; bind its updates to SDK position calls.
  - **`MqttClientProxyManager`** (`Meshtastic/Helpers/Mqtt/`) — MQTT proxy bridge used by `AccessoryManager`; wire to SDK packet flow, do not delete.
  - **TAK bridge** (`AccessoryManager+TAK.swift`, `Meshtastic/Helpers/TAK/`) — bidirectional CoT bridge consuming `FromRadio` packets; refactor to consume `client.packets`, do not delete.
  - **Heartbeat/keepalive** logic in `AccessoryManager` — confirm whether the SDK handles connection liveness internally before removing.
  - **Datadog** (crash reporting, RUM, Session Replay, Tracing) in `MeshtasticApp.swift` — preserve unchanged across migration.

---

## Phase 6: Downstream Architectural Impacts (UI & Domain)
**Goal:** Flatten the app's architecture by eliminating fragmented repositories and use cases.

- **ObservableObject vs @Observable:** The examples in this guide use `ObservableObject` for iOS 16 compatibility. For any new code targeting iOS 17+, prefer `@Observable` (no `@Published` boilerplate; injected via `.environment(radioProvider)` / `@Environment(RadioClientProvider.self)` rather than `.environmentObject`). The two patterns can coexist in the same app — they cannot be used interchangeably for the same object (an `@Observable` type cannot be passed via `.environmentObject`). Introduce `@Observable` on new types only; do not refactor existing `ObservableObject` types unless doing a full view-tree pass.
- **ObservableObject Model Simplification:** Most state is managed by ObservableObject models, @StateObject, or @EnvironmentObject, not classic ViewModels. Only use ViewModels where the Apple app already does (e.g., firmware/OTA flows). For most features, bind SDK publishers directly to ObservableObjects or SwiftUI views.
- **UseCase Decimation:** Delete redundant use cases; call SDK methods directly from ObservableObjects or views.

---

## Phase 7: SwiftUI/ObservableObject Direct Binding Examples & Error Handling
**Goal:** Show concrete examples of direct SDK integration using ObservableObject and SwiftUI idioms.

### 7.1 Node List & App Metadata Join
Combine the SDK's `client.nodesPublisher` with Core Data for UI-only features (like Favorites) in an ObservableObject, not a ViewModel.

```swift
// UiNode — unwrap SDK types to value semantics so SwiftUI can diff efficiently.
// Do not store the raw NodeInfo object; it may not be Equatable/Hashable in the KMP bridge.
struct UiNode: Identifiable, Equatable {
    let id: Int64           // == NodeInfoEntity.num
    let longName: String
    let shortName: String
    let lastHeard: Date?
    let snr: Float
    let hopsAway: Int32
    let isFavorite: Bool    // from AppMetadataStore
    let isIgnored: Bool     // from AppMetadataStore
}
```

**Example:**
```swift
class NodeListModel: ObservableObject {
    @Published var nodes: [UiNode] = []
    private var cancellables = Set<AnyCancellable>()

    init(radioClient: RadioClient, metadataStore: AppMetadataStore) {
        Publishers.CombineLatest(
            radioClient.nodesPublisher,       // Combine publisher from SDK
            metadataStore.metadataPublisher   // emits [NodeMetadata] with favorite/ignored fields
        )
        .map { sdkNodes, metadata in
            sdkNodes.map { node in
                let meta = metadata.first { $0.num == node.num }
                return UiNode(
                    node: node,
                    isFavorite: meta?.isFavorite ?? false,  // NodeInfoEntity.favorite
                    isIgnored: meta?.isIgnored ?? false     // NodeInfoEntity.ignored
                )
            }
        }
        .receive(on: DispatchQueue.main)
        .assign(to: &$nodes)
    }
}
```

### 7.2 SwiftUI: Handle-Based UI State & Messaging
Track individual message handles and states using SDK publishers, binding them directly to views or ObservableObjects.

**Example:**
```swift
// MessageHandle must conform to ObservableObject for @ObservedObject to work.
// If it is a KMP class that does not, wrap it: class MessageHandleWrapper: ObservableObject { ... }
struct MessageSendView: View {
    @ObservedObject var handle: MessageHandle // Provided by SDK
    var body: some View {
        switch handle.state {
        case .queued: Text("Queued...")
        case .sent: Text("Sent to radio")
        case .acked, .delivered: Text("Delivered ✓✓")
        case .failed(let reason): Text("Failed: \(reason)")
        }
    }
}
```

### 7.3 Admin, Config & Error Handling
Use exhaustive `switch` on SDK sealed outcomes (e.g., `AdminResult`). Do not use `try/catch` for routine errors.

**Example:**
```swift
// Call from .task { } (cancels on view disappear) or Button { Task { ... } } (fire-and-forget).
// Never call async functions directly from a synchronous Button action.
func rebootRadio(client: RadioClient) async {
    let result = await client.admin.reboot()
    switch result {
    case .success:      print("Rebooting...")
    case .timeout:      print("Radio didn't respond in time")
    case .unauthorized: print("Invalid admin channel")
    }
}

// In a SwiftUI view — guard against nil client; do not force-unwrap:
Button("Reboot") {
    guard let client = radioProvider.client else { return }
    Task { await rebootRadio(client: client) }
}
// Or via the view lifecycle (automatically cancelled on view disappear):
.task {
    guard let client = radioProvider.client else { return }
    await rebootRadio(client: client)
}
```
---

## Phase 8: Feature Integrations (Locations, Siri, CarPlay)
**Goal:** Re-wire background features directly to the SDK.

- **Device Location:** `LocationsHandler` (`Meshtastic/Helpers/LocationsHandler.swift`) already provides background `CLLocationManager` updates; bind its output to SDK position calls.
- **CarPlay** (`Meshtastic/CarPlay/CarPlaySceneDelegate.swift`) — currently queries `NodeInfoEntity` directly via `NSPredicate`; replace with SDK node publisher. All `CPListTemplate` mutations must run on the main thread — ensure the SDK publisher chain includes `.receive(on: DispatchQueue.main)` before updating templates. `CarPlayIntentDonation.swift` also needs updating.
- **Siri / App Intents** (`Meshtastic/AppIntents/`, `Meshtastic/Intents/`) — bind to SDK actions and flows.
- **Watch App** (`Meshtastic Watch App/`) — uses `WatchConnectivity` only, with no direct Core Data or BLE access. Low coupling; update `WatchSessionManager.sendNodesToWatch()` to serialize from the SDK node publisher instead of a Core Data fetch. Use `transferUserInfo()` (background, queued, survives app termination) for node list syncs; reserve `sendMessage()` for real-time foreground updates only. Treat as a separate, low-risk PR.
- **Widgets** (`Widgets/`) — Live Activity support via `ActivityKit`; verify `MeshActivityAttributes` fields are still valid after model swap.
- **Nymea Provisioning** (`Meshtastic/Provisioning/NymeaProvisioningManager.swift`) — not covered by this plan; assess separately whether it requires SDK integration or can remain independent.
- **Telemetry:** Use `client.telemetry.observe(nodeId)` for live updates.

---

## Phase 9: Testing Strategy
**Goal:** Leverage the SDK's testing module for robust UI testing.

- Replace `RadioClientProvider` with SDK fakes in tests. The existing 50 test files mostly cover utilities, enums, and extensions — **no BLE/AccessoryManager integration tests exist**. This is an opportunity: once Phase 4 is complete, add meaningful integration tests via `FakeRadioClient` that didn't exist before.
- Use **Swift Testing** (`@Suite`, `@Test`) for new async/concurrent tests targeting iOS 17+; retain XCTest for UI/integration tests (XCUITest). Do not mix frameworks in the same test target.
- Focus tests on UI reactions to SDK state, not protocol logic.

---

## Phase 10: Pull Request Execution Sequence
A "Clean Break" is a large change. Use multiple PRs:

- **PR 1:** SDK Integration & Model Swap (XCFramework/SPM integration; protobuf submodule removal; Wire type adoption)
- **PR 2:** One-Time Data Migration & App Metadata DB (Core Data → SQLDelight transfer; `favorite`/`ignored` metadata store; iCloud backup exclusion)
- **PR 3:** Domain Decimation (delete redundant ObservableObjects and use cases; bind SDK publishers directly)
- **PR 4:** The Great Deletion (delete 26 protocol Core Data entities; delete transport wrappers; delete `MeshPackets.swift`, `AccessoryManager+FromRadio/ToRadio/Connect/Discovery.swift`; strip `AccessoryManager` to shell)
- **PR 5:** Feature Re-Wiring (TAK bridge, MQTT proxy, LocationsHandler, CarPlay, Siri/App Intents, Watch App serialization, Widgets field verification)

---

## Benefits of the Clean Break

1. **Massive Code Reduction:** Removes boilerplate, custom protocol logic, and double-buffering.
2. **Elimination of State Bugs:** No more UI/protocol desyncs.
3. **True KMP Alignment:** Apple and Android clients share architecture and state model.
4. **Performance:** No more double-deserialization or redundant Core Data writes.

---

## Agent Guidance & Hooks

- Never reference platform-specific APIs in `commonMain`.
- Use SDK publishers directly in SwiftUI; avoid unnecessary wrappers.
- Use exhaustive `switch` on SDK sealed classes for error handling.
- All Wire-generated fields are `snake_case`.
- The SDK's `MeshEngine` uses an Actor model; do not introduce locks or mutexes. The existing codebase already uses Swift actors (`BLETransport`, `MeshPackets`) — this architectural alignment reduces concurrency surprises during migration.
- The central BLE/protocol class is `AccessoryManager`, not `BLEManager` or `PeripheralModel`. `Peripheral` is a 24-line struct; `AccessoryManager` (879+ lines across its main file and 7 extension files) is the real migration target.
- `NodeInfoEntity.favorite` and `NodeInfoEntity.ignored` are the only confirmed UI-only node fields. A `notes` field on nodes does not exist today.
- The `MeshtasticAppleApp` `@main` struct (not `MeshtasticApp`) injects 5 environment objects: `AppState`, `AccessoryManager`, `Router`, `MeshtasticAPI`, and `managedObjectContext`.
- **`connect()` is async** — `client?.disconnect()` must be awaited. Never call it synchronously.
- **Isolation policy:** All UI updates must be `@MainActor`. SDK emissions may arrive off-main — ensure every publisher chain ends with `.receive(on: DispatchQueue.main)` before assigning to `@Published` properties.
- **`@Observable` (iOS 17+):** For new ObservableObjects, prefer `@Observable` over `ObservableObject`/`@Published`. Inject via `.environment()` not `.environmentObject()`. Do not introduce `@Observable` types into the existing `ObservableObject` hierarchy without auditing the view tree.
- **iCloud backup:** Exclude all SDK database files from backup immediately after creation (`URLResourceValues.isExcludedFromBackup = true`). Protocol state must not sync across devices.
- **PrivacyInfo.xcprivacy:** After Phase 3 deletes Core Data protocol entities, audit `PrivacyInfo.xcprivacy` for stale `NSPrivacyAccessedAPITypes` entries that referenced the deleted stores.
- **BLE state restoration:** Verify that `RadioClient` handles `CBCentralManagerDelegate` state restoration for persistent BLE connections across app backgrounding. If not, preserve the relevant lifecycle hooks from `AccessoryManager`.
