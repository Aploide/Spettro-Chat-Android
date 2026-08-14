# Third-party notices

Copyright (C) 2026 Carlo Esposito / Eyed® Softwares

Spettro Chat for Android is licensed under the GNU General Public License, version 3 or
later, with the additional terms in [LICENSE-EXCEPTION](LICENSE-EXCEPTION). It ships with
the third-party components listed here, each of which remains under its own license.

This list covers what is bundled into a release build. Build-time-only and test-only
dependencies are listed separately at the end and are not distributed with the app.

Versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml); the exact
resolved set for a given build is available from `./gradlew :app:dependencies`.

---

## Apache License 2.0

> Licensed under the Apache License, Version 2.0 (the "License"); you may not use these
> files except in compliance with the License. You may obtain a copy of the License at
> <https://www.apache.org/licenses/LICENSE-2.0>. Unless required by applicable law or
> agreed to in writing, software distributed under the License is distributed on an "AS IS"
> BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

| Component | Copyright |
|---|---|
| AndroidX libraries (`androidx.*`), including Jetpack Compose, Room, DataStore, Lifecycle, Activity, Credentials, Browser, Biometric, Window | The Android Open Source Project |
| Kotlin standard library (`org.jetbrains.kotlin:*`) | JetBrains s.r.o. and Kotlin Programming Language contributors |
| kotlinx.coroutines, kotlinx.serialization, kotlinx.datetime, kotlinx.collections.immutable | JetBrains s.r.o. |
| JetBrains `markdown`, `annotations`, and Compose Multiplatform `foundation` | JetBrains s.r.o. |
| OkHttp, `okhttp-android`, `logging-interceptor`, Okio, Retrofit, `converter-kotlinx-serialization` | Square, Inc. |
| Gson | Google Inc. |
| Guava `listenablefuture` | The Guava Authors |
| JSpecify | The JSpecify Authors |
| [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) | Mike Penz |
| [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (`com.tom-roush:pdfbox-android`), a port of Apache PDFBox | Tom Roush; The Apache Software Foundation |
| [MediaPipe Tasks](https://github.com/google-ai-edge/mediapipe) (`com.google.mediapipe:tasks-text`), including its bundled TensorFlow Lite runtime and protobuf | Google LLC |

The Universal Sentence Encoder model file that `tasks-text` runs is **not** bundled; it is
downloaded on demand from Google's model hosting (Apache License 2.0, © Google LLC) only
when the user enables enhanced semantic recall in Settings.

## Mozilla Public License 2.0

> This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
> If a copy of the MPL was not distributed with this file, You can obtain one at
> <https://mozilla.org/MPL/2.0/>.

| Component | Copyright |
|---|---|
| [Rhino](https://github.com/mozilla/rhino) (`org.mozilla:rhino`), the JavaScript engine behind the run-javascript sandbox | Mozilla and individual contributors |

## MIT License

> Permission is hereby granted, free of charge, to any person obtaining a copy of this
> software and associated documentation files (the "Software"), to deal in the Software
> without restriction, including without limitation the rights to use, copy, modify, merge,
> publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
> to whom the Software is furnished to do so, subject to the following conditions: the above
> copyright notice and this permission notice shall be included in all copies or substantial
> portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
> EXPRESS OR IMPLIED.

| Component | Copyright |
|---|---|
| [Clerk Android SDK](https://github.com/clerk/clerk-android) (`com.clerk:clerk-android-api`, `com.clerk:automap-annotations`) | Clerk, Inc. |
| [icons-lucide](https://github.com/composablehorizons/composeicons) (`com.composables:icons-lucide`) | Composables |
| [JWTDecode.Android](https://github.com/auth0/JWTDecode.Android) (`com.auth0.android:jwtdecode`) | Auth0, Inc. |

The Lucide icon set drawn by `icons-lucide` is itself distributed under the ISC License
(© Lucide Contributors), which derives from Feather (© Cole Bemis, MIT).

## Proprietary — covered by the GPL linking exception

These components are **not** free software. They are distributed under Google's own terms,
are not modifiable or redistributable under the GPL, and are the reason this project grants
the additional permission in [LICENSE-EXCEPTION](LICENSE-EXCEPTION). They enter the build
only as transitive dependencies of the Clerk Android SDK.

| Component | Terms |
|---|---|
| Google Play services client libraries (`com.google.android.gms:play-services-auth`, `-auth-api-phone`, `-auth-base`, `-auth-blockstore`, `-base`, `-basement`, `-fido`, `-identity-credentials`, `-tasks`) | [Android Software Development Kit License Agreement](https://developer.android.com/studio/terms) |
| Google Identity (`com.google.android.libraries.identity.googleid:googleid`) | Android Software Development Kit License Agreement |
| Play Core Common (`com.google.android.play:core-common`) | Play Core Software Development Kit Terms of Service |
| Play Integrity API (`com.google.android.play:integrity`) | Play Integrity API Terms of Service |

Removing the Clerk SDK — replacing it with a self-hosted OAuth flow — would remove every
component in this section, and with it the need for the linking exception.

---

## Not distributed with the app

Build-time and test-only dependencies, listed for completeness:

| Component | License |
|---|---|
| Android Gradle Plugin, Kotlin Gradle Plugin, KSP (`com.google.devtools.ksp`), Room compiler | Apache License 2.0 |
| Espresso, AndroidX Test, Compose UI test | Apache License 2.0 |
| JUnit 4 | Eclipse Public License 1.0 |

JUnit is used only by the test source sets and is never packaged into a release APK.

---

## Brand assets

The Eyed® logo is a registered trademark of Carlo Esposito, registered with the Italian
Patent and Trademark Office (UIBM) under no. 302024000146292. The Spettro, Eyed, and Eyed®
Softwares names, logos, wordmarks, product names, and app icons are the property of Carlo
Esposito and Eyed® Softwares — see the [Eyed® Terms of Use](https://aploi.de/eyed-TOS/).

These are **not** covered by the GPL and are not licensed for use in derivative works. If you
distribute a fork, replace the app name, application id, icon, and any other brand assets
with your own. See Part 2 of [LICENSE-EXCEPTION](LICENSE-EXCEPTION).
