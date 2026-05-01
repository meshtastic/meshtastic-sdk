# MVVM integration with Jetpack Compose and StateFlow

> Guide for integrating the Meshtastic SDK with Android MVVM pattern, Jetpack Compose, and StateFlow-based UI state.

## Overview

The SDK is designed around reactive flows: `connection`, `packets`, `events` are `Flow` types that emit UI state changes as they occur. This document shows how to compose them into a clean MVVM architecture using Jetpack Compose.

## Basic setup

### Add dependencies

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.04.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose")

    // Jetpack ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Meshtastic SDK
    implementation("org.meshtastic:sdk-core:<version>")
}
```

## ViewModel pattern

### Example ViewModel

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.MeshEvent
import org.meshtastic.sdk.MeshPacket
import org.meshtastic.sdk.RadioClient

class MeshViewModel(private val client: RadioClient) : ViewModel() {

    // Expose SDK flows as StateFlow for Compose integration
    val connectionState: StateFlow<ConnectionState> = client.connection
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    val packets: Flow<MeshPacket> = client.packets

    val events: Flow<MeshEvent> = client.events

    // Local UI state (not from SDK)
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Derived state: is connected?
    val isConnected: StateFlow<Boolean> = connectionState
        .map { it is ConnectionState.Connected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun connect() {
        viewModelScope.launch {
            _uiState.value = UiState.Connecting
            try {
                client.connect()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            client.disconnect()
            _uiState.value = UiState.Idle
        }
    }

    fun sendMessage(text: String, to: Int = 0xFFFFFFFF) {
        if (!(connectionState.value is ConnectionState.Connected)) {
            _uiState.value = UiState.Error("Not connected")
            return
        }

        viewModelScope.launch {
            try {
                val packet = MeshPacket(to = to, text = text)
                client.send(packet)
                _uiState.value = UiState.MessageSent
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Send failed")
            }
        }
    }
}

sealed class UiState {
    object Idle : UiState()
    object Connecting : UiState()
    object MessageSent : UiState()
    data class Error(val message: String) : UiState()
}
```

### Inject ViewModel with Hilt (recommended)

```kotlin
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.meshtastic.sdk.RadioClient
import javax.inject.Inject

@HiltViewModel
class MeshViewModel @Inject constructor(
    private val client: RadioClient
) : ViewModel() {
    // ViewModel code as above
}

// In your Compose function:
@Composable
fun MeshScreen(viewModel: MeshViewModel = hiltViewModel()) {
    // Use viewModel
}
```

## Compose UI integration

### Collecting flows in Compose

```kotlin
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier

@Composable
fun MeshScreen(viewModel: MeshViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    when (connectionState) {
        is ConnectionState.Disconnected -> {
            Text("Disconnected")
            Button(onClick = { viewModel.connect() }) {
                Text("Connect")
            }
        }
        is ConnectionState.Connecting -> {
            Text("Connecting...")
        }
        is ConnectionState.Connected -> {
            Text("Connected!")
            Button(onClick = { viewModel.disconnect() }) {
                Text("Disconnect")
            }
        }
        is ConnectionState.Reconnecting -> {
            Text("Reconnecting...")
        }
    }

    when (uiState) {
        is UiState.Error -> Text("Error: ${(uiState as UiState.Error).message}")
        is UiState.MessageSent -> Text("Message sent!")
        else -> {}
    }
}
```

### Collecting packets in a LazyColumn

```kotlin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.collectAsState
import org.meshtastic.sdk.MeshPacket

@Composable
fun MessageList(viewModel: MeshViewModel) {
    val packets by viewModel.packets
        .runningFold(emptyList<MeshPacket>()) { acc, packet -> acc + packet }
        .collectAsState(initial = emptyList())

    LazyColumn {
        items(packets) { packet ->
            MessageItem(packet)
        }
    }
}

@Composable
fun MessageItem(packet: MeshPacket) {
    Text("From ${packet.from}: ${packet.text}")
}
```

### Listening to events

```kotlin
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.filterIsInstance

@Composable
fun EventListener(viewModel: MeshViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is MeshEvent.Notification -> {
                        // Show notification
                    }
                    is MeshEvent.TransportError -> {
                        // Handle transport error
                    }
                    is MeshEvent.PacketsDropped -> {
                        // Handle backpressure
                    }
                    else -> {}
                }
            }
        }
    }
}
```

## Advanced patterns

### Combining multiple flows

```kotlin
import kotlinx.coroutines.flow.combine

@Composable
fun CombinedState(viewModel: MeshViewModel) {
    val combinedState = combine(
        viewModel.connectionState,
        viewModel.packets
    ) { connection, packet ->
        Pair(connection, packet)
    }.collectAsState(initial = Pair(ConnectionState.Disconnected, null))

    val (connection, packet) = combinedState.value
    Text("Connected: ${connection is ConnectionState.Connected}, Last packet: ${packet?.text}")
}
```

