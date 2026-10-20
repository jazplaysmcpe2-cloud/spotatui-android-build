#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
APK="$GITHUB_WORKSPACE/release-output/spotty-android-release.apk"
SMOKE_APK="$GITHUB_WORKSPACE/release-output/spotty-android-smoke.apk"
ERROR_LOG="$GITHUB_WORKSPACE/release-output/emulator-errors.txt"
LOGCAT="$GITHUB_WORKSPACE/release-output/emulator-logcat.txt"
SCREENSHOT="$GITHUB_WORKSPACE/release-output/emulator-smoke-test.png"
WAV_FIXTURE="$GITHUB_WORKSPACE/release-output/spotty-ci-smoke.wav"
SHORT_WAV_FIXTURE="$GITHUB_WORKSPACE/release-output/spotty-ci-short.wav"
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
grep -E "FATAL EXCEPTION|ANR in com\\.spotty\\.music|am_anr.*com\\.spotty\\.music|am_crash:.*com\\.spotty\\.music|am_proc_died:.*com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$LOGCAT" > "$ERROR_LOG" || true
grep -Ei "ANR in com\\.google\\.android\\.apps\\.nexuslauncher|am_anr.*com\\.google\\.android\\.apps\\.nexuslauncher" "$LOGCAT" > "$SYSTEM_WARNING_LOG" || true
if [ -s "$SYSTEM_WARNING_LOG" ]; then
  echo "[smoke] Warning: Pixel Launcher ANR logged by emulator; checking and dismissing any visible system dialog."
fi

if [ -z "$PID" ]; then
  echo "Spotty release APK did not remain running after launch."
  cat "$ERROR_LOG"
  exit 1
fi

if grep -Eiq "FATAL EXCEPTION|ANR in com\\.spotty\\.music|am_crash:.*com\\.spotty\\.music|am_proc_died:.*com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$LOGCAT"; then
  echo "Spotty release APK crashed or became unresponsive in the emulator."
  cat "$ERROR_LOG"
  exit 1
fi

if ! dismiss_launcher_anr_dialog; then
  echo "Unable to dismiss emulator-only launcher dialog; refusing to accept a contaminated screenshot."
  exit 1
fi

if [ -s "$SYSTEM_WARNING_LOG" ]; then
  # The headless emulator's Pixel Launcher can repeatedly raise its own ANR window
  # while Spotty is healthy. Stop only that launcher process, then refocus Spotty.
  # Spotty's own crash/ANR checks above and the full UI checks below remain strict.
  echo "[smoke] Recovering from emulator-only Pixel Launcher ANR and refocusing Spotty."
  timeout 15s adb shell am force-stop com.google.android.apps.nexuslauncher || true
  timeout 30s adb shell am start -W -n com.spotty.music/.MainActivity
  sleep 2
  PID="$(timeout 20s adb shell pidof com.spotty.music | tr -d '\r' || true)"
  if [ -z "$PID" ]; then
    echo "Spotty process disappeared while recovering the emulator launcher."
    exit 1
  fi
fi

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

tap_ui_exact() {
  local target="$1" xml_path="$GITHUB_WORKSPACE/release-output/ui-tap-exact-window.xml"
  local attempt=1 point="missing"
  while [ "$attempt" -le 4 ]; do
    timeout 20s adb shell uiautomator dump /sdcard/spotty-ui-tap-exact.xml >/dev/null 2>&1 || true
    timeout 20s adb pull /sdcard/spotty-ui-tap-exact.xml "$xml_path" >/dev/null 2>&1 || true
    point="$(python3 - "$xml_path" "$target" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
except Exception: nodes = []
target = sys.argv[2].strip().casefold()
for node in nodes:
    value = node.attrib.get("text", "").strip().casefold()
    if value == target:
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
  echo "Could not find exact UI label: $target"
  cat "$xml_path" 2>/dev/null || true
  return 1
}

