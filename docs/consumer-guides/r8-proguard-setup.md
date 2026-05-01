# R8/Proguard configuration for Meshtastic SDK

> Guide for Android app developers using the Meshtastic SDK with R8 code shrinking enabled (AGP 9+) or legacy Proguard.

## Overview

The SDK ships with built-in R8 keep rules that protect public API types and internal protocol machinery from being renamed or removed. Consumer apps should inherit these rules automatically — no manual configuration is required in most cases.

However, if you have a custom Proguard/R8 configuration or need to test obfuscation locally, this guide explains the rules and how to extend them.

## Automatic rule application

When you add the SDK dependency to your Android app:

```gradle
dependencies {
    implementation("org.meshtastic:sdk-core:<version>")
}
```

The AGP automatically discovers and applies the SDK's built-in rules from `META-INF/proguard/meshtastic.pro` at build time. No manual `proguardFiles` directive is needed.

To verify rules are being applied, build with `--verbose` and grep the output for `proguard`:

```bash
./gradlew assemble --verbose 2>&1 | grep -i proguard | head -10
```

## SDK keep rules

The SDK protects:

1. **All public types and their members** — `MeshtasticException`, `RadioClient`, `MeshPacket`, sealed class hierarchies, etc. — from removal or renaming.
2. **Public enum fields** — `ConnectionState`, `SendState`, etc. — needed for exhaustive `when` expressions.
3. **Public constructors and factory functions** — e.g., `RadioClient.Builder(...)`.
4. **Internal protocol-critical types** — wire codec helpers, serialization machinery — from renaming (kept because obfuscating wire-specific class names risks accidentally breaking serialization).
5. **Kotlin metadata** — `@Metadata` annotations — so reflection-based libraries (e.g., kotlinx-serialization) can function.

See the SDK source for the authoritative rules: `<module>/build.gradle.kts` — search for `consumerProguardFiles`.

## Custom consumer rules (if needed)

If you have app-level obfuscation rules that conflict with the SDK's, ensure your rules do not further shrink the SDK's public API. Add to your `proguard-rules.pro`:

```proguard
# Preserve all SDK public types (the SDK ships its own, but listing here for explicitness)
-keep class org.meshtastic.sdk.** { *; }

# Preserve enum constructors if you're using reflection on ConnectionState, SendState, etc.
-keepclassmembers enum org.meshtastic.sdk.** {
  public static **[] values();
  public static ** valueOf(java.lang.String);
}

# If using Hilt dependency injection with SDK types, ensure Hilt-generated code is kept
-keep class ** extends dagger.hilt.android.HiltAndroidApp
```

## Testing obfuscation locally

To verify your app's shrinking and obfuscation work with the SDK:

1. Build a release APK:
   ```bash
   ./gradlew assembleRelease --no-daemon
   ```

2. Inspect the generated `mapping.txt` to verify SDK classes are not removed:
   ```bash
   grep "org.meshtastic.sdk" build/outputs/mapping/release/mapping.txt
   ```

3. If a class is missing, check whether your custom rules or a dependency is incorrectly shrinking it.

4. Decompile the APK and verify public API is intact:
   ```bash
   unzip -q app-release.apk -d unzipped/
   # Use a Kotlin/Java decompiler (cfr, procyon, fernflower, etc.)
   ```

## Debugging shrinking issues

If your app crashes at runtime with `ClassNotFoundException`, `NoSuchMethodError`, or `NoWhenBranchMatchedException` after enabling R8, the failure is almost always one of three things: a missing keep rule, an attribute stripped by R8 full mode, or a Kotlin value-class accessor name mismatch. This section walks through the diagnostic workflow, then shows worked examples for the failure modes consumers hit most often with this SDK.

### Diagnostic workflow

Use this checklist top-down. Most issues are caught by step 2 or 3.

1. **Reproduce in release without shrinking.** Temporarily disable R8 to confirm the failure is shrinking-related:
   ```kotlin
   // app/build.gradle.kts
   android.buildTypes.release {
       isMinifyEnabled = false   // disables R8 entirely
   }
   ```
   If the failure goes away, it is an R8 issue. Re-enable shrinking before continuing.

2. **Inspect the merged R8 configuration.** Every keep rule that R8 applies (yours, the SDK's, and every transitive library's) is dumped to `app/build/outputs/mapping/release/configuration.txt` after a release build. Search it for SDK types to confirm the SDK's bundled rules are being applied:
   ```bash
   ./gradlew :app:assembleRelease
   grep "org.meshtastic" app/build/outputs/mapping/release/configuration.txt | head -20
   ```
   No matches means the SDK's `META-INF/proguard/meshtastic.pro` was not picked up — usually because of a pathological `consumerProguardFiles = []` or a multi-module setup that strips library proguard files.

