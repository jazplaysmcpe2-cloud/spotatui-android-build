#!/usr/bin/env python3
"""Install guarded native equivalents for desktop pages into the mobile app."""
from pathlib import Path
import shutil
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: patch_android_desktop_parity_pages.py SOURCE_ROOT TEMPLATE_DIR")

root = Path(sys.argv[1])
templates = Path(sys.argv[2])
if not root.is_dir():
    raise SystemExit(f"Spotatui source checkout not found: {root}")
if not templates.is_dir():
    raise SystemExit(f"Android parity templates not found: {templates}")

screens = {
    "mobile-discover.tsx": "mobile/app/src/app/discover.tsx",
    "mobile-recently-played.tsx": "mobile/app/src/app/recently-played.tsx",
    "mobile-session.tsx": "mobile/app/src/app/session.tsx",
    "mobile-party.tsx": "mobile/app/src/app/party.tsx",
    "mobile-more.tsx": "mobile/app/src/app/(tabs)/more.tsx",
}
for template_name, source_path in screens.items():
    template = templates / template_name
    destination = root / source_path
    if not template.is_file():
        raise SystemExit(f"Required native page template is missing: {template}")
    if destination.exists():
        raise SystemExit(f"Native page already exists; refusing to overwrite blindly: {source_path}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(template, destination)

tabs_path = root / "mobile/app/src/app/(tabs)/_layout.tsx"
tabs = tabs_path.read_text()
stats_tab = '''      <NativeTabs.Trigger name="stats">
        <NativeTabs.Trigger.Label>Stats</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf="chart.bar.fill" md="bar_chart" />
      </NativeTabs.Trigger>
'''
if tabs.count(stats_tab) != 1:
    raise SystemExit("Stats tab missing or duplicated; refusing to insert the More tab.")
more_tab = '''      <NativeTabs.Trigger name="more">
        <NativeTabs.Trigger.Label>More</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf="ellipsis" md="more_horiz" />
      </NativeTabs.Trigger>
'''
tabs_path.write_text(tabs.replace(stats_tab, stats_tab + more_tab, 1))

layout_path = root / "mobile/app/src/app/_layout.tsx"
layout = layout_path.read_text()
settings_screen = '              <Stack.Screen name="settings" />'
if layout.count(settings_screen) != 1:
    raise SystemExit("Root stack settings screen marker changed; refusing a blind patch.")
extra_screens = '''
              <Stack.Screen name="discover" />
              <Stack.Screen name="recently-played" />
              <Stack.Screen name="session" />
              <Stack.Screen name="party" />'''
layout_path.write_text(layout.replace(settings_screen, settings_screen + extra_screens, 1))

print("Installed Discover, Recently Played, Session, Party, and More navigation screens.")