rail_snapshot_contains() {
  local target="$1"
  local xml_path="$GITHUB_WORKSPACE/release-output/source-rail.xml"
  timeout 20s adb shell uiautomator dump /sdcard/spotty-source-rail.xml >/dev/null 2>&1 || return 1
  timeout 20s adb pull /sdcard/spotty-source-rail.xml "$xml_path" >/dev/null 2>&1 || return 1
  python3 - "$xml_path" "$target" <<'PY'
import sys, xml.etree.ElementTree as ET
try: nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
except Exception: raise SystemExit(1)
target = sys.argv[2].strip().casefold()
for node in nodes:
    if node.attrib.get("text", "").strip().casefold() == target:
        raise SystemExit(0)
raise SystemExit(1)
PY
}

scroll_source_rail() {
  local direction="$1" xml_path="$GITHUB_WORKSPACE/release-output/source-rail.xml"
  timeout 20s adb shell uiautomator dump /sdcard/spotty-source-rail.xml >/dev/null 2>&1 || true
  timeout 20s adb pull /sdcard/spotty-source-rail.xml "$xml_path" >/dev/null 2>&1 || true
  local y screen_width start_x end_x
  y="$(python3 - "$xml_path" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
except Exception: nodes = []
labels = {"AUDIUS", "OPEN ARCHIVE", "DEEZER PREVIEWS", "YOUTUBE", "SOUNDCLOUD",
          "SPOTIFY / SPOTUI", "SOULSEEK", "LOCAL / FLAC", "RADIO"}
for node in nodes:
    value = node.attrib.get("text", "").strip().upper()
    if value in labels:
        bounds = [int(n) for n in re.findall(r"\d+", node.attrib.get("bounds", ""))]
        if len(bounds) == 4:
            print((bounds[1] + bounds[3]) // 2)
            raise SystemExit(0)
print(520)
PY
  )"
  screen_width="$(timeout 10s adb shell wm size | sed -n 's/.*size: \([0-9]*\)x.*/\1/p' | tail -n 1)"
  [[ "$screen_width" =~ ^[0-9]+$ ]] || screen_width=1080
  if [[ "$direction" == "right" ]]; then
    start_x=$((screen_width * 82 / 100)); end_x=$((screen_width * 18 / 100))
  else
    start_x=$((screen_width * 18 / 100)); end_x=$((screen_width * 82 / 100))
  fi
  timeout 15s adb shell input swipe "$start_x" "$y" "$end_x" "$y" 300
  sleep 0.5
}

ensure_rail_label_visible() {
  local target="$1" direction attempt
  if rail_snapshot_contains "$target"; then return 0; fi
  # The provider rail is deliberately horizontally scrollable. Search each viewport
  # instead of incorrectly requiring off-screen labels in the initial XML snapshot.
  for direction in right left; do
    for attempt in 1 2 3 4 5 6 7 8; do
      scroll_source_rail "$direction"
      if rail_snapshot_contains "$target"; then
        echo "[smoke] Provider label visible after scrolling: $target"
        return 0
      fi
    done
  done
  echo "Could not expose source-rail label after scrolling: $target"
  cat "$GITHUB_WORKSPACE/release-output/source-rail.xml" 2>/dev/null || true
  return 1
}

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
if grep -Fq "ALL SOURCES" "$HOME_XML"; then
  echo "The obsolete ALL SOURCES pill is still rendered in the source rail."
  exit 1
fi
if grep -Fq "YouTube • Morphe" "$HOME_XML"; then
  echo "Legacy provider-selector UI was unexpectedly rendered."
  exit 1
fi
echo "[smoke] Discovery heading, unified search field, and absence of the old provider row verified."
# Only the first chip is required in the initial viewport. Verify the remaining labels
# after scrolling the real rail rather than assuming off-screen views are accessible.
ensure_rail_label_visible "AUDIUS"
echo "[smoke] Initial source rail verified without a redundant ALL SOURCES chip."
for rail_label in "AUDIUS" "OPEN ARCHIVE" "DEEZER PREVIEWS" "YOUTUBE" "SOUNDCLOUD" "SPOTIFY / SPOTUI" "SOULSEEK" "LOCAL / FLAC" "RADIO"; do
  ensure_rail_label_visible "$rail_label"
