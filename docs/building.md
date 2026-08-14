# Building and configuration

## Prerequisites

- **JDK 17.** Required by the Clerk SDK; the build sets source and target compatibility to
  17 explicitly.
- **Android SDK** with platform 37 installed.
- Android Studio is optional — the Gradle wrapper is enough.

The build uses the Foojay toolchain resolver, so Gradle can provision a matching JDK itself
if one is not on the path.

## Build

```sh
./gradlew assembleDebug        # debug APK
./gradlew installDebug         # build and install on the connected device
./gradlew test                 # unit tests
./gradlew connectedAndroidTest # instrumented tests (device or emulator required)
./gradlew lint                 # Android Lint
```

The debug APK lands in `app/build/outputs/apk/debug/`.

## Sign-in configuration

Sign-in runs in-app through [Clerk](https://clerk.com) with Google and GitHub social login.
The Clerk **publishable key** is instance-specific, so it is deliberately **not** committed
to this repository. Supply your own through any of these — they are checked in order:

1. `local.properties` (gitignored):

   ```properties
   spettro.clerk.publishableKey=pk_test_your_key_here
   ```

2. A Gradle property:

   ```sh
   ./gradlew assembleDebug -PSPETTRO_CLERK_PUBLISHABLE_KEY=pk_test_your_key_here
   ```

3. An environment variable:

   ```sh
   export SPETTRO_CLERK_PUBLISHABLE_KEY=pk_test_your_key_here
   ```

The key is injected as a `BuildConfig` field by `app/build.gradle.kts`. A build without one
still compiles and runs — and can still be used if a valid session key is already stored on
the device — but the sign-in buttons report that authentication is disabled.

> A Clerk *publishable* key is not a secret in the cryptographic sense; it ships inside every
> client build. It is kept out of the repo because it is instance-specific: committing one
> would point every fork at a single Clerk instance. Never commit a Clerk **secret** key, a
> Spettro API key, or `local.properties`.

To point the app at your own Clerk instance you also need:

- GitHub and Google OAuth connections enabled in the Clerk Dashboard.
- For production keys, this app's package name registered under **Native Applications**.

## Debug URL overrides

Debug builds read two optional global settings so you can aim the app at a local backend.
They are ignored entirely in release builds.

```sh
adb shell settings put global spettro_api_url http://10.0.2.2:8787   # inference API
adb shell settings put global spettro_web_url http://10.0.2.2:3000   # website (key minting)
```

`10.0.2.2` is the host machine as seen from the Android emulator. Clear an override with
`adb shell settings delete global spettro_api_url`.

Defaults are `https://api.spettro.app` and `https://spettro.app`.

## Release builds

The release build type enables R8 with both code and resource shrinking. Project-specific
keep rules live in `app/proguard-rules.pro`; Room, OkHttp, kotlinx.serialization, and
Compose ship their own consumer rules and are not repeated there.

Two things the project rules do cover:

- Generated `kotlinx.serialization` serializers for this app's own models — the chat store
  and the backup format depend on them.
- The Clerk SDK, which deserializes its API models reflectively.

Source file and line number attributes are kept so crash reports from a minified build stay
readable.

No signing configuration is committed. Add your own `signingConfigs` block (reading the
keystore path and passwords from `local.properties` or the environment, never from a
committed file) before producing a distributable build.

## Dependency versions

All versions are centralized in `gradle/libs.versions.toml`. Notable pins:

- **AGP 9** bundles Kotlin 2.2.x, but the Clerk SDK ships Kotlin 2.4 metadata, so the root
  `build.gradle.kts` forces a newer Kotlin Gradle plugin onto the build classpath. This is
  the supported way to override the built-in Kotlin version — do not remove it without
  checking that the Clerk SDK still resolves.
- The Compose BOM governs every Compose artifact; do not pin individual Compose versions.
