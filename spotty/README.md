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

## Background playback and permissions

Native URL and local-file audio playback is owned by a foreground media service, not the Activity. It shows a persistent Android notification with pause/resume and stop controls, requests audio focus, handles headphone/other-app focus changes, uses a partial wake lock while audio is actively playing, and retries a failed stream a limited number of times. The service stores its current source so Android can attempt to restore playback after a process restart.

The app declares Internet access, media-playback foreground-service permissions and WAKE_LOCK. Android 13+ notification permission is requested when the user first starts playback. Local files are selected through Android's document picker / folder picker, so the app does not ask for unrestricted storage access.

No Android app can honestly guarantee 24/7 uninterrupted audio: the OS can force-stop an app, battery settings can restrict it, and remote providers can disconnect or remove a track. A persistent notification and bounded reconnection make playback more robust, not infallible. On phones with aggressive battery management, users may need to allow background activity for Spotty.

## Build and persistent release signing

Requires JDK 17, Android SDK platform 35, Android build tools 35.0.0, and Gradle 8.10.2.

```bash
cd spotty
gradle --no-daemon :app:assembleDebug :app:assembleRelease
```

The public release workflow builds both variants. Pull-request CI can fall back to the debug-signed APK when release secrets are absent; public releases from `main` are blocked unless all four persistent signing secrets are set. Configure these under GitHub **Settings → Secrets and variables → Actions → New repository secret**:

- `ANDROID_KEYSTORE_BASE64`: base64 of the long-term JKS keystore, all one line
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: keystore alias
- `ANDROID_KEY_PASSWORD`: private-key password

Keep the keystore and values out of the repo, issues, commits, and PR comments. Back up the key securely; losing it prevents normal in-place updates. The previously distributed build 47 was debug-signed, so switching to a different release key means uninstalling that debug build before installing the first persistent-key release (local data may be removed). Subsequent releases must reuse this same key.

Once the secrets are set, re-run the Spotty workflow or make a commit to trigger it. The workflow verifies the signed, non-debuggable APK, installs and launches it in an Android emulator, checks the media-service permissions and crash/ANR logs, and publishes a tagged release from `main` only after all gates pass.

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

On app launches, Spotty checks its GitHub releases feed and the official Morphe manager releases no more than once every 30 days (or when requested in Settings). It notifies when a new Morphe manager tag appears and links to the official release page. Spotty does not silently patch or replace Morphe/YouTube apps; review and apply patches in Morphe Manager yourself. Android still requires the user to confirm any Spotty APK installation. The build workflow runs monthly on the first day, builds the current default-branch revision, smoke-tests it on an Android API 35 emulator, captures an emulator screenshot, and publishes a release only if all checks pass. Dependabot checks GitHub Actions dependencies monthly.