done

# Verify the separate Spotui handoff immediately after the source rail has been inspected.
# Keep this independent from search/filter interactions, which change result-list layout.
ensure_rail_label_visible "SPOTIFY / SPOTUI"
tap_ui_contains "SPOTIFY / SPOTUI"
sleep 1
timeout 20s adb shell uiautomator dump /sdcard/spotty-spotui-handoff.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-spotui-handoff.xml "$GITHUB_WORKSPACE/release-output/spotty-spotui-handoff.xml" >/dev/null
grep -Fq "SPOTIFY • SPOTUI HANDOFF" "$GITHUB_WORKSPACE/release-output/spotty-spotui-handoff.xml"
grep -Fq "Open Spotui player" "$GITHUB_WORKSPACE/release-output/spotty-spotui-handoff.xml"
tap_ui_contains "DISCOVER"
sleep 1

# Submit a real query and verify the result heading changes; then exercise a specific source filter.
tap_ui_contains "Track, artist, mood, or a lyric"
timeout 15s adb shell input text "Radiohead"
timeout 15s adb shell input keyevent 66
sleep 2
timeout 20s adb shell uiautomator dump /sdcard/spotty-unified-search.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-unified-search.xml "$GITHUB_WORKSPACE/release-output/spotty-unified-search.xml" >/dev/null
grep -Fq "SEARCH RESULTS" "$GITHUB_WORKSPACE/release-output/spotty-unified-search.xml"
ensure_rail_label_visible "AUDIUS"
tap_ui_exact "AUDIUS"
sleep 1
timeout 20s adb shell uiautomator dump /sdcard/spotty-source-filter.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-source-filter.xml "$GITHUB_WORKSPACE/release-output/spotty-source-filter.xml" >/dev/null
grep -Fq "AUDIUS SEARCH" "$GITHUB_WORKSPACE/release-output/spotty-source-filter.xml"
echo "[smoke] Search submit, source filtering, and the explicit Spotui handoff view work."


# Theme switching used to recreate the Activity while a parent dialog was still attached.
# Exercise several visually distinct themes through the actual Settings UI, then restore default.

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
python3 - "$SHORT_WAV_FIXTURE" <<'PY'
import array
import math
import sys
import wave

path = sys.argv[1]
rate = 44100
seconds = 3
frames = rate * seconds
samples = array.array("h", (
    int(1800 * math.sin(2.0 * math.pi * 660.0 * i / rate))
    for i in range(frames)
))
with wave.open(path, "wb") as stream:
    stream.setnchannels(1)
    stream.setsampwidth(2)
    stream.setframerate(rate)
    stream.writeframes(samples.tobytes())
print(f"Generated repeat-test audio: {frames} frames at {rate} Hz")
PY

echo "[smoke] Switching to debug-only test APK to exercise real local-file playback"
timeout 30s adb uninstall com.spotty.music
timeout 90s adb install "$SMOKE_APK"
timeout 30s adb push "$WAV_FIXTURE" /data/local/tmp/spotty-ci-smoke.wav
timeout 30s adb push "$SHORT_WAV_FIXTURE" /data/local/tmp/spotty-ci-short.wav
timeout 20s adb shell run-as com.spotty.music mkdir -p files
timeout 20s adb shell run-as com.spotty.music cp /data/local/tmp/spotty-ci-smoke.wav files/spotty-ci-smoke.wav
timeout 20s adb shell run-as com.spotty.music cp /data/local/tmp/spotty-ci-short.wav files/spotty-ci-short-two.wav
timeout 20s adb shell run-as com.spotty.music cp /data/local/tmp/spotty-ci-short.wav files/spotty-ci-short-three.wav
timeout 20s adb shell run-as com.spotty.music cp /data/local/tmp/spotty-ci-short.wav files/spotty-ci-short-smart.wav
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
grep -Fq "SHUFFLE" "$PLAYER_XML"
grep -Fq "SMART SHUFFLE" "$PLAYER_XML"
grep -Fq "REPEAT OFF" "$PLAYER_XML"
grep -Fq "ADD TO PLAYLIST" "$PLAYER_XML"
echo "[smoke] Player title, seek control, shuffle, smart shuffle, repeat modes, and playlist action are visible."