### Debouncing repeated events

```kotlin
import kotlinx.coroutines.flow.debounce

@Composable
fun DebouncedEvents(viewModel: MeshViewModel) {
    val debouncedEvents by viewModel.events
        .debounce(300)  // Ignore events within 300ms
        .collectAsState(initial = null)

    debouncedEvents?.let {
        Text("Event: $it")
    }
}
```

### Caching UI state across configuration changes

```kotlin
import androidx.lifecycle.SavedStateHandle

@HiltViewModel
class MeshViewModel @Inject constructor(
    private val client: RadioClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    // savedStateHandle survives process death; useful for caching selections
    private val _selectedNodeId = savedStateHandle.getStateFlow("selectedNodeId", -1)
    val selectedNodeId: StateFlow<Int> = _selectedNodeId

    fun selectNode(nodeId: Int) {
        _selectedNodeId.value = nodeId
    }
}
```

### Pre-rendering (offline support)

If you want to display cached state while reconnecting:

```kotlin
@Composable
fun MeshScreen(viewModel: MeshViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val cachedPackets = remember { mutableListOf<MeshPacket>() }
    val packets by viewModel.packets
        .onEach { cachedPackets.add(it) }
        .runningFold(emptyList<MeshPacket>()) { acc, packet -> acc + packet }
        .collectAsState(initial = emptyList())

    // Always render cached packets, even if disconnecting
    LazyColumn {
        items(cachedPackets) { packet ->
            MessageItem(packet)
        }
    }
}
```

## Error handling

### Global error snackbar

```kotlin
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun MeshScreenWithErrorHandling(viewModel: MeshViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is UiState.Error) {
            scope.launch {
                snackbarHostState.showSnackbar((uiState as UiState.Error).message)
            }
        }
    }

    Box {
        // Main content
        MeshContent(viewModel)

        // Error snackbar
        SnackbarHost(snackbarHostState)
    }
}
```

### Retry logic

```kotlin
@Composable
fun ConnectWithRetry(viewModel: MeshViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()

    if (connectionState is ConnectionState.Reconnecting) {
        val attempt = (connectionState as ConnectionState.Reconnecting).attempt
        Text("Reconnecting (attempt $attempt)...")
        Button(onClick = { viewModel.connect() }) {
            Text("Retry now")
        }
    }
}
```

## Testing

### Unit tests with a fake client

```kotlin
import org.junit.Test
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorage
import org.meshtastic.sdk.RadioClient
import kotlinx.coroutines.test.runTest

class MeshViewModelTest {

    @Test
    fun testConnect() = runTest {
        val transport = FakeRadioTransport()
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorage())
            .build()

        val viewModel = MeshViewModel(client)

        viewModel.connect()

        // Simulate config complete
        transport.simulateConfigComplete()

        // Assert state
        assert(viewModel.isConnected.value)
    }
}
```

### Compose UI tests

```kotlin
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

class MeshScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testConnectButton() {
        composeTestRule.setContent {
            MeshScreen(mockViewModel)
        }

        composeTestRule
            .onNodeWithText("Connect")
            .performClick()

        composeTestRule
            .onNodeWithText("Connecting...")
            .assertExists()
    }
}
```

## Architecture diagram

```
┌──────────────────────────────────────────┐
│       Jetpack Compose UI Layer           │
│  (collectAsState, LaunchedEffect)        │
└──────────────────┬───────────────────────┘
                   │
                   ↓
┌──────────────────────────────────────────┐
│        MeshViewModel (MVVM)              │
│  (StateFlow, coroutineScope.launch)      │
└──────────────────┬───────────────────────┘
                   │
                   ↓
┌──────────────────────────────────────────┐
│      RadioClient (SDK)                   │
│  (Flow<packets>, Flow<events>)           │
└──────────────────┬───────────────────────┘
                   │
                   ↓
┌──────────────────────────────────────────┐
│   Transport (BLE, TCP, Serial)           │
│   Storage (Persistent/In-memory)         │
└──────────────────────────────────────────┘
```

## Related

- [`docs/SPEC.md`](../SPEC.md) — SDK API reference
- [`docs/integration-guide.md`](../integration-guide.md) — general integration
- [`docs/testing.md`](../testing.md) — SDK testing patterns
- [Jetpack Compose documentation](https://developer.android.com/jetpack/compose)
- [ViewModel official guide](https://developer.android.com/topic/libraries/architecture/viewmodel)
