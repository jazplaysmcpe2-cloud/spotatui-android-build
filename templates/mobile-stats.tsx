import { useEffect } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { EN } from '@gui/i18n';
import { cyclesTo, groupThousands, moveText, periods, playable, sentence } from '@gui/statsModel';

import { Chip } from '@/components/Controls';
import { MINI_PLAYER_SPACE, MiniPlayer } from '@/components/MiniPlayer';
import { StatusScrim } from '@/components/StatusScrim';
import { TrackRow } from '@/components/TrackRow';
import { useChannel, useSend } from '@/core-context';
import { usePlayback } from '@/playback';
import { colors, fonts, type } from '@/theme';

/** Native counterpart to the desktop Stats page; it uses the same Rust stats channel and actions. */
export default function StatsScreen() {
  const stats = useChannel('stats');
  const send = useSend();
  const { item } = usePlayback();
  const period = stats?.period ?? '30d';
  const periodOptions = periods(EN);
  const plays = (key: string) => stats?.plays.find((entry) => entry.period === key)?.plays ?? 0;
  const noHistory = Boolean(stats?.loaded && plays('all') === 0);
  const emptyPeriod = Boolean(stats?.loaded && plays(period) === 0);

  useEffect(() => {
    send({ OpenLibrary: 'Stats' });
  }, [send]);

  const pickPeriod = (next: string) => {
    const steps = cyclesTo(period, next);
    for (let step = 0; step < steps.count; step += 1) {
      send({ CycleStatsPeriod: { forward: steps.forward } });
    }
  };

  const playTrack = (uri: string | null) => {
    if (playable(uri)) send({ PlayUris: { uris: [uri], offset: 0 } });
  };

  return (
    <View style={styles.screen}>
      <SafeAreaView edges={['top']} style={styles.top}>
        <Text style={type.title}>{EN.stats}</Text>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
          {periodOptions.map((entry) => (
            <Chip key={entry.key} label={entry.label} on={entry.key === period} onPress={() => pickPeriod(entry.key)} />
          ))}
        </ScrollView>
      </SafeAreaView>

      <ScrollView
        contentContainerStyle={{ paddingBottom: MINI_PLAYER_SPACE }}
        refreshControl={
          <RefreshControl
            refreshing={Boolean(stats?.loading)}
            onRefresh={() => send({ OpenLibrary: 'Stats' })}
            tintColor={colors.text}
          />
        }>
        {!stats?.loaded ? (
          <Text style={[type.small, styles.status]}>
            {stats?.loading || !stats ? EN.loading : EN.history_not_loaded}
          </Text>
        ) : noHistory ? (
          <Text style={[type.small, styles.status]}>{EN.no_listening_history}</Text>
        ) : (
          <>
            <View style={styles.metricRow}>
              {[
                { key: '7d', label: EN.plays_seven_days },
                { key: '30d', label: EN.plays_thirty_days },
                { key: 'all', label: EN.plays_all_time },
              ].map((metric) => (
                <View key={metric.key} style={styles.metric}>
                  <Text style={[styles.metricCount, metric.key === period && styles.metricCurrent]}>
                    {groupThousands(plays(metric.key))}
                  </Text>
                  <Text style={styles.metricLabel}>{metric.label}</Text>
                </View>
              ))}
            </View>

            {stats.movements.length > 0 && (
              <View style={styles.movementBlock}>
                {stats.movements.map((movement) => {
                  const [before, marker, after] = sentence(movement, period, EN);
                  return (
                    <Text key={movement.kind + '-' + movement.name} style={styles.movementLine}>
                      {before}<Text style={styles.movementHighlight}>{marker}</Text>{after}
                    </Text>
                  );
                })}
              </View>
            )}

            {emptyPeriod ? (
              <Text style={[type.small, styles.status]}>{EN.no_period_plays}</Text>
            ) : (
              <>
                <SectionTitle title={EN.top_artists} trailing={period !== 'all' ? EN.vs_all_time : undefined} />
                {stats.top_artists.slice(0, 10).map((row, index) => {
                  const movement = moveText(row, index + 1, EN);
                  return (
                    <View key={row.title} style={styles.artistRow}>
                      <Text style={styles.rank}>{index + 1}</Text>
                      <Text style={styles.artistName} numberOfLines={1}>{row.title}</Text>
                      {period !== 'all' && (
                        <Text style={[
                          styles.rankMovement,
                          movement.tone === 'up' && styles.rankUp,
                          movement.tone === 'down' && styles.rankDown,
                        ]}>{movement.text}</Text>
                      )}
                    </View>
                  );
                })}

                <SectionTitle title={EN.top_albums} />
                {stats.top_albums.slice(0, 10).map((row, index) => (
                  <View key={row.title} style={styles.artistRow}>
                    <Text style={styles.rank}>{index + 1}</Text>
                    <Text style={styles.artistName} numberOfLines={1}>{row.title}</Text>
                  </View>
                ))}

                <SectionTitle title={EN.top_tracks} />
                {stats.top_tracks.slice(0, 10).map((row, index) => (
                  <TrackRow
                    key={row.title + '-' + index}
                    image={null}
                    cover={false}
                    rank={index + 1}
                    title={row.title}
                    subtitle={row.artist ?? undefined}
                    playing={Boolean(row.uri && row.uri === item?.uri)}
                    onPress={playable(row.uri) ? () => playTrack(row.uri) : undefined}
                  />
                ))}
              </>
            )}

            {stats.week_tracks.length > 0 && (
              <>
                <SectionTitle title={EN.week_top_five} />
                {stats.week_tracks.slice(0, 5).map((row, index) => (
                  <TrackRow
                    key={row.title + '-' + index}
                    image={null}
                    cover={false}
                    rank={index + 1}
                    title={row.title}
                    subtitle={row.artist ?? undefined}
                    playing={Boolean(row.uri && row.uri === item?.uri)}
                    onPress={playable(row.uri) ? () => playTrack(row.uri) : undefined}
                  />
                ))}
              </>
            )}
          </>
        )}
      </ScrollView>
      <StatusScrim />
      <MiniPlayer />
    </View>
  );
}