# Verify pause/resume through the real media service rather than only toggling a label.
timeout 20s adb logcat -c
tap_ui_exact "Ⅱ"
sleep 1
timeout 20s adb logcat -d -s SpottyPlaybackService:I > "$GITHUB_WORKSPACE/release-output/player-pause-log.txt"
grep -Fq "playback-state=paused" "$GITHUB_WORKSPACE/release-output/player-pause-log.txt"
timeout 20s adb logcat -c
tap_ui_exact "▶"
sleep 1
timeout 20s adb logcat -d -s SpottyPlaybackService:I > "$GITHUB_WORKSPACE/release-output/player-resume-log.txt"
grep -Fq "playback-state=playing" "$GITHUB_WORKSPACE/release-output/player-resume-log.txt"
echo "[smoke] Pause and resume changed the real media-service state."

# Drag the actual SeekBar and verify the decoded audio position jumps forward.
timeout 20s adb logcat -c
sleep 2
timeout 20s adb logcat -d -s SpottyPlaybackService:I > "$GITHUB_WORKSPACE/release-output/player-seek-before.log"
BASE_POSITION="$(awk -F'position-ms=' '/playback-heartbeat=playing position-ms=/ { value=$2; sub(/[^0-9].*/, "", value); if (value ~ /^[0-9]+$/) latest=value } END { if (latest != "") print latest }' "$GITHUB_WORKSPACE/release-output/player-seek-before.log")"
[[ "$BASE_POSITION" =~ ^[0-9]+$ ]]
timeout 20s adb shell uiautomator dump /sdcard/spotty-seek-window.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-seek-window.xml "$GITHUB_WORKSPACE/release-output/spotty-seek-window.xml" >/dev/null
IFS=',' read -r SEEK_X1 SEEK_Y SEEK_X2 <<< "$(python3 - "$GITHUB_WORKSPACE/release-output/spotty-seek-window.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
bars = []
for node in root.iter("node"):
    if node.attrib.get("class") == "android.widget.SeekBar":
        bounds = [int(n) for n in re.findall(r"\d+", node.attrib.get("bounds", ""))]
        if len(bounds) == 4 and bounds[2] - bounds[0] > 100:
            bars.append(bounds)
if not bars:
    raise SystemExit("No visible player SeekBar was found.")
left, top, right, bottom = max(bars, key=lambda b: b[2] - b[0])
y = (top + bottom) // 2
print(f"{left + (right-left)//10},{y},{left + (right-left)*65//100}")
PY
)"
timeout 15s adb shell input swipe "$SEEK_X1" "$SEEK_Y" "$SEEK_X2" "$SEEK_Y" 500
sleep 2
timeout 20s adb logcat -d -s SpottyPlaybackService:I > "$GITHUB_WORKSPACE/release-output/player-seek-after.log"
SEEK_POSITION="$(awk -F'position-ms=' '/playback-heartbeat=playing position-ms=/ { value=$2; sub(/[^0-9].*/, "", value); if (value ~ /^[0-9]+$/) latest=value } END { if (latest != "") print latest }' "$GITHUB_WORKSPACE/release-output/player-seek-after.log")"
if [[ ! "$SEEK_POSITION" =~ ^[0-9]+$ ]] || (( SEEK_POSITION - BASE_POSITION < 60000 )); then
  echo "Seek did not move the actual playback position forward by at least 60 seconds (before=$BASE_POSITION after=$SEEK_POSITION)."
  cat "$GITHUB_WORKSPACE/release-output/player-seek-after.log"
  exit 1
fi
echo "[smoke] Seeking moved real playback forward from \${BASE_POSITION}ms to \${SEEK_POSITION}ms."

