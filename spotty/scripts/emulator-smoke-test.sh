#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
APK="$GITHUB_WORKSPACE/release-output/spotty-android-release.apk"
SMOKE_APK="$GITHUB_WORKSPACE/release-output/spotty-android-smoke.apk"
ERROR_LOG="$GITHUB_WORKSPACE/release-output/emulator-errors.txt"
LOGCAT="$GITHUB_WORKSPACE/release-output/emulator-logcat.txt"
SCREENSHOT="$GITHUB_WORKSPACE/release-output/emulator-smoke-test.png"
WAV_FIXTURE="$GITHUB_WORKSPACE/release-output/spotty-ci-smoke.wav"
PLAYBACK_LOGCAT="$GITHUB_WORKSPACE/release-output/background-playback-logcat.txt"
SERVICE_LOG="$GITHUB_WORKSPACE/release-output/background-playback-service.txt"
SERVICE_DUMP="$GITHUB_WORKSPACE/release-output/background-playback-service-dump.txt"

test -s "$APK"
test -s "$SMOKE_APK"

SYSTEM_WARNING_LOG="$GITHUB_WORKSPACE/release-output/emulator-system-warnings.txt"
WINDOW_XML="$GITHUB_WORKSPACE/release-output/emulator-window.xml"
HOME_XML="$GITHUB_WORKSPACE/release-output/spotty-home-window.xml"
PLAYER_XML="$GITHUB_WORKSPACE/release-output/spotty-player-window.xml"

