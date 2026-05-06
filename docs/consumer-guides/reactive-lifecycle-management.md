# Reactive lifecycle management

How to safely collect Kotlin `Flow` and `StateFlow` from the Meshtastic SDK in lifecycle-aware contexts (Android Fragments, Compose, etc.) without memory leaks or missed updates.

## Problem: Why you need this

The Meshtastic SDK exposes state as cold flows:

- `client.nodes` — node changes and snapshot
- `client.packets` — received mesh packets
- `client.events` — connection/protocol events
- `client.nodeDb` — the synced device NodeDB
- `transport.state` — transport-level state changes

**These are cold flows** — each collector gets its own instance from the beginning. If you collect carelessly in Android/Compose lifecycle contexts, you risk:

1. **Memory leaks** — collectors that don't stop when the screen dismisses stay active, leaking the entire `RadioClient`.
2. **Duplicate collection** — collecting from the same flow multiple times causes redundant work.
3. **Missed updates** — collecting too late or too briefly loses important events (e.g., node additions).

## Fragment: repeatOnLifecycle()

The safest pattern in Android Fragments is `repeatOnLifecycle(Lifecycle.State.STARTED)`:

```kotlin
import androidx.lifecycle.repeatOnLifecycle

class MeshNodeListFragment : Fragment() {
    private val viewModel: MeshNodeListViewModel by viewModels()
    private lateinit var adapter: NodeListAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = NodeListAdapter()
        binding.list.adapter = adapter

        // Launch a new coroutine for each lifecycle start → stop cycle
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // This block re-launches when the view enters STARTED state
                // and cancels when it leaves STARTED state (e.g., screen rotates, back pressed).
                viewModel.client.nodes.collect { change ->
                    when (change) {
                        is NodeChange.Snapshot -> adapter.setNodes(change.nodes)
                        is NodeChange.Added -> adapter.addNode(change.node)
                        is NodeChange.Updated -> adapter.updateNode(change.node)
                        is NodeChange.Removed -> adapter.removeNode(change.nodeId)
                        is NodeChange.WentOffline -> adapter.setOffline(change.nodeId)
                        is NodeChange.CameOnline -> adapter.setOnline(change.nodeId)
                    }
                }
            }
        }
    }
}
```

### Why this works

- `repeatOnLifecycle(STARTED)` automatically cancels the collection when the Fragment view stops (e.g., user navigates away or rotates the device).
- When the view returns to the STARTED state (e.g., user comes back), a fresh collection starts.
- No manual unsubscribe needed — the lifecycle handles cancellation.

### State.STARTED vs State.RESUMED

- **`STARTED`** (recommended): Collection runs when the Fragment is visible, even if it's partially obscured by a dialog or another Fragment.
- **`RESUMED`**: Collection only runs when the Fragment has full focus. Use if you need to pause updates while the user is interacting with a modal dialog.

---

## Compose: collectAsStateWithLifecycle()

In Jetpack Compose, use `collectAsStateWithLifecycle()` to tie flow collection to the Compose lifecycle:

```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MeshNodeList(client: RadioClient) {
    val nodes by client.nodes
        .collectAsStateWithLifecycle(initialValue = null)

    when (val snapshot = nodes) {
        null -> CircularProgressIndicator()
        else -> LazyColumn {
            items(snapshot.nodes, key = { it.num }) { node ->
                NodeRow(node)
            }
        }
    }
}
```

### Why this works

- `collectAsStateWithLifecycle()` automatically stops collecting when the Composable leaves the composition, preventing leaks.
- The initial value (`initialValue = null`) is used until the first emission arrives.
- State updates automatically trigger recomposition.

### Comparison with collectAsState()

- **`collectAsState()`** ✗ — naive collection that ignores lifecycle, leaks in background.
- **`collectAsStateWithLifecycle()`** ✓ — respects the Compose lifecycle, safe.

---

## LaunchedEffect: Caveat for one-shot side effects

`LaunchedEffect` is for **one-shot side effects** (like sending a message), not for observing state:

```kotlin
@Composable
fun SendMessageButton(client: RadioClient, messageText: String) {
    LaunchedEffect(messageText) {
        // Run once when messageText changes
        val handle = client.sendText(messageText)
        val outcome = handle.await()
        // … handle outcome
    }

    Button(onClick = { /* … */ }) { Text("Send") }
}
```

❌ **Don't use LaunchedEffect to collect flows directly:**

```kotlin
// ❌ WRONG: This re-collects on every recomposition
LaunchedEffect(Unit) {
    client.nodes.collect { … }  // Leaks if screen rotates
}
```

✓ **Use `collectAsStateWithLifecycle()` instead** for observing flows in Compose.

---

## LiveData: Still supported (legacy)

If your codebase still uses `LiveData`, the old patterns still work:

```kotlin
// Convert Flow → LiveData if needed
val nodesLiveData: LiveData<NodeChange> = client.nodes.asLiveData()

// Observe safely
nodesLiveData.observe(viewLifecycleOwner) { change ->
    // Observer automatically cleaned up when view is destroyed
}
```

But new code should prefer Flows + `repeatOnLifecycle()` or `collectAsStateWithLifecycle()`.

---

## Testing without lifecycle

In unit tests, use `runTest` (instead of `runBlocking`) and collect flows directly:

```kotlin
@Test
fun testNodeUpdates() = runTest {
    val client = RadioClient.Builder()
        .transport(FakeRadioTransport())
        .storage(InMemoryStorageProvider())
        .build()
    client.connect()

    val changes = mutableListOf<NodeChange>()
    launch {
        client.nodes.collect { changes.add(it) }
    }

    // Manually drive the fake transport
    advanceUntilIdle()

    assertTrue(changes.any { it is NodeChange.Snapshot })
}
```

See [testing.md](../testing.md) for more patterns.

---

## References

- [Android Architecture Components — Lifecycle-aware collection](https://developer.android.com/topic/architecture/ui-layer/stateholders#lifecycle-aware-flow-collection)
- [Android Architecture Blueprints — advanced patterns](https://github.com/android/architecture-samples)
- [Compose State — collectAsStateWithLifecycle](https://developer.android.com/jetpack/compose/state-management#stateflow)
- [Integration guide](../integration-guide.md) — wiring RadioClient into your app