# Exercise mode state transitions, not just the presence of labels.
tap_ui_contains "SHUFFLE"
sleep 0.5
timeout 20s adb shell uiautomator dump /sdcard/spotty-mode-shuffle-on.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-mode-shuffle-on.xml "$GITHUB_WORKSPACE/release-output/spotty-mode-shuffle-on.xml" >/dev/null
grep -Fq "✓ SHUFFLE" "$GITHUB_WORKSPACE/release-output/spotty-mode-shuffle-on.xml"
tap_ui_contains "✓ SHUFFLE"
timeout 20s adb shell uiautomator dump /sdcard/spotty-mode-shuffle-off.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-mode-shuffle-off.xml "$GITHUB_WORKSPACE/release-output/spotty-mode-shuffle-off.xml" >/dev/null
grep -Fq 'text="SHUFFLE"' "$GITHUB_WORKSPACE/release-output/spotty-mode-shuffle-off.xml"
tap_ui_contains "SMART SHUFFLE"
timeout 20s adb shell uiautomator dump /sdcard/spotty-mode-smart-on.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-mode-smart-on.xml "$GITHUB_WORKSPACE/release-output/spotty-mode-smart-on.xml" >/dev/null
grep -Fq "✦ SMART SHUFFLE" "$GITHUB_WORKSPACE/release-output/spotty-mode-smart-on.xml"
# Smart Shuffle must add related playable suggestions to the queue without duplicate URLs.
tap_ui_contains "Queue"
sleep 0.5
timeout 20s adb shell uiautomator dump /sdcard/spotty-smart-queue.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-smart-queue.xml "$GITHUB_WORKSPACE/release-output/spotty-smart-queue.xml" >/dev/null
python3 - "$GITHUB_WORKSPACE/release-output/spotty-smart-queue.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
texts = [node.attrib.get("text", "") for node in root.iter("node")]
assert "Spotty smart suggestion" in texts, "Smart Shuffle did not append the related playable fixture."
assert "Spotty smart suggestion duplicate" not in texts, "Smart Shuffle appended a duplicate URL."
assert "Spotty queue second" in texts and "Spotty queue third" in texts, "Smart Shuffle discarded existing queued tracks."
PY
timeout 15s adb shell input keyevent 4
tap_ui_contains "✦ SMART SHUFFLE"
timeout 20s adb shell uiautomator dump /sdcard/spotty-mode-smart-off.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-mode-smart-off.xml "$GITHUB_WORKSPACE/release-output/spotty-mode-smart-off.xml" >/dev/null
grep -Fq 'text="SMART SHUFFLE"' "$GITHUB_WORKSPACE/release-output/spotty-mode-smart-off.xml"
tap_ui_contains "REPEAT OFF"
tap_ui_contains "REPEAT PLAYLIST"
timeout 20s adb shell uiautomator dump /sdcard/spotty-mode-repeat-track.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-mode-repeat-track.xml "$GITHUB_WORKSPACE/release-output/spotty-mode-repeat-track.xml" >/dev/null
grep -Fq "REPEAT TRACK" "$GITHUB_WORKSPACE/release-output/spotty-mode-repeat-track.xml"
tap_ui_contains "REPEAT TRACK"
timeout 20s adb shell uiautomator dump /sdcard/spotty-mode-repeat-off.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-mode-repeat-off.xml "$GITHUB_WORKSPACE/release-output/spotty-mode-repeat-off.xml" >/dev/null
grep -Fq "REPEAT OFF" "$GITHUB_WORKSPACE/release-output/spotty-mode-repeat-off.xml"
echo "[smoke] Shuffle, smart-shuffle and repeat controls changed to the expected persisted modes."

