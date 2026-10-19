# Spotty Android app and legacy Spotatui build

This repository contains a standalone Android project named **Spotty** in `spotty/`. Spotty has its own Android app ID (`com.spotty.music`) and does not use Spotatui, Spotify Connect, or Spotify Premium.

## Spotty

Spotty searches YouTube through documented public Invidious API instances, then opens selected YouTube links in Morphe YouTube when installed. It also offers Audius, licensed/public-domain Internet Archive audio, and free internet radio. YouTube playback is delegated to Morphe/the phone's default YouTube handler; Spotty does not extract YouTube streams.

Build and behavior notes are in [spotty/README.md](spotty/README.md). The `Build Spotty Android APK` workflow builds, verifies, and smoke-tests the standalone app and publishes a release after a successful build on `main`.

## Legacy Spotatui builder

The existing root-level workflow `.github/workflows/build-spotatui-android.yml` remains for the prior Spotatui Android experiment. It is separate from Spotty. The Spotatui APK still inherits Spotatui's service/account limitations; use Spotty for the independent no-Spotify-Premium project.

## Signing

The Spotty workflow can use these repository Actions secrets to sign releases with a persistent key:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit a signing keystore or passwords. If the secrets are not configured, the workflow falls back to the Android debug signing key; that build is installable but later builds may need a reinstall.
