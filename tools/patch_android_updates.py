#!/usr/bin/env python3
"""Add a safe, in-app checker for signed Android releases of this port."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
settings_path = root / "mobile/app/src/app/settings.tsx"
source = settings_path.read_text()

def replace_once(old: str, new: str, description: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{description}: expected exactly one match, found {count}; refusing a blind patch.")
    source = source.replace(old, new, 1)

replace_once(
    "import { useState } from 'react';",
    "import { useCallback, useEffect, useState } from 'react';",
    "Settings React hooks",
)
replace_once(
    "import { Modal, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';",
    "import { Linking, Modal, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';",
    "Settings native imports",
)
replace_once(
    "import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';",
    "import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';\nimport Constants from 'expo-constants';",
    "Expo native version import",
)
replace_once(
    "        {rows === null && <Text style={type.small}>Loading…</Text>}",
    "        <AndroidUpdatePanel />\n        {rows === null && <Text style={type.small}>Loading…</Text>}",
    "Settings update panel insertion",
)

anchor = "function SettingItem({ row, send }: { row: SettingRow; send: Send }) {"
if source.count(anchor) != 1:
    raise SystemExit("Settings component insertion point changed upstream; refusing a blind patch.")

component = """const ANDROID_RELEASES_API = 'https://api.github.com/repos/jazplaysmcpe2-cloud/spotatui-android-build/releases/latest';

type AndroidRelease = {
  name?: string | null;
  tag_name?: string;
  assets?: { name: string; browser_download_url: string }[];
};

/** Checks the Android port's own signed APK releases; upstream desktop releases are not Android APKs. */
function AndroidUpdatePanel() {
  const installedBuild = Number(Constants.nativeBuildVersion ?? '1') || 1;
  const [checking, setChecking] = useState(false);
  const [status, setStatus] = useState('Checking for updates…');
  const [release, setRelease] = useState<AndroidRelease | null>(null);

  const check = useCallback(async () => {
    try {
      const response = await fetch(ANDROID_RELEASES_API, {
        headers: { Accept: 'application/vnd.github+json' },
      });
      if (response.status === 404) {
        setRelease(null);
        setStatus('No signed Android release has been published yet.');
        return;
      }
      if (!response.ok) throw new Error('Update check failed (HTTP ' + response.status + ').');
      const candidate = (await response.json()) as AndroidRelease;
      const match = /^android-build-(\\d+)$/.exec(candidate.tag_name ?? '');
      const apk = candidate.assets?.find((asset) => asset.name === 'spotatui-android-release.apk');
      if (!match || !apk) {
        setRelease(null);
        setStatus('The latest release does not contain an installable Android APK.');
      } else if (Number(match[1]) > installedBuild) {
        setRelease(candidate);
        setStatus('Update available: ' + (candidate.name || ('Android build ' + match[1])));
      } else {
        setRelease(null);
        setStatus('You are up to date (build ' + installedBuild + ').');
      }
    } catch (error) {
      setRelease(null);
      setStatus(error instanceof Error ? error.message : 'Could not check for updates. Check your connection.');
    } finally {
      setChecking(false);
    }
  }, [installedBuild]);

  useEffect(() => {
    // Defer the initial network check so updates are not scheduled synchronously during commit.
    const timer = setTimeout(() => {
      void check();
    }, 0);
    return () => clearTimeout(timer);
  }, [check]);

  const download = async () => {
    const apk = release?.assets?.find((asset) => asset.name === 'spotatui-android-release.apk');
    if (!apk) return;
    try {
      await Linking.openURL(apk.browser_download_url);
      setStatus('APK opened in your browser. Install the downloaded file to update Spotatui.');
    } catch {
      setStatus('Could not open the APK download link.');
    }
  };

  return (
    <View style={styles.item}>
      <Text style={styles.name}>App updates</Text>
      <Text style={type.small}>
        Installed version {Constants.nativeApplicationVersion ?? 'unknown'} · build {installedBuild}
      </Text>
      <Text style={[type.small, { marginTop: 4 }]}>{status}</Text>
      <Pressable
        style={{ marginTop: 8, minHeight: TOUCH, justifyContent: 'center' }}
        onPress={() => {
          setChecking(true);
          void check();
        }}
        disabled={checking}
        accessibilityRole="button">
        <Text style={[type.bodyBold, { color: colors.accent }]}>
          {checking ? 'Checking…' : 'Check for updates'}
        </Text>
      </Pressable>
      {release && (
        <Pressable
          style={{ minHeight: TOUCH, justifyContent: 'center' }}
          onPress={() => void download()}
          accessibilityRole="button">
          <Text style={[type.bodyBold, { color: colors.accent }]}>Download update</Text>
        </Pressable>
      )}
    </View>
  );
}

"""
source = source.replace(anchor, component + anchor, 1)
settings_path.write_text(source)
print("Installed the Android release checker and download action in Settings.")