# Check the actual library toggle and named-playlist create/persist paths.
tap_ui_contains "ADD TO YOUR LIBRARY"
timeout 20s adb shell uiautomator dump /sdcard/spotty-favorite-on.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-favorite-on.xml "$GITHUB_WORKSPACE/release-output/spotty-favorite-on.xml" >/dev/null
grep -Fq "SAVED TO YOUR LIBRARY" "$GITHUB_WORKSPACE/release-output/spotty-favorite-on.xml"
tap_ui_contains "ADD TO PLAYLIST"
sleep 1
timeout 20s adb shell uiautomator dump /sdcard/spotty-playlist-create.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-playlist-create.xml "$GITHUB_WORKSPACE/release-output/spotty-playlist-create.xml" >/dev/null
grep -Fq "Name your playlist" "$GITHUB_WORKSPACE/release-output/spotty-playlist-create.xml"
tap_ui_contains "Create"
sleep 1
timeout 20s adb shell run-as com.spotty.music cat shared_prefs/spotty_preferences.xml > "$GITHUB_WORKSPACE/release-output/spotty-prefs-after-playlist.xml"
grep -Fq "user_playlists_json" "$GITHUB_WORKSPACE/release-output/spotty-prefs-after-playlist.xml"
grep -Fq "My playlist" "$GITHUB_WORKSPACE/release-output/spotty-prefs-after-playlist.xml"
grep -Fq "favorite_tracks_json" "$GITHUB_WORKSPACE/release-output/spotty-prefs-after-playlist.xml"
echo "[smoke] Favorite persistence and named playlist creation were written to app preferences."

tap_ui_contains "Queue"
sleep 1
timeout 20s adb shell uiautomator dump /sdcard/spotty-queue-window.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-queue-window.xml "$GITHUB_WORKSPACE/release-output/spotty-queue-window.xml" >/dev/null
QUEUE_XML="$GITHUB_WORKSPACE/release-output/spotty-queue-window.xml"
grep -Fq "Playing queue" "$QUEUE_XML"
grep -Fq "Spotty playback test" "$QUEUE_XML"
grep -Fq "Spotty queue second" "$QUEUE_XML"
grep -Fq "Spotty queue third" "$QUEUE_XML"
# Move the playing row down and confirm the active queue slot moves with it.
tap_ui_contains "Move down"
sleep 0.5
timeout 20s adb shell uiautomator dump /sdcard/spotty-queue-reordered.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-queue-reordered.xml "$GITHUB_WORKSPACE/release-output/spotty-queue-reordered.xml" >/dev/null
python3 - "$GITHUB_WORKSPACE/release-output/spotty-queue-reordered.xml" <<'PY'
import sys
value = open(sys.argv[1], encoding="utf-8").read()
current = value.index("Spotty playback test")
other_rows = [pos for title in ("Spotty queue second", "Spotty queue third") if (pos := value.find(title)) >= 0]
assert other_rows and min(other_rows) < current, "Move down did not move the playing row below another queued row."
assert "Spotty queue second" in value and "Spotty queue third" in value, "Move down unexpectedly dropped another queue item."
PY
# Clear upcoming then remove the non-playing row; the current track must stay queued.
tap_ui_contains "CLEAR UPCOMING"
sleep 0.5
tap_ui_contains "Remove from queue"
sleep 0.5
timeout 20s adb shell uiautomator dump /sdcard/spotty-queue-final.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-queue-final.xml "$GITHUB_WORKSPACE/release-output/spotty-queue-final.xml" >/dev/null
grep -Fq "Spotty playback test" "$GITHUB_WORKSPACE/release-output/spotty-queue-final.xml"
if grep -Fq "Spotty queue second" "$GITHUB_WORKSPACE/release-output/spotty-queue-final.xml" || grep -Fq "Spotty queue third" "$GITHUB_WORKSPACE/release-output/spotty-queue-final.xml"; then
  echo "Queue clear/remove did not preserve only the expected playing track."
  cat "$GITHUB_WORKSPACE/release-output/spotty-queue-final.xml"
  exit 1
