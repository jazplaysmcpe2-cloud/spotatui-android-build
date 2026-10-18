#!/usr/bin/env python3
"""Install the native Stats screen into the pinned official mobile source."""
from pathlib import Path
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: patch_android_stats.py SOURCE_ROOT STATS_TEMPLATE")

root = Path(sys.argv[1])
template = Path(sys.argv[2])
if not root.is_dir():
    raise SystemExit(f"Spotatui source checkout not found: {root}")
if not template.is_file():
    raise SystemExit(f"Stats template not found: {template}")

stats_path = root / "mobile/app/src/app/(tabs)/stats.tsx"
if stats_path.exists():
    raise SystemExit("Native Stats screen already exists; refusing to overwrite it blindly.")
stats_path.parent.mkdir(parents=True, exist_ok=True)
stats_path.write_text(template.read_text())

tabs_path = root / "mobile/app/src/app/(tabs)/_layout.tsx"
tabs = tabs_path.read_text()
marker = "    </NativeTabs>"
if tabs.count(marker) != 1:
    raise SystemExit("Native tab insertion point changed upstream; refusing a blind patch.")
tab = '''      <NativeTabs.Trigger name="stats">
        <NativeTabs.Trigger.Label>Stats</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf="chart.bar.fill" md="bar_chart" />
      </NativeTabs.Trigger>
'''
tabs_path.write_text(tabs.replace(marker, tab + marker, 1))
print("Installed the native Stats screen and fourth tab.")
