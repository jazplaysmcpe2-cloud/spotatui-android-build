import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';

import type { ShowInfo } from '@gui/bindings/ShowInfo';
import { episodeDate, episodeLength, episodePlay, newestEpisode } from '@gui/showModel';

import { Cover } from '@/components/Cover';
import { Icon } from '@/components/Icon';
import { MINI_PLAYER_SPACE, MiniPlayer } from '@/components/MiniPlayer';
import { StatusScrim } from '@/components/StatusScrim';
import { TrackRow } from '@/components/TrackRow';
import { useChannel, useSend } from '@/core-context';
import { usePlayback } from '@/playback';
import { colors, fonts, TOUCH, type } from '@/theme';

/** Native podcast/show page; episode pagination and follow state come from the shared Rust core. */
export default function ShowScreen() {
  const { id, name, image } = useLocalSearchParams<{ id: string; name?: string; image?: string }>();
  const send = useSend();
  const { item } = usePlayback();
  const insets = useSafeAreaInsets();
  const payload = useChannel('show');
  const page = payload?.page?.show.id === id ? payload.page : null;
  const episodes = page?.episodes ?? [];
  const [expanded, setExpanded] = useState(false);
  const [morePending, setMorePending] = useState(false);
  const requestedKey = useRef('');

  useFocusEffect(useCallback(() => {
    if (page) return;
    const show: ShowInfo = {
      id,
      uri: `spotify:show:${id}`,
      name: name ?? 'Podcast',
      description: '',
      publisher: '',
      image_url: image || null,
    };
    send({ OpenShowEpisodes: show });
  }, [id, name, image, page, send]));

  useEffect(() => {
    const key = id + ':' + episodes.length;
    if (requestedKey.current !== key) {
      requestedKey.current = key;
      setMorePending(false);
    }
  }, [id, episodes.length]);

  const playNewest = () => {
    const index = newestEpisode(episodes);
    if (index >= 0) {
      const action = episodePlay(episodes, index);
      if (action) send(action);
    }
  };
  const loadMore = () => {
    if (!morePending && page?.has_more) {
      setMorePending(true);
      send({ LoadMore: 'ShowEpisodes' });
    }
  };

  return (
    <View style={styles.screen}>
      <FlatList
        data={episodes}
        keyExtractor={(episode, index) => `${index}-${episode.uri ?? episode.name}`}
        onEndReached={loadMore}
        onEndReachedThreshold={0.35}
        contentContainerStyle={{ paddingBottom: MINI_PLAYER_SPACE + insets.bottom }}
        ListHeaderComponent={
          <SafeAreaView edges={['top']} style={styles.header}>
            <Pressable style={styles.back} onPress={() => router.back()} accessibilityLabel="Back">
              <Icon name="back" size={24} />
            </Pressable>
            <Cover uri={page?.show.image_url ?? (image || null)} size={208} style={styles.cover} />
            <View style={styles.words}>
              <Text style={styles.kind}>PODCAST</Text>
              <Text style={styles.name}>{page?.show.name ?? name ?? 'Loading…'}</Text>
              {!!page?.show.publisher && <Text style={styles.publisher}>{page.show.publisher}</Text>}
              <Text style={type.small}>{page ? `${page.total} episodes` : 'Loading episodes…'}</Text>
            </View>
            {!!page && (
              <View style={styles.actions}>
                <Pressable
                  style={[styles.follow, page.saved && styles.followOn]}
                  disabled={!page.show.id}
                  onPress={() => page.show.id && send(page.saved ? { UnsaveShow: page.show.id } : { SaveShow: page.show.id })}
                  accessibilityRole="button"
                  accessibilityState={{ selected: page.saved }}>
                  <Text style={styles.followText}>{page.saved ? 'Following' : 'Follow'}</Text>
                </Pressable>
                <View style={styles.spacer} />
                <Pressable
                  style={styles.play}
                  disabled={episodes.length === 0 || newestEpisode(episodes) < 0}
                  onPress={playNewest}
                  accessibilityRole="button"
                  accessibilityLabel="Play newest episode">
                  <Icon name="play" size={26} color={colors.onAccent} />
                </Pressable>
              </View>
            )}
            {!!page?.show.description && (
              <View style={styles.about}>
                <Text style={styles.aboutTitle}>About</Text>
                <Text style={styles.description} numberOfLines={expanded ? undefined : 4}>{page.show.description}</Text>
                {page.show.description.length > 240 && (
                  <Pressable onPress={() => setExpanded((value) => !value)} accessibilityRole="button">
                    <Text style={styles.expand}>{expanded ? 'Show less' : 'Show more'}</Text>
                  </Pressable>
                )}
              </View>
            )}
            <Text style={styles.episodeTitle}>All Episodes</Text>
          </SafeAreaView>
        }
        renderItem={({ item: episode, index }) => {
          const metadata = [episodeDate(episode.release_date), episodeLength(episode.duration_ms)].filter(Boolean).join(' · ');
          const progress = episode.resume_point
            ? episode.resume_point.fully_played
              ? 'Played'
              : episode.resume_point.resume_position_ms > 0
                ? `${Math.min(100, Math.round(episode.resume_point.resume_position_ms / Math.max(episode.duration_ms, 1) * 100))}% played`
                : ''
            : '';
          const subtitle = [metadata, progress].filter(Boolean).join(' · ');
          const action = episodePlay(episodes, index);
          return (
            <TrackRow
              image={episode.image_url ?? page?.show.image_url ?? null}
              title={episode.name}
              subtitle={subtitle || episode.description}
              playing={Boolean(episode.uri && episode.uri === item?.uri)}
              onPress={action ? () => send(action) : undefined}
            />
          );
        }}
        ListEmptyComponent={<Text style={[type.small, styles.empty]}>{page?.loaded ? 'No episodes available.' : 'Loading episodes…'}</Text>}
        ListFooterComponent={page?.has_more ? (
          <Pressable style={styles.loadMore} onPress={loadMore} disabled={morePending} accessibilityRole="button">
            <Text style={styles.expand}>{morePending ? 'Loading more…' : 'Load more episodes'}</Text>
          </Pressable>
        ) : null}
      />
      <StatusScrim />
      <MiniPlayer edge />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  header: { gap: 14, paddingHorizontal: 16, paddingBottom: 12, experimental_backgroundImage: `linear-gradient(#253c32, ${colors.background} 360px)` },
  back: { width: TOUCH, height: TOUCH, marginLeft: -12, alignItems: 'center', justifyContent: 'center' },
  cover: { alignSelf: 'center', boxShadow: '0 12px 32px rgba(0,0,0,0.4)' },
  words: { gap: 7 },
  kind: { fontFamily: fonts.bold, fontSize: 10, letterSpacing: 1.2, color: colors.muted },
  name: { fontFamily: fonts.extrabold, fontSize: 25, color: colors.text },
  publisher: { fontFamily: fonts.semibold, fontSize: 13, color: colors.text },
  actions: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  follow: { height: 34, justifyContent: 'center', paddingHorizontal: 16, borderWidth: 1, borderColor: colors.muted, borderRadius: 17 },
  followOn: { borderColor: colors.text },
  followText: { fontFamily: fonts.semibold, fontSize: 13, color: colors.text },
  spacer: { flex: 1 },
  play: { width: 52, height: 52, borderRadius: 26, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.accent },
  about: { gap: 8, paddingVertical: 8 },
  aboutTitle: { fontFamily: fonts.bold, fontSize: 18, color: colors.text },
  description: { fontFamily: fonts.regular, fontSize: 13, lineHeight: 19, color: colors.muted },
  expand: { fontFamily: fonts.semibold, fontSize: 13, color: colors.accent, paddingVertical: 5 },
  episodeTitle: { paddingTop: 12, fontFamily: fonts.bold, fontSize: 20, color: colors.text },
  empty: { paddingHorizontal: 16, paddingVertical: 20 },
  loadMore: { alignItems: 'center', paddingVertical: 20 },
});
