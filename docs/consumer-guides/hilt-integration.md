# Hilt dependency injection integration

> Guide for Android developers integrating the Meshtastic SDK with Hilt dependency injection.

## Overview

The SDK's `RadioClient` is a heavyweight singleton that should be managed by a dependency injector rather than created repeatedly. This guide shows how to set up Hilt to provide the SDK to your app.

## Setup

### 1. Add Hilt dependencies

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")

    // SDK itself
    implementation("org.meshtastic:sdk-core:<version>")
}

android {
    // Enable Java 8+ for Hilt
    kotlinOptions {
        jvmTarget = "1.8"
    }
}
```

### 2. Create a Hilt module for RadioClient

```kotlin
package com.example.myapp.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.transport.BleTransport
import org.meshtastic.sdk.storage.InMemoryStorage
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RadioClientModule {

    @Provides
    @Singleton
    fun provideBleTransport(@ApplicationContext context: Context): BleTransport {
        return BleTransport(context)
    }

    @Provides
    @Singleton
    fun provideRadioClient(
        bleTransport: BleTransport
    ): RadioClient {
        return RadioClient.Builder()
            .transport(bleTransport)
            .storage(InMemoryStorage())  // Or your custom StorageProvider
            .build()
    }
}
```

### 3. Use in your Application

Annotate your `Application` class with `@HiltAndroidApp`:

```kotlin
package com.example.myapp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MeshApp : Application() {
    // Hilt sets up dependency injection for all activities, fragments, etc.
}
```

### 4. Inject into Activities and ViewModels

#### In an Activity

```kotlin
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import org.meshtastic.sdk.RadioClient

@AndroidEntryPoint
class MeshActivity : AppCompatActivity() {

    @Inject
    lateinit var radioClient: RadioClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mesh)

        // radioClient is now injected and ready to use
        connectToRadio()
    }

    private suspend fun connectToRadio() {
        radioClient.connect()
    }
}
```

#### In a ViewModel (Recommended)

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.RadioClient
import javax.inject.Inject

@HiltViewModel
class MeshViewModel @Inject constructor(
    private val radioClient: RadioClient
) : ViewModel() {

    val connection: StateFlow<ConnectionState> = radioClient.connection

    fun connect() {
        viewModelScope.launch {
            try {
                radioClient.connect()
            } catch (e: Exception) {
                // Handle connection error
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            radioClient.disconnect()
        }
    }
}
```

#### In a Fragment

```kotlin
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MeshFragment : Fragment() {

    private val viewModel: MeshViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.connect()
    }
}
```

## Multi-transport setup

If your app supports multiple transports (BLE, Serial, TCP), create separate qualifiers and factories:

```kotlin
package com.example.myapp.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.transport.BleTransport
import org.meshtastic.sdk.transport.SerialTransport
import org.meshtastic.sdk.transport.TcpTransport
import org.meshtastic.sdk.storage.InMemoryStorage
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BleTransportQualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TcpTransportQualifier

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    @BleTransportQualifier
    fun provideBleTransport(@ApplicationContext context: Context): BleTransport {
        return BleTransport(context)
    }

    @Provides
    @Singleton
    @TcpTransportQualifier
    fun provideTcpTransport(host: String, port: Int): TcpTransport {
        return TcpTransport(host, port)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object RadioClientModule {

    @Provides
    @Singleton
    fun provideRadioClient(
        @BleTransportQualifier bleTransport: BleTransport
    ): RadioClient {
        return RadioClient.Builder()
            .transport(bleTransport)
            .storage(InMemoryStorage())
            .build()
    }
}
```

Then inject the qualified transport:

```kotlin
@HiltViewModel
class MeshViewModel @Inject constructor(
    @BleTransportQualifier private val bleTransport: BleTransport,
    private val radioClient: RadioClient
) : ViewModel() {
    // Use bleTransport or switch transports at runtime
}
```

## Persistence and Storage

