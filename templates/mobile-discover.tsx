import { router } from 'expo-router';
import { useEffect, useRef } from 'react';
import { Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { asksDiscover } from '@gui/pagesModel';

import { Cover } from '@/components/Cover';
import { Icon } from '@/components/Icon';
import { MINI_PLAYER_SPACE, MiniPlayer } from '@/components/MiniPlayer';
import { StatusScrim } from '@/components/StatusScrim';
import { useChannel, useCoreSelect, useSend } from '@/core-context';
import { colors, fonts, type } from '@/theme';

/** Native counterpart to Spotatui desktop Discover (Spotify Made For You shelves). */
export default function DiscoverScreen() {
  const discover = useChannel('discover') ?? null;
  const connected = useCoreSelect((state) => state.connected);
  const send = useSend();
  const asked = useRef(false);

  useEffect(() => {
    if (!connected) asked.current = false;
    if (!asked.current && asksDiscover(discover, connected)) {
      asked.current = true;
      send({ OpenLibrary: 'Discover' });
    }
  }, [connected, discover, send]);

  return (
    <View style={styles.screen}>
      <SafeAreaView edges={['top']} style={styles.header}>
        <Text style={type.title}>Discover</Text>
        <Text style={type.small}>Made for you by Spotify</Text>
      </SafeAreaView>
      <ScrollView
        contentContainerStyle={{ paddingBottom: MINI_PLAYER_SPACE }}
        refreshControl={
          <RefreshControl
            refreshing={Boolean(discover?.loading)}
            onRefresh={() => send({ OpenLibrary: 'Discover' })}
            tintColor={colors.text}
          />
        }>
        {!discover?.available ? (
          <Text style={[type.small, styles.empty]}>Sign in to Spotify Premium to discover personalized playlists.</Text>
        ) : (discover?.shelves ?? []).length === 0 ? (
          <Text style={[type.small, styles.empty]}>{discover?.loading ? 'Loading your recommendations…' : 'Nothing here yet.'}</Text>
        ) : (
          (discover?.shelves ?? []).map((shelf, shelfIndex) => (
            <View key={shelf.title + '-' + shelfIndex} style={styles.shelf}>
              <Text style={styles.shelfTitle}>{shelf.title}</Text>
              <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.cards}>
                {shelf.playlists.map((playlist) => (
                  <View key={playlist.uri} style={styles.card}>
                    <View style={styles.coverWrap}>
                      <Pressable
                        onPress={() => router.push({
                          pathname: '/playlist/[id]',
                          params: { id: playlist.uri, name: playlist.name, image: playlist.image_url ?? '' },
                        })}
                        accessibilityRole="button"
                        accessibilityLabel={playlist.name}>
                        <Cover uri={playlist.image_url} size={144} radius={6} />
                      </Pressable>
                      <Pressable
                        style={styles.play}
                        onPress={() => send({ PlayContext: { uri: playlist.uri, offset: null } })}
                        accessibilityRole="button"
                        accessibilityLabel={'Play ' + playlist.name}>
                        <Icon name="play" color={colors.onAccent} size={24} />
                      </Pressable>
                    </View>
                    <Text style={styles.cardTitle} numberOfLines={2}>{playlist.name}</Text>
                    <Text style={styles.cardSubtitle} numberOfLines={2}>{playlist.description ?? 'By Spotify'}</Text>
                  </View>
                ))}
              </ScrollView>
            </View>
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
  header: { paddingHorizontal: 16, paddingTop: 12, paddingBottom: 8, gap: 5 },
  empty: { paddingHorizontal: 16, paddingVertical: 20 },
  shelf: { marginTop: 18, gap: 12 },
  shelfTitle: { paddingHorizontal: 16, fontFamily: fonts.bold, fontSize: 18, color: colors.text },
  cards: { gap: 12, paddingHorizontal: 16 },
  card: { width: 144, gap: 5 },
  coverWrap: { position: 'relative' },
  play: { position: 'absolute', right: 8, bottom: 8, width: 42, height: 42, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.accent, borderRadius: 21 },
  cardTitle: { fontFamily: fonts.semibold, fontSize: 14, color: colors.text },
  cardSubtitle: { fontFamily: fonts.regular, fontSize: 12, lineHeight: 17, color: colors.muted },
});
