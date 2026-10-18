import { router } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Icon } from '@/components/Icon';
import { MINI_PLAYER_SPACE, MiniPlayer } from '@/components/MiniPlayer';
import { StatusScrim } from '@/components/StatusScrim';
import { colors, fonts, type } from '@/theme';

const LINKS = [
  { title: 'Discover', subtitle: 'Spotify recommendations', icon: 'speaker', path: '/discover' },
  { title: 'Recently Played', subtitle: 'Your recent listening history', icon: 'history', path: '/recently-played' },
  { title: 'Listening Session', subtitle: 'What played, what is playing, what comes next', icon: 'history', path: '/session' },
  { title: 'Listening Party', subtitle: 'Host or join a shared listening room', icon: 'devices', path: '/party' },
  { title: 'Settings', subtitle: 'Playback, account and app preferences', icon: 'settings', path: '/settings' },
] as const;

/** Small native navigation hub for desktop surfaces that do not fit on the main tab bar. */
export default function MoreScreen() {
  return (
    <View style={styles.screen}>
      <SafeAreaView edges={['top']} style={styles.header}>
        <Text style={type.title}>More</Text>
        <Text style={type.small}>Additional Spotatui features</Text>
      </SafeAreaView>
      <ScrollView contentContainerStyle={{ paddingBottom: MINI_PLAYER_SPACE }}>
        {LINKS.map((link) => (
          <Pressable
            key={link.path}
            style={styles.row}
            onPress={() => router.push(link.path)}
            accessibilityRole="button"
            accessibilityLabel={link.title}>
            <View style={styles.icon}><Icon name={link.icon} size={23} color={colors.accent} /></View>
            <View style={styles.words}>
              <Text style={styles.title}>{link.title}</Text>
              <Text style={styles.subtitle}>{link.subtitle}</Text>
            </View>
            <Icon name="forward" size={18} color={colors.muted} />
          </Pressable>
        ))}
      </ScrollView>
      <StatusScrim />
      <MiniPlayer />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  header: { gap: 5, paddingHorizontal: 16, paddingTop: 12, paddingBottom: 8 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 78, paddingHorizontal: 16, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
  icon: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', borderRadius: 10, backgroundColor: colors.surface },
  words: { flex: 1, minWidth: 0, gap: 4 },
  title: { fontFamily: fonts.semibold, fontSize: 15, color: colors.text },
  subtitle: { fontFamily: fonts.regular, fontSize: 12, color: colors.muted },
});