3. **Use `-whyareyoukeeping` to debug "kept too much" / "stripped too much".** Add to your `proguard-rules.pro`:
   ```proguard
   -whyareyoukeeping class org.meshtastic.sdk.RadioClient { *; }
   ```
   R8 prints the chain from your app's entry points to the kept class. Inversely, if a class is missing, `-whyareyoukeeping` on the *expected* type and a release build will tell you whether it was ever in the keep set at all.

4. **Read the obfuscated stack trace via `retrace`.** AGP 9 + Android Studio Otter 3 deobfuscate Logcat automatically. For everything else (CI logs, Crashlytics console exports, raw `adb logcat` from older Studio), use the bundled tool:
   ```bash
   $ANDROID_HOME/cmdline-tools/latest/bin/retrace \
     app/build/outputs/mapping/release/mapping.txt \
     trace.txt
   ```
   See [Android: R8 retrace](https://developer.android.com/studio/command-line/retrace).

5. **For Crashlytics**, the Gradle plugin uploads `mapping.txt` automatically — no opt-in required since AGP 4.2+. Crashes appear deobfuscated in the Firebase console. To verify upload happened, look for `Crashlytics: Successfully uploaded mapping file` in the build log. To disable per-variant, set `firebaseCrashlytics.mappingFileUploadEnabled = false` (see [Firebase docs](https://firebase.google.com/docs/crashlytics/android/get-deobfuscated-reports)).

### R8 full mode (default since AGP 8.0)

Full mode is more aggressive than legacy compatibility mode: it can merge classes vertically/horizontally, inline across class boundaries, and strips bytecode attributes more aggressively. **Most SDK consumers should not need to change anything** — the SDK's bundled rules are written for full mode. However, two attributes commonly need preservation when consumer code reflects on SDK types:

```proguard
# Required for kotlin-reflect on SDK sealed hierarchies (MeshEvent, SendFailure, ConnectionState).
-keepattributes Signature,InnerClasses,EnclosingMethod

# Required for any library that uses generic-typed reflection (Gson, Moshi-codegen, etc.) on SDK types.
# Not needed if you do not deserialize SDK types with reflective parsers.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
```

Without `Signature`, `kotlin-reflect`'s `KClass.sealedSubclasses` returns an empty list at runtime even though `when (event) { is MeshEvent.IdentityRebound -> … }` compiles fine — see the worked example below. See [Use R8 in full mode](https://developer.android.com/topic/performance/app-optimization/full-mode) for the full attribute matrix.

### Worked examples

#### `ClassNotFoundException: org.meshtastic.sdk.RadioClient$Builder`

Your app crashes on the very first `RadioClient.Builder()` call. The class exists in the SDK and is publicly kept. Cause: the SDK's `META-INF/proguard/meshtastic.pro` was not merged into your build. Most likely culprits:

- Your app applies `consumerProguardFiles` with an explicit list that excludes library files
- A custom Gradle plugin filters the META-INF directory
- You depend on a non-AAR repackaging of the SDK (e.g. shadow-jar) that drops resources

**Diagnose**:
```bash
unzip -p app/build/outputs/apk/release/app-release.apk META-INF/proguard/meshtastic.pro | head
# Empty output → SDK rules absent. Inspect your Gradle config for filters.
grep -r "org.meshtastic" app/build/outputs/mapping/release/configuration.txt
# No output → confirms rules never reached R8.
```

**Fix**: ensure `consumerProguardFiles` is not stripping library rules. The default behavior is correct; only override if you know why.

#### `NoSuchMethodError: org.meshtastic.sdk.NodeInfo.getNodeId-PPSJZE4()`

A custom keep rule references the *mangled* JVM accessor for an inline value class. Since v0.1.0 the SDK ships `-Xjvm-expose-boxed`, which generates the unmangled `getNodeId()` accessor — the mangled `getNodeId-PPSJZE4()` form is no longer the public ABI for Java callers.

**Fix**: replace mangled references with their unmangled equivalents in your keep rules:
```proguard
# WRONG — references mangled internal accessor, will not match in newer SDK versions
-keepclassmembers class org.meshtastic.sdk.NodeInfo {
    public int getNodeId-PPSJZE4();
}

# RIGHT — matches the publicly exposed boxed accessor
-keepclassmembers class org.meshtastic.sdk.NodeInfo {
    public org.meshtastic.sdk.NodeId getNodeId();
}
```
See the [API hygiene notes in CHANGELOG](../../CHANGELOG.md) for the full migration. In practice the SDK's bundled rules already cover this; only manually-added consumer rules are at risk.

#### `NoWhenBranchMatchedException` on a sealed `MeshEvent` branch

You collect `client.events` and `when` over the sealed `MeshEvent` hierarchy. Compiles fine in release; throws at runtime. Cause: a sealed subclass that your app constructs **only via reflection** (e.g. through kotlinx-serialization, kotlin-reflect, or a generic event bus) was stripped or its `Signature` attribute was lost.

**Diagnose**:
```bash
# Confirm the missing subclass is absent from the dex
$ANDROID_HOME/build-tools/35.0.0/dexdump -f app-release.apk \
  | grep "Lorg/meshtastic/sdk/MeshEvent\$"
```

**Fix** — add to `proguard-rules.pro`:
```proguard
# Keep all MeshEvent variants and their members, plus Signature for reflective dispatch.
-keep class org.meshtastic.sdk.MeshEvent$* { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
```
Apply the same pattern to `SendFailure` (`SendFailure$*`) and `ConnectionState` (`ConnectionState$*`) if your app reflects on those.

#### "BLE transport silently does nothing in release, works in debug"

The Kable BLE library reflects on companion objects to wire up the platform `Scanner`. R8 full mode strips them by default. The SDK's bundled rules cover Kable's needs, but if you re-export Kable types or add custom scanner filters, you may need:
```proguard
-keep class com.juul.kable.** { *; }
-keep class com.juul.kable.**$Companion { *; }
```
Check `configuration.txt` first — the SDK ships these already; you only need to re-add if a custom `consumerProguardFiles` setup is dropping them.

#### `mapping.txt` exists but stack traces still look mangled

Crashlytics did not upload the mapping file for that build, or you are deobfuscating against the wrong build's mapping. Each release build **overwrites** `mapping.txt` — you must archive a copy per published version. CI tip:
```bash
# After release build
cp app/build/outputs/mapping/release/mapping.txt \
   release-artifacts/mapping-${VERSION}.txt
```
For Crashlytics, verify the build log contains `Crashlytics: Successfully uploaded mapping file`. If not, the Firebase Crashlytics Gradle plugin is not configured — add `id("com.google.firebase.crashlytics")` and the `google-services` plugin per [Firebase docs](https://firebase.google.com/docs/crashlytics/android/get-deobfuscated-reports).

### Reading `mapping.txt`

A line like:
```
org.meshtastic.sdk.internal.MeshEngine -> a.b.c:
    void handleHandshakeTimeout() -> a
    void processRoutingAck(int,int) -> b
```
means `MeshEngine` was renamed to `a.b.c`, `handleHandshakeTimeout()` to `a()`, and `processRoutingAck(int,int)` to `b(int,int)`. When a Crashlytics frame says `at a.b.c.b(Unknown Source:42)`, `retrace` resolves it to `MeshEngine.processRoutingAck:42`.

### When to file an SDK bug

If a public SDK type or member is being stripped despite the SDK's own keep rules being present in `configuration.txt`, that's an SDK bug. File an issue with:

- The relevant lines from `app/build/outputs/mapping/release/configuration.txt`
- A minimal reproducer (`build.gradle.kts`, `proguard-rules.pro`, the failing call site)
- The full `retrace`d stack trace
- Your AGP, Kotlin, and SDK versions
- Whether you use R8 full mode or compatibility mode (default is full since AGP 8.0)

## Native code and so files

The SDK does not ship native libraries. Transports (`transport-ble`, `transport-serial`, `transport-tcp`) are pure Kotlin. If your app uses native C/C++ code, ensure your `Android.mk` or `CMakeLists.txt` does not conflict with JNI bindings — but the SDK does not create any.

## Gradle plugin versions

- **AGP 9.0+** (Gradle 8.9+): R8 is the only shrinker; legacy ProGuard support is removed. R8 full mode is the default. Logcat in Android Studio Otter 3 / AGP 9 deobfuscates stack traces automatically.
- **AGP 8.0–8.x**: R8 is the default shrinker; R8 full mode is the default since AGP 8.0. To opt out: `android.enableR8.fullMode=false` in `gradle.properties` (not recommended).
- **AGP 7.x**: R8 is the default shrinker; full mode is opt-in via `android.enableR8.fullMode=true`.
- **AGP 4.x–6.x**: R8 is the default; legacy ProGuard available as opt-in. The SDK's bundled rules target R8 syntax and may need translation for ProGuard.

## Related

- [Meshtastic Android reference](https://github.com/meshtastic/Meshtastic-Android) — source of truth for R8 configuration on real devices.
- [Android R8 official guide](https://developer.android.com/build/shrink-code) — official Android documentation.
- [ProGuard manual](https://www.guardsquare.com/proguard/manual) — in-depth reference (legacy, but concepts still apply).