# A no-window Android emulator can occasionally show "Pixel Launcher isn't responding"
# over a healthy app. Dismiss that system dialog by locating its Wait button through UIAutomator.
# Spotty's own crash/ANR checks and the real playback-service test remain hard release gates.
dismiss_launcher_anr_dialog() {
  local attempt=1
  while [ "$attempt" -le 4 ]; do
    timeout 15s adb shell uiautomator dump /sdcard/spotty-window.xml >/dev/null 2>&1 || {
      attempt=$((attempt + 1)); sleep 1; continue;
    }
    timeout 15s adb pull /sdcard/spotty-window.xml "$WINDOW_XML" >/dev/null 2>&1 || {
      attempt=$((attempt + 1)); sleep 1; continue;
    }
    local action
    action="$(python3 - "$WINDOW_XML" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
    nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
except Exception:
    print("unknown")
    raise SystemExit(0)
values = [(n.attrib.get("text", "") + " " + n.attrib.get("content-desc", "")).strip().lower() for n in nodes]
dialog = any("pixel launcher isn't responding" in v or "launcher isn't responding" in v for v in values)
if not dialog:
    print("none")
    raise SystemExit(0)
for node, value in zip(nodes, values):
    if value.strip() == "wait" or value.endswith(" wait"):
        bounds = [int(n) for n in re.findall(r"\d+", node.attrib.get("bounds", ""))]
        if len(bounds) == 4:
            print(str((bounds[0] + bounds[2]) // 2) + "," + str((bounds[1] + bounds[3]) // 2))
            raise SystemExit(0)
print("dialog-without-wait-button")
PY
)"
    if [ "$action" = "none" ]; then return 0; fi
    if [[ "$action" =~ ^[0-9]+,[0-9]+$ ]]; then
      local x y
      x="$(printf '%s' "$action" | cut -d, -f1)"
      y="$(printf '%s' "$action" | cut -d, -f2)"
      echo "[smoke] Dismissing transient Pixel Launcher ANR dialog via Wait (attempt $attempt)"
      timeout 10s adb shell input tap "$x" "$y" || true
      sleep 2
    else
      echo "[smoke] Launcher dialog state: $action (attempt $attempt); sending Back and retrying"
      timeout 10s adb shell input keyevent 4 || true
      sleep 2
    fi
    attempt=$((attempt + 1))
  done
  timeout 15s adb shell uiautomator dump /sdcard/spotty-window-final.xml >/dev/null 2>&1 || true
  timeout 15s adb pull /sdcard/spotty-window-final.xml "$WINDOW_XML" >/dev/null 2>&1 || true
  if grep -Fqi "Pixel Launcher isn't responding" "$WINDOW_XML" 2>/dev/null; then
    echo "Pixel Launcher ANR dialog remained visible after dismissal attempts."
    return 1
  fi
  return 0
}

echo "[smoke] Verifying emulator boot without foregrounding the home launcher"
BOOTED="$(timeout 20s adb shell getprop sys.boot_completed | tr -d '\r')"
if [ "$BOOTED" != "1" ]; then
  echo "Android emulator has not completed boot."
  exit 1
fi

echo "[smoke] Installing and launching the signed release APK"
timeout 90s adb install "$APK"
timeout 20s adb logcat -c
timeout 20s adb shell am force-stop com.spotty.music
timeout 30s adb shell am start -W -n com.spotty.music/.MainActivity
sleep 10

echo "[smoke] Checking release APK process, crash logs, and ANRs"
PID="$(timeout 20s adb shell pidof com.spotty.music | tr -d '\r' || true)"
timeout 30s adb logcat -d -b main -b system -b events > "$LOGCAT"
grep -E "FATAL EXCEPTION|ANR in com\\.spotty\\.music|am_anr.*com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$LOGCAT" > "$ERROR_LOG" || true
grep -Ei "ANR in com\\.google\\.android\\.apps\\.nexuslauncher|am_anr.*com\\.google\\.android\\.apps\\.nexuslauncher" "$LOGCAT" > "$SYSTEM_WARNING_LOG" || true
if [ -s "$SYSTEM_WARNING_LOG" ]; then
  echo "[smoke] Warning: Pixel Launcher ANR logged by emulator; checking and dismissing any visible system dialog."
fi

if [ -z "$PID" ]; then
  echo "Spotty release APK did not remain running after launch."
  cat "$ERROR_LOG"
  exit 1
fi

if grep -Eiq "FATAL EXCEPTION|ANR in com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$LOGCAT"; then
  echo "Spotty release APK crashed or became unresponsive in the emulator."
  cat "$ERROR_LOG"
  exit 1
fi

if ! dismiss_launcher_anr_dialog; then
  echo "Unable to dismiss emulator-only launcher dialog; refusing to accept a contaminated screenshot."
  exit 1
fi
echo "[smoke] Verifying redesigned discovery home is rendered"
for home_attempt in 1 2 3; do
  timeout 20s adb shell uiautomator dump /sdcard/spotty-home-window.xml >/dev/null 2>&1 || true
  timeout 30s adb pull /sdcard/spotty-home-window.xml "$HOME_XML" >/dev/null 2>&1 || true
  if grep -Fq "FRESH FINDS" "$HOME_XML" && grep -Fq "Track, artist, mood, or a lyric" "$HOME_XML"; then
    break
  fi
  echo "[smoke] Home accessibility tree was obscured; recovering system dialog (attempt $home_attempt)."
  dismiss_launcher_anr_dialog || true
  sleep 1
done
grep -Fq "FRESH FINDS" "$HOME_XML"
grep -Fq "Track, artist, mood, or a lyric" "$HOME_XML"
if grep -Fq "YouTube • Morphe" "$HOME_XML"; then
  echo "Legacy provider-selector UI was unexpectedly rendered."
  exit 1
fi
echo "[smoke] Discovery heading, unified search field, and absence of the old provider row verified."
grep -Fq "ALL SOURCES" "$HOME_XML"
grep -Fq "AUDIUS" "$HOME_XML"
grep -Fq "OPEN ARCHIVE" "$HOME_XML"
grep -Fq "LOCAL / FLAC" "$HOME_XML"
grep -Fq "DEEZER PREVIEWS" "$HOME_XML"
grep -Fq "SOUNDCLOUD" "$HOME_XML"
grep -Fq "SPOTIFY / SPOTUI" "$HOME_XML"
grep -Fq "SOULSEEK" "$HOME_XML"
echo "[smoke] Unified-source and explicit provider/handoff rail labels verified."

# Submit a real query and verify the result heading changes; then exercise a specific source filter.
tap_ui_contains "Track, artist, mood, or a lyric"
timeout 15s adb shell input text "Radiohead"
timeout 15s adb shell input keyevent 66
sleep 2
timeout 20s adb shell uiautomator dump /sdcard/spotty-unified-search.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-unified-search.xml "$GITHUB_WORKSPACE/release-output/spotty-unified-search.xml" >/dev/null
grep -Fq "SEARCH RESULTS" "$GITHUB_WORKSPACE/release-output/spotty-unified-search.xml"
tap_ui_contains "AUDIUS"
sleep 1
timeout 20s adb shell uiautomator dump /sdcard/spotty-source-filter.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-source-filter.xml "$GITHUB_WORKSPACE/release-output/spotty-source-filter.xml" >/dev/null
grep -Fq "AUDIUS SEARCH" "$GITHUB_WORKSPACE/release-output/spotty-source-filter.xml"
tap_ui_contains "SPOTIFY / SPOTUI"
sleep 1
timeout 20s adb shell uiautomator dump /sdcard/spotty-spotui-handoff.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-spotui-handoff.xml "$GITHUB_WORKSPACE/release-output/spotty-spotui-handoff.xml" >/dev/null
grep -Fq "SPOTIFY • SPOTUI HANDOFF" "$GITHUB_WORKSPACE/release-output/spotty-spotui-handoff.xml"
grep -Fq "Open Spotui player" "$GITHUB_WORKSPACE/release-output/spotty-spotui-handoff.xml"
tap_ui_contains "ALL SOURCES"
sleep 1
echo "[smoke] Search submit, source filtering, and the explicit Spotui handoff view work."


# Theme switching used to recreate the Activity while a parent dialog was still attached.
# Exercise several visually distinct themes through the actual Settings UI, then restore default.
tap_ui_contains() {
  local target="$1" xml_path="$GITHUB_WORKSPACE/release-output/ui-tap-window.xml"
  local attempt=1 point="missing"
  while [ "$attempt" -le 4 ]; do
    timeout 20s adb shell uiautomator dump /sdcard/spotty-ui-tap.xml >/dev/null 2>&1 || true
    timeout 20s adb pull /sdcard/spotty-ui-tap.xml "$xml_path" >/dev/null 2>&1 || true
    point="$(python3 - "$xml_path" "$target" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
except Exception: nodes = []
target = sys.argv[2].casefold()
for node in nodes:
    value = " ".join(node.attrib.get(k, "") for k in ("text", "hint", "content-desc"))
    if target in value.casefold():
        bounds = [int(n) for n in re.findall(r"\d+", node.attrib.get("bounds", ""))]
        if len(bounds) == 4:
            print(f"{(bounds[0]+bounds[2])//2},{(bounds[1]+bounds[3])//2}")
            raise SystemExit(0)
print("missing")
PY
    )"
    if [[ "$point" =~ ^[0-9]+,[0-9]+$ ]]; then
      local x y
      x="${point%,*}"; y="${point#*,}"
      timeout 15s adb shell input tap "$x" "$y"
      return 0
    fi
    sleep 1
    attempt=$((attempt + 1))
  done
  echo "Could not find tappable UI label: $target"
  cat "$xml_path" 2>/dev/null || true
  return 1
}
for theme in "Y2K Glass" "Midwest Emo" "Retro CRT" "Synthwave" "Frutiger Aero"; do
  echo "[smoke] Switching theme through Settings: $theme"
  tap_ui_contains "SETTINGS"
  sleep 1
  tap_ui_contains "Appearance & themes"
  sleep 1
  tap_ui_contains "$theme"
  sleep 2
  timeout 20s adb shell pidof com.spotty.music | tr -d '\r' | grep -Eq '[0-9]+'
  timeout 20s adb shell uiautomator dump /sdcard/spotty-theme-home.xml >/dev/null 2>&1
  timeout 20s adb pull /sdcard/spotty-theme-home.xml "$GITHUB_WORKSPACE/release-output/spotty-theme-home.xml" >/dev/null
  grep -Fq "FRESH FINDS" "$GITHUB_WORKSPACE/release-output/spotty-theme-home.xml"