fi
timeout 15s adb shell input keyevent 4
echo "[smoke] Queue ordering, clear-upcoming, and removal passed without removing the active track."
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
echo "[smoke] Locking the emulator display while Spotty should keep playing in its foreground service"
timeout 15s adb shell input keyevent 26
sleep 12
timeout 15s adb shell input keyevent 26
sleep 22
timeout 30s adb logcat -d -s SpottyPlaybackService:I > "$SERVICE_LOG"
HEARTBEATS="$(grep -Fc "playback-heartbeat=playing position-ms=" "$SERVICE_LOG" || true)"
if [[ "$HEARTBEATS" -lt 2 ]]; then
  echo "Background playback did not produce two real-player heartbeats across the screen-off interval."
  cat "$SERVICE_LOG"
  exit 1
fi
POSITIONS="$(awk -F'position-ms=' '/playback-heartbeat=playing position-ms=/ { value=$2; sub(/[^0-9].*/, "", value); if (value ~ /^[0-9]+$/) print value }' "$SERVICE_LOG")"
FIRST_POSITION="$(printf '%s\n' "$POSITIONS" | head -n 1)"
LAST_POSITION="$(printf '%s\n' "$POSITIONS" | tail -n 1)"
if [[ ! "$FIRST_POSITION" =~ ^[0-9]+$ || ! "$LAST_POSITION" =~ ^[0-9]+$ ]] || (( LAST_POSITION - FIRST_POSITION < 15000 )); then
  echo "Audio position did not advance by at least 15 seconds during the background screen-off soak."
  printf 'Recorded audio positions:\n%s\n' "$POSITIONS"
  cat "$SERVICE_LOG"
  exit 1
fi
if grep -Fq "playback-state=error" "$SERVICE_LOG"; then
  echo "Playback entered an error state during the screen-off background soak."
  cat "$SERVICE_LOG"
  exit 1
fi
timeout 30s adb shell dumpsys activity services com.spotty.music > "$SERVICE_DUMP"
if ! grep -Eiq 'isForeground=true|foregroundId=7401|foregroundNoti=Notification' "$SERVICE_DUMP"; then
  echo "The media service left foreground status during the screen-off background soak."
  cat "$SERVICE_DUMP"
  exit 1
fi
PID="$(timeout 20s adb shell pidof com.spotty.music | tr -d '\\r' || true)"
if [ -z "$PID" ]; then
  echo "Spotty process disappeared during screen-off background playback."
  exit 1
fi
echo "[smoke] Screen-off soak passed with $HEARTBEATS live playback heartbeats and foreground service intact."
timeout 30s adb logcat -d -b main -b system -b events > "$PLAYBACK_LOGCAT"

# Restart without the debug playback hook to verify the edited queue itself persists.
timeout 20s adb shell am force-stop com.spotty.music
timeout 20s adb logcat -c
timeout 30s adb shell am start -W -f 0x10008000 -n com.spotty.music/.MainActivity
sleep 2
tap_ui_contains "Queue"
sleep 0.5
timeout 20s adb shell uiautomator dump /sdcard/spotty-queue-restored.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-queue-restored.xml "$GITHUB_WORKSPACE/release-output/spotty-queue-restored.xml" >/dev/null
grep -Fq "Playing queue" "$GITHUB_WORKSPACE/release-output/spotty-queue-restored.xml"
grep -Fq "Spotty playback test" "$GITHUB_WORKSPACE/release-output/spotty-queue-restored.xml"
if grep -Fq "Spotty queue second" "$GITHUB_WORKSPACE/release-output/spotty-queue-restored.xml" || grep -Fq "Spotty queue third" "$GITHUB_WORKSPACE/release-output/spotty-queue-restored.xml"; then
  echo "Edited queue did not restore the saved one-track state after app relaunch."
  cat "$GITHUB_WORKSPACE/release-output/spotty-queue-restored.xml"
  exit 1
fi
echo "[smoke] Edited queue restored from device preferences after a process restart."

