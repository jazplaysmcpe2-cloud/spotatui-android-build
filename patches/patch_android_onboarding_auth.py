#!/usr/bin/env python3
"""Make Spotify's device authorization flow explicit and usable from Android."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_android_onboarding_auth.py SOURCE_ROOT")

root = Path(sys.argv[1])
path = root / "mobile/app/src/components/Onboarding.tsx"
source = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, description: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(
            f"{description}: expected exactly one match in {path}, found {count}; refusing a blind patch."
        )
    source = source.replace(old, new, 1)


replace_once(
    "import * as WebBrowser from 'expo-web-browser';\n",
    "",
    "Replace the embedded browser import",
)
replace_once(
    "import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';",
    "import { Linking, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';",
    "React Native imports",
)

start_marker = "function Transcript({ text }: { text: string }) {"
end_marker = "\nfunction Question("
if source.count(start_marker) != 1 or source.count(end_marker) != 1:
    raise SystemExit("Onboarding transcript component changed upstream; refusing a blind patch.")
start = source.index(start_marker)
end = source.index(end_marker, start)
transcript_component = """function Transcript({ text }: { text: string }) {
  const [openError, setOpenError] = useState<string | null>(null);
  const parts = text.split(/(https?:\\/\\/\\S+)/);
  const spotifyUrl = parts.find(
    (part, index) => index % 2 === 1 && isSpotifyAuthUrl(part),
  );

  const openUrl = (url: string) => {
    const cleanUrl = url.replace(/[),.;]+$/, '');
    setOpenError(null);
    void Linking.openURL(cleanUrl).catch((error: unknown) => {
      const reason = error instanceof Error ? error.message : String(error);
      console.warn('[Spotatui] Could not open Spotify sign-in URL:', reason);
      setOpenError('Could not open the sign-in page. Tap the highlighted Spotify URL directly or check that a browser is installed.');
    });
  };

  return (
    <View style={styles.transcriptBlock}>
      <Text style={styles.transcript}>
        {parts.map((part, index) =>
          index % 2 === 1 ? (
            <Text key={index} style={styles.link} onPress={() => openUrl(part)}>
              {part}
            </Text>
          ) : (
            part
          ),
        )}
      </Text>
      {spotifyUrl && (
        <Pressable
          style={styles.openLoginButton}
          onPress={() => openUrl(spotifyUrl)}
          accessibilityRole=\"button\"
          accessibilityLabel=\"Open Spotify sign-in in browser\">
          <Text style={styles.openLoginText}>Open Spotify sign-in in browser</Text>
        </Pressable>
      )}
      {openError && <Text style={styles.linkError}>{openError}</Text>}
    </View>
  );
}

/** Only promote Spotify's HTTPS pairing/authorization URLs to the sign-in action. */
function isSpotifyAuthUrl(url: string): boolean {
  const cleanUrl = url.replace(/[),.;]+$/, '');
  try {
    const parsed = new URL(cleanUrl);
    const spotifyHost =
      parsed.hostname === 'spotify.com' || parsed.hostname.endsWith('.spotify.com');
    return (
      parsed.protocol === 'https:' &&
      spotifyHost &&
      /\\/(?:pair|activate|login|authorize)(?:\\/|$)/i.test(parsed.pathname)
    );
  } catch {
    return false;
  }
}
"""
source = source[:start] + transcript_component + source[end:]

replace_once(
  "  transcript: { fontFamily: fonts.mono, fontSize: 12, lineHeight: 18, color: colors.muted },\n  link: { color: colors.accent, textDecorationLine: 'underline' },\n  question: { gap: 12 },",
  """  transcriptBlock: { gap: 12 },
  transcript: { fontFamily: fonts.mono, fontSize: 12, lineHeight: 18, color: colors.muted },
  link: { color: colors.accent, textDecorationLine: 'underline' },
  openLoginButton: {
    minHeight: 48,
    alignSelf: 'flex-start',
    paddingHorizontal: 18,
    borderRadius: 24,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.accent,
  },
  openLoginText: { fontFamily: fonts.bold, fontSize: 14, color: colors.onAccent },
  linkError: { fontFamily: fonts.regular, fontSize: 12, lineHeight: 18, color: colors.accent },
  question: { gap: 12 },""",
  "Explicit Spotify sign-in action styles",
)

path.write_text(source, encoding="utf-8")
for marker in (
    "accessibilityLabel=\"Open Spotify sign-in in browser\"",
    "Linking.openURL(cleanUrl)",
    "function isSpotifyAuthUrl(url: string)",
    "Could not open the sign-in page.",
):
    if marker not in source:
        raise SystemExit(f"Post-patch verification failed: missing {marker!r}")
print("Applied and verified the explicit Android Spotify sign-in button and visible browser-open recovery message.")
