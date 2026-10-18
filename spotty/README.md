# Spotty for Android

Spotty is an independent Android app (package `com.spotty.music`) with its own native UI and player for sources that permit direct streaming. It is not Spotatui and does not use Spotify's API, Spotify Connect, or a Spotify account.

## YouTube via Morphe

Spotty searches the YouTube catalog through public Invidious instances and hands the selected YouTube link to the Morphe YouTube app (package `app.morphe.android.youtube`). Spotty does **not** extract, download, or redistribute YouTube media streams.

For background audio on YouTube, install and configure Morphe separately using the official project: https://github.com/MorpheApp/morphe-manager. Morphe patches the YouTube app on the device; it is not a Spotify replacement API. When Morphe isn't installed, Spotty falls back to the Android app/browser that can open YouTube links. Some YouTube content can still require sign-in or have availability restrictions.

## Other free sources

- **Audius:** searchable/trending community music through its public API.
- **Internet Archive:** searches audio items and checks for a Creative Commons/public-domain license before streaming a supported audio file.
- **Radio:** searches free internet radio stations.

YouTube catalog searching depends on public Invidious instances, which are third-party community services and can be temporarily unavailable or rate-limited. Spotty includes several documented instances as fallbacks; if none respond, it offers to open the search directly in Morphe/YouTube.

## Build

Requires JDK 17, Android SDK platform 35, Android build tools 35.0.0, and Gradle 8.10.2.

```bash
cd spotty
gradle --no-daemon :app:assembleDebug
```

The APK is at `spotty/app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow builds, verifies, and smoke-tests the APK. It publishes a release from `main` when the build succeeds.

## Limitations

- Spotty's YouTube result cards hand playback off to Morphe or the phone's default YouTube handler. It does not stream YouTube audio/video within its own player.
- Morphe is a separate third-party patching project; install it only from its official project/documentation and review its permissions and patches.
- Public search instances and streaming providers can change or go offline.
- No Spotify subscription, Spotify credentials, or Spotify account are needed.


## Playlists, audio quality and downloads

Use **Import playlist** to open a CSV/M3U/text playlist or paste track rows. A common Spotify playlist-export CSV with `Track Name` and `Artist Name(s)` columns is supported. A Spotify share URL by itself is not a portable track-list export: Spotify's playlist-items API uses OAuth and has access restrictions. Imported rows are saved locally; selecting a row searches that title/artist on YouTube and hands it to Morphe/YouTube.

## Themes and quality preferences

Themes include Frutiger Aero (default), Midwest Emo, Synthwave, Vaporwave, Cyberpunk, Aurora Forest and AMOLED. Audio quality preferences choose among files the licensed provider offers; they cannot increase an encoded file's quality. Lossless formats or 320 kbps are available only when the actual source file supplies them.

## Download rules

The **Download licensed audio** action appears only on Internet Archive results. Before queueing the download, Spotty checks the item's metadata for a Creative Commons or public-domain license and then downloads the best supported audio file matching your quality preference. It does not download protected Spotify tracks or extract YouTube audio/video.

## Updates

On app launches, Spotty checks the GitHub releases feed no more than once every 30 days (or when requested in Settings). It links to a release when a new tag is available; Android still requires the user to confirm any APK installation. The build workflow runs a monthly on the first day, builds the current default-branch revision, smoke-tests it on an Android API 35 emulator, captures an emulator screenshot, and publishes a release only if all checks pass. Dependabot checks GitHub Actions dependencies monthly.
