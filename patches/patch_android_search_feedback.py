#!/usr/bin/env python3
"""Add explicit recovery UI when Android Spotify searches receive no reply."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_android_search_feedback.py SOURCE_ROOT")

root = Path(sys.argv[1])
search_path = root / "mobile/app/src/app/(tabs)/search.tsx"
source = search_path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, description: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(
            f"{description}: expected exactly one match in {search_path}, found {count}; refusing a blind patch."
        )
    source = source.replace(old, new, 1)


replace_once(
    "import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';",
    "import { Linking, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';",
    "React Native imports",
)
replace_once(
    "  const results = query && search?.query ? search : null;",
    "  const results = query && search?.query === query ? search : null;",
    "Prevent stale search results from masking a failed query",
)

replace_once(
    "const TYPE_PAUSE_MS = 350;",
    """const TYPE_PAUSE_MS = 350;
/** Keep a failed or unauthenticated Spotify request from spinning indefinitely. */
const SEARCH_RESPONSE_TIMEOUT_MS = 25_000;""",
    "Search response timeout",
)
replace_once(
    "  const [asked, setAsked] = useState<string | null>(null);",
    """  const [asked, setAsked] = useState<string | null>(null);
  const [timedOutQuery, setTimedOutQuery] = useState<string | null>(null);""",
    "Search timeout state",
)
replace_once(
    """  useEffect(() => {
    if (!query || query === asked) return;
    const timer = setTimeout(() => ask(query), TYPE_PAUSE_MS);
    return () => clearTimeout(timer);
  }, [query, asked, ask]);""",
    """  useEffect(() => {
    if (!spotifyAvailable || !query || query === asked) return;
    const timer = setTimeout(() => ask(query), TYPE_PAUSE_MS);
    return () => clearTimeout(timer);
  }, [query, asked, ask, spotifyAvailable]);""",
    "Wait for Spotify session before searching",
)

replace_once(
    "  const search = useChannel('search');",
    """  const search = useChannel('search');
  const status = useChannel('status');
  const liked = useChannel('liked');
  const spotifyAvailable = liked?.available ?? false;""",
    "Status channel subscription",
)
replace_once(
    "      setAsked(next);\n      send({ SearchActiveSource: next });",
    "      setAsked(next);\n      setTimedOutQuery(null);\n      send({ SearchActiveSource: next });",
    "Clear timeout on a new search",
)
replace_once(
    "  }, [query, asked, ask, spotifyAvailable]);\n  const submit = () => {",
    """  }, [query, asked, ask, spotifyAvailable]);
  // The backend may drop a Spotify search when login is missing, or return an
  // error without publishing SearchPayload. Never leave the mobile UI spinning
  // forever; the timeout also exposes the backend's status/error for recovery.
  useEffect(() => {
    if (!spotifyAvailable || !query || !asked || query !== asked || search?.query === query) return;
    const timer = setTimeout(
      () => setTimedOutQuery(query),
      SEARCH_RESPONSE_TIMEOUT_MS,
    );
    return () => clearTimeout(timer);
  }, [asked, query, search?.query, spotifyAvailable]);
  const timedOut = spotifyAvailable && timedOutQuery === query && search?.query !== query;
  const statusText = [status?.message, status?.api_error].filter(Boolean).join('\\n');
  const loginUrl = statusText.match(/https?:\\/\\/[^\\s)]+/)?.[0]?.replace(/[.,;]+$/, '') ?? null;
  const submit = () => {""",
    "Timed-out request state and Spotify login link",
)
replace_once(
    """  const submit = () => {
    if (!query) return;
    setTab('All');
    if (query !== asked) ask(query);
  };""",
    """  const submit = () => {
    if (!query) return;
    setTab('All');
    // Keep Enter from consuming the query before Spotify has authenticated.
    // The gated search effect will dispatch it after the session is available.
    if (!spotifyAvailable) {
      setAsked(null);
      setTimedOutQuery(null);
      return;
    }
    if (query !== asked) ask(query);
  };""",
    "Do not consume a pre-login search submission",
)

replace_once(
    "              onChangeText={setText}",
    """              onChangeText={(next) => {
                setText(next);
                setTimedOutQuery(null);
              }}""",
    "Reset timeout when query changes",
)
replace_once(
    """        {query && !results && <Text style={[type.small, styles.status]}>Searching…</Text>}
        {results && <Results results={results} tab={tab} playingUri={item?.uri ?? null} />}""",
    """        {query && !spotifyAvailable && (
          <View style={[styles.status, styles.timeoutBlock]}>
            <Text style={type.small}>Connect a Spotify Premium account to search songs, artists and albums.</Text>
            {status?.message && status.is_error && <Text style={type.small}>{status.message}</Text>}
            <Pressable
              onPress={() => send({ SelectSource: 'Spotify' })}
              style={styles.timeoutButton}
              accessibilityRole="button">
              <Text style={[type.bodyBold, { color: colors.accent }]}>Connect Spotify</Text>
            </Pressable>
          </View>
        )}
        {query && spotifyAvailable && !results && !timedOut && (
          <Text style={[type.small, styles.status]}>Searching…</Text>
        )}
        {timedOut && spotifyAvailable && (
          <View style={[styles.status, styles.timeoutBlock]}>
            <Text style={type.small}>
              Spotify did not return results for this search. Check your internet connection and sign in with a Spotify Premium account.
            </Text>
            {status?.message && <Text style={type.small}>{status.message}</Text>}
            {status?.api_error && status.api_error !== status.message && (
              <Text style={type.small}>{status.api_error}</Text>
            )}
            {loginUrl && (
              <Pressable
                onPress={() => {
                  void Linking.openURL(loginUrl).catch(() => undefined);
                }}
                style={styles.timeoutButton}
                accessibilityRole="button">
                <Text style={[type.bodyBold, { color: colors.accent }]}>Open Spotify login</Text>
              </Pressable>
            )}
            <View style={styles.timeoutActions}>
              <Pressable
                onPress={() => send({ SelectSource: 'Spotify' })}
                style={styles.timeoutButton}
                accessibilityRole="button">
                <Text style={[type.bodyBold, { color: colors.accent }]}>Connect Spotify</Text>
              </Pressable>
              <Pressable
                onPress={() => {
                  setTimedOutQuery(null);
                  setAsked(null);
                }}
                style={styles.timeoutButton}
                accessibilityRole="button">
                <Text style={[type.bodyBold, { color: colors.accent }]}>Retry search</Text>
              </Pressable>
            </View>
          </View>
        )}
        {results && <Results results={results} tab={tab} playingUri={item?.uri ?? null} />}""",
    "Search loading and recovery UI",
)
replace_once(
    "  status: { paddingHorizontal: 16, paddingTop: 24 },",
    """  status: { paddingHorizontal: 16, paddingTop: 24 },
  timeoutBlock: { gap: 10, paddingBottom: 16 },
  timeoutActions: { flexDirection: 'row', flexWrap: 'wrap', gap: 16 },
  timeoutButton: { minHeight: 44, justifyContent: 'center' },""",
    "Search timeout styles",
)

search_path.write_text(source, encoding="utf-8")
checks = [
    "SEARCH_RESPONSE_TIMEOUT_MS = 25_000",
    "Spotify did not return results for this search.",
    "SelectSource: 'Spotify'",
    "Retry search",
    "!spotifyAvailable || !query || query === asked",
    "Connect a Spotify Premium account",
    "Do not consume a pre-login search submission",
]
for marker in checks:
    if marker not in source:
        raise SystemExit(f"Post-patch verification failed: missing {marker!r}")
print("Applied and verified Android search timeout, status diagnostics, Spotify reconnect, and retry controls.")