done
echo "[smoke] Five themes including the default were selected; Activity remained alive after each change."

# The bottom mini-player must always lead to a usable player, even before a track is chosen.
tap_ui_contains "Tap to open the player"
sleep 1
timeout 20s adb shell uiautomator dump /sdcard/spotty-player-entry.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-player-entry.xml "$GITHUB_WORKSPACE/release-output/spotty-player-entry.xml" >/dev/null
if ! grep -Eq "NOW PLAYING|Your next listen starts here" "$GITHUB_WORKSPACE/release-output/spotty-player-entry.xml"; then
  echo "Mini-player tap failed to open a real player surface or its empty state."
  cat "$GITHUB_WORKSPACE/release-output/spotty-player-entry.xml"
  exit 1
fi
timeout 15s adb shell input keyevent 4
echo "[smoke] Mini-player entry opens a real player screen or the dedicated empty-player state."

echo "[smoke] Capturing signed release APK screenshot after system-dialog recovery"
timeout 20s adb shell screencap -p /sdcard/spotty-smoke-test.png
timeout 30s adb pull /sdcard/spotty-smoke-test.png "$SCREENSHOT"
timeout 20s adb shell pm list packages | grep -Fx "package:com.spotty.music"
test -s "$SCREENSHOT"
echo "[smoke] Signed release APK installed/launched and screenshot captured."

