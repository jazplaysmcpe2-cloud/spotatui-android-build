# Spotty for Android

Spotty is an independent Android music-discovery and listening app (package `com.spotty.music`). The home screen searches multiple public catalogues in one query. Track cards stay inside Spotty; the source is shown quietly in the player rather than acting as a provider-selection screen.

## What can play inside Spotty?

- **Audius:** searches its public catalogue and streams tracks that the API marks as streamable.
- **Internet Archive:** searches audio marked as Creative Commons/public-domain. Before playback, Spotty checks item metadata and chooses a supported audio file. Only items with a verifiable open licence are played.
- **Deezer:** searches the public API and plays the official preview URL when a preview is provided (typically around 30 seconds). The full-track action opens Deezer explicitly.
- **Local audio:** plays files chosen with Android's document picker, including supported FLAC files. A Soulseek download is played locally after the user imports/selects it.
- **Internet radio:** playable stations can be searched from the radio collection.
- **YouTube catalogue:** results are metadata/listing links, not an in-app full-track stream. Tapping a result opens a Spotty player sheet first; only the explicit "Open in YouTube" action hands the link to Morphe/YouTube or a browser. Spotty does not extract, download, or redistribute YouTube media streams.
- **SoundCloud, Spotify and YouTube Music:** these services do not magically become connected because their apps/websites can be opened. This build does not have an official, authenticated SoundCloud stream/search integration, Spotify catalogue/playback API session, or private YouTube Music history integration. The app does not show a false Connected state.

## Search and player behaviour

The default search merges available Audius, open-licence Internet Archive, Deezer-preview and public YouTube results. Each network request is independent: one catalogue failing should not prevent other providers from returning results. Results are ordered to prioritise sources that can play in Spotty. When all catalogues fail, the page reports a failure rather than pretending that tracks loaded.

Track cards show artwork where the catalogue provides it, title, artist and a small source line. Selecting a directly playable source starts the native audio service and opens the full player. Selecting a source that cannot provide a playable in-app stream opens the player sheet without redirecting; the external hand-off is a separate, explicit choice. The player includes artwork, queue, previous/next, pause/resume, seek position when the source supports it, elapsed/duration indicators, and source metadata at the bottom.

## Background playback and permissions

The native playback service owns the stream outside the Activity lifecycle. It uses Android's foreground-service/media-playback permissions and notification controls, requests audio focus, acquires a partial wake lock while playing, records listening time, and retries stream failures a limited number of times. Local files are selected with Android's document picker; the app does not request unrestricted storage access.

No Android app can guarantee uninterrupted 24/7 playback: the OS may force-stop it, battery management may restrict background activity, and providers can disconnect or remove media. On phones with aggressive battery optimisation, users may need to allow background activity for Spotty.

## Themes and visual design

The theme selector now uses short art-direction descriptions and preview surfaces rather than a bare list of colour names. The themes include Frutiger Aero, Midwest Emo, Synthwave, Vaporwave, Cyberpunk, Aurora Forest, AMOLED, Y2K Glass, Candy Pop, Retro CRT, Sunset Coast and Dark Academia. The redesigned home, cards, player and settings all use the selected palette and theme-specific surfaces. Theme changes are visual preferences only; they do not change audio quality or provider access.

## Provider credentials and limits

No new credentials are required for the current public APIs. Avoid adding secrets until the matching implementation exists.

- **Spotify:** this separate Spotty app does not currently implement Spotify OAuth or streaming. A Spotify developer Client ID alone would not grant catalogue playback; OAuth scopes, policy-compliant API access and an implemented flow would be required. Spotify's rules do not allow combining Spotify recordings with an unrelated multi-service streaming player.
- **SoundCloud:** official in-app search/streaming requires using SoundCloud's supported API access and the permissions/tokens available to the app. This build keeps it as a clearly identified external option rather than faking a connection.
- **Google/YouTube:** public video listings require no Google OAuth client. Account login and private YouTube data would require a separate supported OAuth implementation. Google OAuth does not unlock all YouTube Music listening history.
- **Last.fm:** account scrobbling needs a Last.fm API account and its authentication flow; merely opening .fmbot does not connect Last.fm.
- **Soulseek:** it is a peer-to-peer file-sharing network, not a FLAC streaming API. Spotty plays user-selected local files; users must ensure they have rights to those files.

There is no added client ID, redirect URI, OAuth secret or signing credential hard-coded in this app.

## Build and release signing

Requires JDK 17, Android SDK platform 35, Android build-tools 35.0.0 and Gradle 8.10.2.

```bash
cd spotty
gradle --no-daemon :app:assembleDebug :app:assembleRelease
```

The GitHub Actions workflow builds both variants and runs an Android emulator smoke test, including a real local WAV playback check through the background service. Pull-request builds may use a debug-signed APK for CI only. Publishing from `main` is blocked unless the same four persistent signing secrets are configured under **GitHub Settings → Secrets and variables → Actions → New repository secret**:

- `ANDROID_KEYSTORE_BASE64`: base64 of the long-term JKS keystore, on one line.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: key alias.
- `ANDROID_KEY_PASSWORD`: private-key password.

Keep keystores and passwords out of commits, issues, and pull requests. Back up the release key securely: future releases must use the same key to update an installed build. The initial persistent-key release may require uninstalling an older build that used a different debug key.

