# Spotatui Android build

This repository builds an Android port from the official LargeModGames/spotatui source. The upstream desktop builds are not Android APKs, so this workflow rebuilds the mobile app and Rust core for Android.

## Updates

The Android app checks this repository's latest release from Settings → App updates. A signed release publishes spotatui-android-release.apk plus a SHA-256 checksum and source revision. Android will still ask the user to confirm installing a downloaded APK; ordinary sideloaded apps cannot silently self-install an update.

The updater is deliberately separate from the desktop app's built-in updater. APK updates must use the same Android application ID and the same signing certificate as the installed build.

## One-time signing configuration

To publish updateable APK releases, configure these repository Actions secrets before expecting an update to appear:

- ANDROID_KEYSTORE_BASE64: the release keystore encoded as one Base64 line.
- ANDROID_KEYSTORE_PASSWORD: the keystore password.
- ANDROID_KEY_ALIAS: the private-key alias.
- ANDROID_KEY_PASSWORD: the private-key password.

Generate and store the signing keystore privately. Do not commit the keystore or its passwords to this public repository. Preserve the keystore safely; future APKs must be signed with the same key to install over previous releases. The workflow can still build and run emulator smoke tests without these secrets, but it will not publish a public update release.

## CI

The workflow tracks the official repository's main commit and checks it daily. Scheduled builds are skipped when the upstream source revision has already passed the emulator smoke test. Pushes to the build scripts and manual workflow runs force a rebuild. Each successful run records its tested upstream revision.