# Exercise the actual MediaPlayer + foreground-service path with a generated local WAV,
# not a UI/mock object or an external provider that may be offline during CI.
echo "[smoke] Generating a short local WAV fixture for end-to-end background playback"
python3 - "$WAV_FIXTURE" <<'PY'
import array
import math
import sys
import wave

path = sys.argv[1]
rate = 44100
seconds = 180
frames = rate * seconds
samples = array.array("h", (
    int(1800 * math.sin(2.0 * math.pi * 440.0 * i / rate))
    for i in range(frames)
))
with wave.open(path, "wb") as stream:
    stream.setnchannels(1)
    stream.setsampwidth(2)
    stream.setframerate(rate)
    stream.writeframes(samples.tobytes())
print(f"Generated deterministic test audio: {frames} frames at {rate} Hz")
PY

echo "[smoke] Switching to debug-only test APK to exercise real local-file playback"
timeout 30s adb uninstall com.spotty.music
timeout 90s adb install "$SMOKE_APK"
timeout 30s adb push "$WAV_FIXTURE" /data/local/tmp/spotty-ci-smoke.wav
timeout 20s adb shell run-as com.spotty.music mkdir -p files
timeout 20s adb shell run-as com.spotty.music cp /data/local/tmp/spotty-ci-smoke.wav files/spotty-ci-smoke.wav
timeout 20s adb logcat -c

echo "[smoke] Launching the debug-only internal WAV test hook"
timeout 30s adb shell am start -W -n com.spotty.music/.MainActivity --ez spotty_ci_smoke_playback true
sleep 3
if ! dismiss_launcher_anr_dialog; then
  echo "Could not clear emulator launcher dialog before background-playback assertions."
  exit 1
fi
echo "[smoke] Verifying the real in-app player sheet while the WAV is playing"
for player_attempt in 1 2 3; do
  timeout 20s adb shell uiautomator dump /sdcard/spotty-player-window.xml >/dev/null 2>&1 || true
  timeout 30s adb pull /sdcard/spotty-player-window.xml "$PLAYER_XML" >/dev/null 2>&1 || true
  if grep -Fq "NOW PLAYING" "$PLAYER_XML"; then break; fi
  echo "[smoke] Player accessibility tree was obscured; recovering system dialog (attempt $player_attempt)."
  dismiss_launcher_anr_dialog || true
  sleep 1
done
grep -Fq "NOW PLAYING" "$PLAYER_XML"
grep -Fq "Spotty playback test" "$PLAYER_XML"
grep -Fq "SOURCE" "$PLAYER_XML"
grep -Fq "ON THIS DEVICE" "$PLAYER_XML"
grep -Fq 'class="android.widget.SeekBar"' "$PLAYER_XML"
echo "[smoke] Player title, source footer, and seek control are visible."
timeout 20s adb logcat -d -s SpottyPlaybackService:I > "$SERVICE_LOG"
if ! grep -Fq "playback-state=playing" "$SERVICE_LOG"; then
  echo "The foreground playback service never reported that the real WAV stream started."
  cat "$SERVICE_LOG"
  timeout 30s adb logcat -d -b main -b system -b events > "$PLAYBACK_LOGCAT"
  exit 1