# A fresh debug-only run verifies persisted library/playlist state and repeat behaviour
# against real short local WAVs; no public streaming endpoint is involved.
timeout 20s adb shell am force-stop com.spotty.music
timeout 20s adb logcat -c
timeout 30s adb shell am start -W -f 0x10008000 -n com.spotty.music/.MainActivity --ez spotty_ci_smoke_playback true
sleep 3
timeout 20s adb shell uiautomator dump /sdcard/spotty-persistence-window.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-persistence-window.xml "$GITHUB_WORKSPACE/release-output/spotty-persistence-window.xml" >/dev/null
timeout 20s adb logcat -d -s SpottySmoke:I > "$GITHUB_WORKSPACE/release-output/spotty-smoke-diagnostics.txt"
grep -Fq "test-hook restored-favorites=1 saved-current=true" "$GITHUB_WORKSPACE/release-output/spotty-smoke-diagnostics.txt"
grep -Fq "SAVED TO YOUR LIBRARY" "$GITHUB_WORKSPACE/release-output/spotty-persistence-window.xml"
tap_ui_contains "ADD TO PLAYLIST"
sleep 0.5
timeout 20s adb shell uiautomator dump /sdcard/spotty-playlist-persisted.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-playlist-persisted.xml "$GITHUB_WORKSPACE/release-output/spotty-playlist-persisted.xml" >/dev/null
grep -Fq "My playlist" "$GITHUB_WORKSPACE/release-output/spotty-playlist-persisted.xml"
grep -Fq "1 tracks" "$GITHUB_WORKSPACE/release-output/spotty-playlist-persisted.xml"
timeout 15s adb shell input keyevent 4

# Repeat track must really restart the selected short file after MediaPlayer completion.
tap_ui_contains "REPEAT OFF"
tap_ui_contains "REPEAT PLAYLIST"
timeout 20s adb shell uiautomator dump /sdcard/spotty-repeat-track-window.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-repeat-track-window.xml "$GITHUB_WORKSPACE/release-output/spotty-repeat-track-window.xml" >/dev/null
grep -Fq "REPEAT TRACK" "$GITHUB_WORKSPACE/release-output/spotty-repeat-track-window.xml"
tap_ui_contains "⏭"
sleep 8
timeout 30s adb logcat -d -s SpottyPlaybackService:I > "$SERVICE_LOG"
grep -Fq "track-completed title=Spotty queue second repeat-mode=track" "$SERVICE_LOG"
STARTS_OF_SECOND="$(grep -Fc 'playback-started title=Spotty queue second' "$SERVICE_LOG" || true)"
if [[ "$STARTS_OF_SECOND" -lt 2 ]]; then
  echo "Repeat-track did not start the selected track at least twice (starts=$STARTS_OF_SECOND)."
  cat "$SERVICE_LOG"
  exit 1
fi
echo "[smoke] Repeat-track restarted the real selected audio after completion."

# Repeat playlist must wrap from the final short track back to the first long track.
timeout 20s adb shell am force-stop com.spotty.music
timeout 20s adb logcat -c
timeout 30s adb shell am start -W -f 0x10008000 -n com.spotty.music/.MainActivity --ez spotty_ci_smoke_playback true
sleep 3
tap_ui_contains "REPEAT TRACK"
tap_ui_contains "REPEAT OFF"
timeout 20s adb shell uiautomator dump /sdcard/spotty-repeat-playlist-window.xml >/dev/null 2>&1
timeout 20s adb pull /sdcard/spotty-repeat-playlist-window.xml "$GITHUB_WORKSPACE/release-output/spotty-repeat-playlist-window.xml" >/dev/null
grep -Fq "REPEAT PLAYLIST" "$GITHUB_WORKSPACE/release-output/spotty-repeat-playlist-window.xml"
tap_ui_contains "⏭"
sleep 0.5
tap_ui_contains "⏭"
sleep 8
timeout 30s adb logcat -d -s SpottyPlaybackService:I > "$SERVICE_LOG"
grep -Fq "track-completed title=Spotty queue third repeat-mode=playlist" "$SERVICE_LOG"
grep -Fq "playback-started title=Spotty playback test queue-index=0" "$SERVICE_LOG"
echo "[smoke] Repeat-playlist advanced through real queue items and wrapped to the first track."
timeout 20s adb shell am force-stop com.spotty.music
timeout 20s adb logcat -c
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
