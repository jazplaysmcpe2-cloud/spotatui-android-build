import { useEffect } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { MINI_PLAYER_SPACE, MiniPlayer } from '@/components/MiniPlayer';
import { StatusScrim } from '@/components/StatusScrim';
import { TrackRow } from '@/components/TrackRow';
import { useChannel, useSend } from '@/core-context';
import { usePlayback } from '@/playback';
import { colors, type } from '@/theme';

/** All recently played items from the same core channel used by desktop. */
export default function RecentlyPlayedScreen() {
  const rows = useChannel('recently_played') ?? [];
  const { item } = usePlayback();
  const send = useSend();

  useEffect(() => {
    send({ Navigate: 'RecentlyPlayed' });
  }, [send]);

  return (
    <View style={styles.screen}>
      <SafeAreaView edges={['top']} style={styles.header}>
        <Text style={type.title}>Recently Played</Text>
      </SafeAreaView>
      <ScrollView
        contentContainerStyle={{ paddingBottom: MINI_PLAYER_SPACE }}
        refreshControl={
          <RefreshControl refreshing={false} onRefresh={() => send({ Navigate: 'RecentlyPlayed' })} tintColor={colors.text} />
        }>
        {rows.length === 0 ? (
          <Text style={[type.small, styles.empty]}>Your recent listening history will appear here.</Text>
        ) : (
          [...rows].reverse().map((track, index) => (
            <TrackRow
              key={track.uri ?? track.id ?? track.name + '-' + index}
              image={track.image_url}
              title={track.name}
              subtitle={track.artists.join(', ')}
              playing={Boolean(track.uri && track.uri === item?.uri)}
              onPress={() => track.uri && send({ PlayContext: { uri: track.uri, offset: null } })}
            />
          ))
        )}
      </ScrollView>
      <StatusScrim />
      <MiniPlayer />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  header: { paddingHorizontal: 16, paddingTop: 12, paddingBottom: 8 },
  empty: { paddingHorizontal: 16, paddingVertical: 20 },
});