fi

echo "[smoke] Moving Spotty behind Settings while service playback is active"
timeout 30s adb shell am start -W -a android.settings.SETTINGS
sleep 3
if ! dismiss_launcher_anr_dialog; then
  echo "Could not clear emulator launcher dialog while Spotty audio should be running."
  exit 1
fi
timeout 30s adb shell dumpsys activity services com.spotty.music > "$SERVICE_DUMP"
if ! grep -Eiq 'isForeground=true|foregroundId=7401|foregroundNoti=Notification' "$SERVICE_DUMP"; then
  echo "Spotty playback service did not remain a foreground media service after the Activity was backgrounded."
  cat "$SERVICE_DUMP"
  exit 1
fi
PID="$(timeout 20s adb shell pidof com.spotty.music | tr -d '\r' || true)"
if [ -z "$PID" ]; then
  echo "Spotty process disappeared while its media service should have been playing."
  exit 1
fi
sleep 2
timeout 30s adb logcat -d -b main -b system -b events > "$PLAYBACK_LOGCAT"
if grep -Eiq "FATAL EXCEPTION|ANR in com\\.spotty\\.music|am_anr.*com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$PLAYBACK_LOGCAT"; then
  echo "Spotty crashed or became unresponsive during background playback."
  grep -Ei "FATAL EXCEPTION|ANR in com\\.spotty\\.music|am_anr.*com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$PLAYBACK_LOGCAT" > "$ERROR_LOG" || true
  cat "$ERROR_LOG"
  exit 1
fi

grep -Fq "playback-state=playing" "$SERVICE_LOG"
echo "[smoke] Checking Music stats screen and displayed metrics"
# Force a fresh Activity so the debug test intent is processed by onCreate.
timeout 20s adb shell am force-stop com.spotty.music
timeout 30s adb shell am start -W -f 0x10008000 -n com.spotty.music/.MainActivity --ez spotty_ci_smoke_stats true
sleep 2
timeout 20s adb shell uiautomator dump /sdcard/spotty-stats-window.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-stats-window.xml "$GITHUB_WORKSPACE/release-output/spotty-stats-window.xml" >/dev/null
grep -Eiq "Music stats" "$GITHUB_WORKSPACE/release-output/spotty-stats-window.xml"
grep -Eiq "Days played" "$GITHUB_WORKSPACE/release-output/spotty-stats-window.xml"
grep -Eiq "Minutes listened" "$GITHUB_WORKSPACE/release-output/spotty-stats-window.xml"
grep -Eiq "Songs played" "$GITHUB_WORKSPACE/release-output/spotty-stats-window.xml"
grep -Eiq "Taste overview" "$GITHUB_WORKSPACE/release-output/spotty-stats-window.xml"

echo "[smoke] Checking account connection screen labels"
# Each test entry point is handled only on creation; don't let Android reuse the previous screen.
timeout 20s adb shell am force-stop com.spotty.music
timeout 30s adb shell am start -W -f 0x10008000 -n com.spotty.music/.MainActivity --ez spotty_ci_smoke_connections true
sleep 2
timeout 20s adb shell uiautomator dump /sdcard/spotty-connections-window.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-connections-window.xml "$GITHUB_WORKSPACE/release-output/spotty-connections-window.xml" >/dev/null
grep -Eiq "Connect music accounts" "$GITHUB_WORKSPACE/release-output/spotty-connections-window.xml"
grep -Eiq "YouTube account" "$GITHUB_WORKSPACE/release-output/spotty-connections-window.xml"
grep -Eiq "Spotify sign-in" "$GITHUB_WORKSPACE/release-output/spotty-connections-window.xml"
grep -Eiq "fmbot / Last.fm setup" "$GITHUB_WORKSPACE/release-output/spotty-connections-window.xml"
echo "Spotty release install/launch, real local-WAV background playback, stats UI, and account entry-point smoke tests passed."
