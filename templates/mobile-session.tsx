import { useEffect } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { headline, replayRun, timeOfDay } from '@gui/sessionModel';
import { upNext } from '@gui/libraryModel';

import { Cover } from '@/components/Cover';
import { MINI_PLAYER_SPACE, MiniPlayer } from '@/components/MiniPlayer';
import { StatusScrim } from '@/components/StatusScrim';
import { TrackRow } from '@/components/TrackRow';
import { useChannel, useSend } from '@/core-context';
import { clock, isSpotify, useElapsed, usePlayback } from '@/playback';
import { colors, fonts, type } from '@/theme';

/** Listening-session timeline: current item, next queue entries and finished plays. */
export default function SessionScreen() {
  const plays = useChannel('session') ?? [];
  const queue = useChannel('queue');
  const { item } = usePlayback();
  const elapsed = useElapsed();
  const send = useSend();

  useEffect(() => {
    send('RefreshQueue');
  }, [send]);

  const next = upNext(queue, isSpotify(item?.uri))[0] ?? null;
  const newestFirst = plays.map((play, index) => ({ play, index })).reverse();

  return (
    <View style={styles.screen}>
      <SafeAreaView edges={['top']} style={styles.header}>
        <Text style={type.title}>Listening Session</Text>
        <Text style={type.small}>{headline(plays, item?.uri ?? null)}</Text>
      </SafeAreaView>
      <ScrollView
        contentContainerStyle={{ paddingBottom: MINI_PLAYER_SPACE }}
        refreshControl={<RefreshControl refreshing={false} onRefresh={() => send('RefreshQueue')} tintColor={colors.text} />}>
        <View style={styles.nowBlock}>
          <Text style={styles.sectionTitle}>Now Playing</Text>
          {item ? (
            <View style={styles.nowRow}>
              <Cover uri={item.image_url} size={80} radius={6} />
              <View style={styles.words}>
                <Text style={styles.nowTitle} numberOfLines={2}>{item.title}</Text>
                <Text style={styles.meta} numberOfLines={2}>{item.artists.join(', ')}</Text>
                <Text style={styles.meta}>{clock(Math.max(0, elapsed ?? 0))} / {item.is_live ? 'LIVE' : clock(item.duration_ms)}</Text>
              </View>
            </View>
          ) : <Text style={type.small}>Nothing is playing.</Text>}
        </View>

        {next && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Coming Up</Text>
            <TrackRow
              image={next.image_url}
              title={next.name}
              subtitle={next.artists.join(', ')}
              playing={false}
              onPress={() => next.uri && send({ PlayContext: { uri: next.uri, offset: null } })}
            />
          </View>
        )}

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Earlier This Session</Text>
          {newestFirst.length === 0 ? (
            <Text style={[type.small, styles.empty]}>Finished plays from this session will appear here.</Text>
          ) : newestFirst.map(({ play, index }) => {
            const action = replayRun(plays, index);
            return (
              <View key={play.started_at_ms + '-' + index} style={styles.historyRow}>
                <Text style={styles.time}>{timeOfDay(play.started_at_ms)}</Text>
                <View style={styles.historyTrack}>
                  <TrackRow
                    image={play.image_url}
                    title={play.title}
                    subtitle={play.artists.join(', ')}
                    onPress={action ? () => send(action) : undefined}
                  />
                </View>
              </View>
            );
          })}
        </View>
      </ScrollView>
      <StatusScrim />
      <MiniPlayer />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  header: { gap: 5, paddingHorizontal: 16, paddingTop: 12, paddingBottom: 8 },
  nowBlock: { marginTop: 12, marginHorizontal: 16, padding: 14, borderRadius: 8, backgroundColor: colors.surface, gap: 12 },
  section: { marginTop: 22 },
  sectionTitle: { paddingHorizontal: 16, fontFamily: fonts.bold, fontSize: 18, color: colors.text },
  nowRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  words: { flex: 1, gap: 4, minWidth: 0 },
  nowTitle: { fontFamily: fonts.bold, fontSize: 16, color: colors.text },
  meta: { fontFamily: fonts.regular, fontSize: 12, color: colors.muted },
  empty: { padding: 16 },
  historyRow: { flexDirection: 'row', alignItems: 'center', gap: 8, paddingRight: 6 },
  time: { width: 56, textAlign: 'right', fontFamily: fonts.mono, fontSize: 11, color: colors.muted },
  historyTrack: { flex: 1 },
});