function SectionTitle({ title, trailing }: { title: string; trailing?: string }) {
  return (
    <View style={styles.sectionTitleRow}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {!!trailing && <Text style={styles.sectionTrailing}>{trailing}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  top: { gap: 12, paddingHorizontal: 16, paddingTop: 12, paddingBottom: 8 },
  chips: { gap: 8 },
  status: { paddingHorizontal: 16, paddingVertical: 20 },
  metricRow: { flexDirection: 'row', gap: 8, paddingHorizontal: 16, paddingTop: 8, paddingBottom: 12 },
  metric: { flex: 1, minWidth: 0, padding: 10, borderRadius: 6, backgroundColor: colors.surface },
  metricCount: { fontFamily: fonts.extrabold, fontSize: 22, color: colors.text, fontVariant: ['tabular-nums'] },
  metricCurrent: { color: colors.accent },
  metricLabel: { fontFamily: fonts.medium, fontSize: 11, color: colors.muted, marginTop: 3 },
  movementBlock: { gap: 8, paddingHorizontal: 16, paddingBottom: 8 },
  movementLine: { fontFamily: fonts.regular, fontSize: 13, lineHeight: 19, color: colors.muted },
  movementHighlight: { color: colors.accent, fontFamily: fonts.bold },
  sectionTitleRow: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', paddingHorizontal: 16, paddingTop: 22, paddingBottom: 8 },
  sectionTitle: { fontFamily: fonts.bold, fontSize: 18, color: colors.text },
  sectionTrailing: { fontFamily: fonts.regular, fontSize: 11, color: colors.muted },
  artistRow: { minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 16 },
  rank: { width: 24, textAlign: 'center', fontFamily: fonts.medium, fontSize: 14, color: colors.muted, fontVariant: ['tabular-nums'] },
  artistName: { flex: 1, minWidth: 0, fontFamily: fonts.semibold, fontSize: 14, color: colors.text },
  rankMovement: { fontFamily: fonts.medium, fontSize: 12, color: colors.muted },
  rankUp: { color: '#65c58c' },
  rankDown: { color: '#ee827b' },
});
