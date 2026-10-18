#!/usr/bin/env python3
"""Make Android Spotify login use the phone UI, not a desktop browser launcher."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_android_mobile_login.py SOURCE_ROOT")
root = Path(sys.argv[1])


def replace_once(relative_path: str, old: str, new: str, description: str) -> None:
    path = root / relative_path
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{description}: expected one match, found {count}; refusing a blind patch.")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


replace_once(
    "src/core/auth.rs",
    '''    if let Err(e) = open::that_detached(pending.url()) {
      log::warn!("could not open the browser: {e}");
    }''',
    '''    #[cfg(not(target_os = "android"))]
    if let Err(e) = open::that_detached(pending.url()) {
      log::warn!("could not open the browser: {e}");
    }
    #[cfg(target_os = "android")]
    log::info!("Android sign-in URL is presented by the mobile UI; skipping desktop browser launch.");''',
    "Bootstrap Spotify browser launcher",
)

replace_once(
    "mobile/app/src/app/(tabs)/index.tsx",
    '''  useEffect(() => {
    if (!connected) askedDiscover.current = false;
    if (askedDiscover.current || !asksDiscover(discover, connected)) return;
    askedDiscover.current = true;
    send({ OpenLibrary: 'Discover' });
  }, [connected, discover, send]);''',
    '''  useEffect(() => {
    // The local UI socket connects before Spotify authentication. A Discover
    // request at that point is rejected and must not block the post-login fetch.
    if (!connected || !spotify) askedDiscover.current = false;
    if (!spotify || askedDiscover.current || !asksDiscover(discover, connected)) return;
    askedDiscover.current = true;
    send({ OpenLibrary: 'Discover' });
  }, [connected, discover, send, spotify]);''',
    "Wait for Spotify authentication before loading the Home feed",
)

replace_once(
    "src/infra/network/mod.rs",
    '''      if let Err(e) = open::that_detached(pending.url()) {
        log::warn!("[login] failed to open browser automatically: {e}");
      }''',
    '''      #[cfg(not(target_os = "android"))]
      if let Err(e) = open::that_detached(pending.url()) {
        log::warn!("[login] failed to open browser automatically: {e}");
      }
      #[cfg(target_os = "android")]
      log::info!("[login] Android sign-in URL is exposed in the mobile UI; skipping desktop browser launch.");''',
    "Interactive Spotify browser launcher",
)

component = r'''import { useEffect, useState } from 'react';
import { Linking, Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { useChannel } from '@/core-context';
import { colors, fonts } from '@/theme';

/** Phone-first UI for Spotify's device authorization flow. */
export function SpotifyLoginPrompt() {
  const status = useChannel('status');
  const message = status?.message ?? '';
  const url = message.match(/https?:\/\/(?:[A-Za-z0-9-]+\.)*spotify\.com\/(?:pair|activate|login|authorize)\S*/i)?.[0]?.replace(/[),.;]+$/, '') ?? null;
  const code = message.match(/confirm the code\s+([A-Z0-9-]+)/i)?.[1] ?? null;
  const [dismissed, setDismissed] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => setError(null), [message]);
  const visible = Boolean(url) && dismissed !== message;

  const openLogin = () => {
    if (!url) return;
    setError(null);
    void Linking.openURL(url).catch((reason: unknown) => {
      console.warn('[Spotatui] Could not open Spotify sign-in page:', reason);
      setError('Could not open the sign-in page. Install or enable a browser, then try again.');
    });
  };

  return (
    <Modal transparent visible={visible} animationType="fade" onRequestClose={() => setDismissed(message)}>
      <View style={styles.backdrop}>
        <View style={styles.card}>
          <Text style={styles.title}>Connect Spotify</Text>
          <Text style={styles.body}>Open Spotify's pairing page in your phone's browser, enter this code, approve access, then return to Spotatui.</Text>
          {code ? <View style={styles.codeBlock}>
            <Text style={styles.label}>Spotify sign-in code</Text>
            <Text selectable style={styles.code}>{code}</Text>
          </View> : <Text selectable style={styles.body}>{message}</Text>}
          <Pressable style={styles.primary} onPress={openLogin} accessibilityRole="button">
            <Text style={styles.primaryText}>Open Spotify sign-in in browser</Text>
          </Pressable>
          {error && <Text style={styles.error}>{error}</Text>}
          <Pressable style={styles.secondary} onPress={() => setDismissed(message)} accessibilityRole="button">
            <Text style={styles.secondaryText}>Not now</Text>
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, justifyContent: 'center', padding: 22, backgroundColor: 'rgba(0,0,0,0.72)' },
  card: { gap: 16, padding: 22, borderRadius: 18, backgroundColor: colors.tile, borderWidth: 1, borderColor: colors.border },
  title: { fontFamily: fonts.bold, fontSize: 22, color: colors.text },
  body: { fontFamily: fonts.regular, fontSize: 14, lineHeight: 21, color: colors.text },
  codeBlock: { gap: 8, padding: 16, borderWidth: 1, borderColor: colors.border, borderRadius: 12 },
  label: { fontFamily: fonts.semibold, fontSize: 12, color: colors.muted },
  code: { fontFamily: fonts.bold, fontSize: 26, letterSpacing: 3, color: colors.text },
  primary: { minHeight: 50, paddingHorizontal: 16, borderRadius: 25, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.accent },
  primaryText: { fontFamily: fonts.bold, fontSize: 14, color: colors.onAccent, textAlign: 'center' },
  secondary: { minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  secondaryText: { fontFamily: fonts.semibold, fontSize: 14, color: colors.muted },
  error: { fontFamily: fonts.regular, fontSize: 13, lineHeight: 19, color: colors.accent },
});
'''

layout_path = root / "mobile/app/src/app/_layout.tsx"
source = layout_path.read_text(encoding="utf-8")
anchor = "import { Onboarding } from '@/components/Onboarding';"
if source.count(anchor) != 1:
    raise SystemExit("Root layout import changed upstream; refusing a blind patch.")
source = source.replace(anchor, anchor + "\nimport { SpotifyLoginPrompt } from '@/components/SpotifyLoginPrompt';", 1)
opening = '''        <CoreProvider connection={connection}>
          <Gate>'''
if source.count(opening) != 1:
    raise SystemExit("Root CoreProvider opening changed upstream; refusing a blind patch.")
source = source.replace(opening, '''        <CoreProvider connection={connection}>
          <>
            <SpotifyLoginPrompt />
            <Gate>''', 1)
closing = '''            </Stack>
          </Gate>
        </CoreProvider>'''
if source.count(closing) != 1:
    raise SystemExit("Root CoreProvider closing changed upstream; refusing a blind patch.")
source = source.replace(closing, '''            </Stack>
            </Gate>
          </>
        </CoreProvider>''', 1)
layout_path.write_text(source, encoding="utf-8")
(root / "mobile/app/src/components/SpotifyLoginPrompt.tsx").write_text(component, encoding="utf-8")

for relative, marker in [
    ("src/core/auth.rs", "Android sign-in URL is presented by the mobile UI"),
    ("src/infra/network/mod.rs", "Android sign-in URL is exposed in the mobile UI"),
    ("mobile/app/src/app/_layout.tsx", "<SpotifyLoginPrompt />"),
    ("mobile/app/src/components/SpotifyLoginPrompt.tsx", "Linking.openURL(url)"),
    ("mobile/app/src/app/(tabs)/index.tsx", "Wait for Spotify authentication"),
]:
    if marker not in (root / relative).read_text(encoding="utf-8"):
        raise SystemExit(f"Post-patch check failed: {marker} not found in {relative}")
print("Android phone-first Spotify pairing patch applied and checked.")
