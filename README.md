# Spettro Chat for Android

Native Android client for [Spettro](https://spettro.app), built with Jetpack Compose.

## Building

Open the project in Android Studio or run:

```sh
./gradlew assembleDebug
```

### Authentication configuration

Sign-in happens in-app through [Clerk](https://clerk.com) (Google and GitHub social
login). The Clerk **publishable key** is instance-specific, so it is intentionally
**not** committed to this repository. Provide your own via any of these (checked in
order):

1. `local.properties` (gitignored):

   ```properties
   spettro.clerk.publishableKey=pk_test_...
   ```

2. A Gradle property: `-PSPETTRO_CLERK_PUBLISHABLE_KEY=pk_test_...`
3. The `SPETTRO_CLERK_PUBLISHABLE_KEY` environment variable.

A build without a key still compiles and runs, but the sign-in buttons will report
that authentication is disabled.

To point the app at your own Clerk instance you also need GitHub and Google OAuth
connections enabled in the Clerk Dashboard, and (for production keys) the app's
package name registered under **Native Applications**.

### Debug URL overrides

Debug builds can be pointed at local servers:

```sh
adb shell settings put global spettro_api_url http://10.0.2.2:8787   # API backend
adb shell settings put global spettro_web_url http://10.0.2.2:3000   # website (key minting)
```

## How sign-in works

1. The Clerk Android SDK runs the OAuth flow in a Custom Tab and returns to the app.
2. The app exchanges the Clerk session for a Spettro `ep_` API key by calling
   `POST /api/sync-user` and `POST /api/keys/generate` on spettro.app with the
   session JWT as a Bearer token.
3. The `ep_` key is encrypted with the Android Keystore and stored locally; all
   API traffic (`/v1/models`, `/v1/account`, `/v1/chat/completions`) authenticates
   with it. Signing out revokes the key server-side (best-effort) and ends the
   Clerk session.