By default, the module uses `InMemoryStorage()` (data is lost on app restart). For persistence, provide your own `StorageProvider`:

```kotlin
import org.meshtastic.sdk.storage.StorageProvider

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideStorageProvider(@ApplicationContext context: Context): StorageProvider {
        return MyPersistentStorage(context)  // Implement StorageProvider
    }
}

@Module
@InstallIn(SingletonComponent::class)
object RadioClientModule {

    @Provides
    @Singleton
    fun provideRadioClient(
        bleTransport: BleTransport,
        storageProvider: StorageProvider
    ): RadioClient {
        return RadioClient.Builder()
            .transport(bleTransport)
            .storage(storageProvider)
            .build()
    }
}
```

See [`docs/SPEC.md`](../SPEC.md) for the `StorageProvider` interface.

## Lifecycle and cleanup

Hilt manages the lifecycle of singletons automatically — when the app is destroyed, Hilt cleans up injected dependencies. However, ensure your `RadioClient` is properly closed:

```kotlin
@HiltViewModel
class MeshViewModel @Inject constructor(
    private val radioClient: RadioClient
) : ViewModel() {

    override fun onCleared() {
        super.onCleared()
        // Hilt handles cleanup; the RadioClient is closed by the app lifecycle
    }
}
```

The `RadioClient` does not hold resources that require explicit cleanup (no file handles, database connections, etc.), so Hilt's automatic cleanup is sufficient.

## Testing with Hilt

For unit tests, use Hilt's testing framework:

```kotlin
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.meshtastic.sdk.RadioClient

@HiltAndroidTest
class MeshViewModelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var radioClient: RadioClient

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun testConnect() {
        // radioClient is injected for testing
        // You can override bindings per test with @BindValue or custom TestModule
    }
}
```

For more complex mocking, create a test-specific Hilt module:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object TestRadioClientModule {

    @Provides
    @Singleton
    fun provideRadioClient(): RadioClient {
        // Return a test stub or FakeRadioTransport-based client
        return RadioClient.Builder()
            .transport(FakeRadioTransport())
            .storage(InMemoryStorage())
            .build()
    }
}
```

Replace the production module for the test by placing the test module in your test source set.

## Common patterns

### Lazy initialization

If you want to delay creating the `RadioClient` until first use:

```kotlin
@Provides
@Singleton
fun provideRadioClient(
    bleTransport: BleTransport,
    storageProvider: StorageProvider
): Lazy<RadioClient> = lazy {
    RadioClient.Builder()
        .transport(bleTransport)
        .storage(storageProvider)
        .build()
}

// Inject as:
@Inject
lateinit var lazyRadioClient: Lazy<RadioClient>

// Use:
val client = lazyRadioClient.value  // Created on first access
```

### Intercepting RadioClient creation

Use Hilt's `@EntryPoint` to access the client from non-Hilt code:

```kotlin
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RadioClientEntryPoint {
    fun radioClient(): RadioClient
}

// In non-Hilt code:
val entryPoint = EntryPointAccessors.fromApplication(context, RadioClientEntryPoint::class.java)
val client = entryPoint.radioClient()
```

## Troubleshooting

**Q: My Activity crashes with "Cannot access injected field unless it's initialized"**

A: Ensure you annotated the Activity with `@AndroidEntryPoint` and called `super.onCreate()` before accessing injected fields.

**Q: The RadioClient singleton is being recreated on each Activity**

A: Use `@Singleton` on the `@Provides` function and ensure it's in a module installed in `SingletonComponent`.

**Q: Hilt can't find my custom StorageProvider**

A: Make sure the provider is public, not nested in a non-Hilt class, and the module is correctly installed in the right `Component`.

## Related

- [Hilt official documentation](https://dagger.dev/hilt/)
- [`docs/SPEC.md`](../SPEC.md) — RadioClient and StorageProvider interfaces
- [`docs/integration-guide.md`](../integration-guide.md) — general SDK integration
