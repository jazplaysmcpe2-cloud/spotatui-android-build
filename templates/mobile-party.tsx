import { useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { headline, joinRequest, listeners, normalizeCode, normalizeName } from '@gui/partyModel';
import { Icon } from '@/components/Icon';
import { MINI_PLAYER_SPACE, MiniPlayer } from '@/components/MiniPlayer';
import { StatusScrim } from '@/components/StatusScrim';
import { useChannel, useCoreSelect, useSend } from '@/core-context';
import { colors, fonts, type } from '@/theme';

/** Native Spotify listening-party room controls backed by Spotatui's existing party relay. */
export default function PartyScreen() {
  const party = useChannel('party') ?? null;
  const connected = useCoreSelect((state) => state.connected);
  const send = useSend();
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const room = party?.room ?? null;
  const canAct = connected && Boolean(party?.available) && party?.phase !== 'connecting';
  const join = joinRequest(code, name);
  const people = room ? listeners(room) : [];

  return (
    <View style={styles.screen}>
      <SafeAreaView edges={['top']} style={styles.header}>
        <Text style={type.title}>Listening Party</Text>
        <Text style={type.small}>{party ? headline(party) : 'Connecting to party service…'}</Text>
      </SafeAreaView>
      <ScrollView contentContainerStyle={{ paddingBottom: MINI_PLAYER_SPACE }}>
        {!room ? (
          <View style={styles.formArea}>
            {!party?.available && <Text style={styles.notice}>Sign in to Spotify Premium to host or join a party.</Text>}
            <View style={styles.panel}>
              <Text style={styles.panelTitle}>Host a party</Text>
              <Text style={type.small}>Create a room code for friends to join.</Text>
              <Pressable
                style={[styles.primary, !canAct && styles.disabled]}
                disabled={!canAct}
                onPress={() => send('StartParty')}
                accessibilityRole="button">
                <Text style={styles.primaryText}>{party?.phase === 'connecting' ? 'Connecting…' : 'Start Party'}</Text>
              </Pressable>
            </View>
            <View style={styles.panel}>
              <Text style={styles.panelTitle}>Join a party</Text>
              <Text style={type.small}>Enter the six-character room code and the name shown to listeners.</Text>
              <TextInput
                value={code}
                onChangeText={(value) => setCode(normalizeCode(value))}
                placeholder="ABC123"
                placeholderTextColor={colors.dim}
                autoCapitalize="characters"
                autoCorrect={false}
                maxLength={6}
                editable={canAct}
                style={styles.input}
                accessibilityLabel="Party code"
              />
              <TextInput
                value={name}
                onChangeText={(value) => setName(normalizeName(value))}
                placeholder="Your name"
                placeholderTextColor={colors.dim}
                maxLength={32}
                editable={canAct}
                style={styles.input}
                accessibilityLabel="Your party name"
              />
              <Pressable
                style={[styles.primary, (!canAct || !join) && styles.disabled]}
                disabled={!canAct || !join}
                onPress={() => join && send(join)}
                accessibilityRole="button">
                <Text style={styles.primaryText}>Join Party</Text>
              </Pressable>
            </View>
          </View>
        ) : (
          <View style={styles.room}>
            <View style={styles.roomHeading}>
              <View style={styles.words}>
                <Text style={styles.panelTitle}>{room.host ? 'You are hosting' : 'You joined the party'}</Text>
                <Text style={type.small}>{headline(party!)}</Text>
              </View>
              {room.code ? (
                <Text selectable style={styles.code}>{room.code}</Text>
              ) : <Text style={styles.code}>Getting code…</Text>}
            </View>
            <View style={styles.panel}>
              <Text style={styles.panelTitle}>Listeners ({people.length})</Text>
              {people.map((person, index) => (
                <View key={person.name + '-' + index} style={styles.listener}>
                  <View style={[styles.initial, { backgroundColor: `hsl(${person.hue}, 44%, 36%)` }]}>
                    <Text style={styles.initialText}>{person.initial}</Text>
                  </View>
                  <View style={styles.words}>
                    <Text style={styles.listenerName}>{person.name}</Text>
                    <Text style={type.small}>{person.role}</Text>
                  </View>
                </View>
              ))}
            </View>
            {room.host && (
              <Pressable style={styles.secondary} onPress={() => send('TogglePartyControlMode')} accessibilityRole="button">
                <Icon name="devices" color={colors.accent} />
                <Text style={styles.secondaryText}>{room.shared_control ? 'Shared control: on' : 'Shared control: off'}</Text>
              </Pressable>
            )}
            <Pressable
              style={[styles.secondary, styles.leave]}
              onPress={() => Alert.alert(room.host ? 'End party?' : 'Leave party?', room.host ? 'This will close the room for everyone.' : 'You will leave this room.', [
                { text: 'Cancel', style: 'cancel' },
                { text: room.host ? 'End party' : 'Leave', style: 'destructive', onPress: () => send('LeaveParty') }
              ])}
              accessibilityRole="button">
              <Text style={styles.leaveText}>{room.host ? 'End Party' : 'Leave Party'}</Text>
            </Pressable>
          </View>
        )}
      </ScrollView>
      <StatusScrim />
      <MiniPlayer />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  header: { gap: 5, paddingHorizontal: 16, paddingTop: 12, paddingBottom: 8 },
  formArea: { gap: 12, padding: 16 },
  panel: { gap: 12, padding: 16, borderRadius: 8, backgroundColor: colors.surface },
  panelTitle: { fontFamily: fonts.bold, fontSize: 17, color: colors.text },
  notice: { padding: 12, borderRadius: 6, backgroundColor: colors.sheet, color: colors.muted, fontFamily: fonts.regular, fontSize: 13 },
  primary: { minHeight: 46, alignItems: 'center', justifyContent: 'center', borderRadius: 23, backgroundColor: colors.accent },
  primaryText: { fontFamily: fonts.bold, fontSize: 14, color: colors.onAccent },
  disabled: { opacity: 0.4 },
  input: { borderWidth: 1, borderColor: colors.border, borderRadius: 6, paddingHorizontal: 12, paddingVertical: 11, color: colors.text, fontFamily: fonts.regular, fontSize: 15 },
  room: { padding: 16, gap: 14 },
  roomHeading: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  words: { flex: 1, minWidth: 0, gap: 4 },
  code: { padding: 10, borderRadius: 8, backgroundColor: colors.tile, color: colors.accent, fontFamily: fonts.extrabold, fontSize: 24, letterSpacing: 2 },
  listener: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  initial: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  initialText: { fontFamily: fonts.bold, fontSize: 16, color: colors.text },
  listenerName: { fontFamily: fonts.semibold, fontSize: 15, color: colors.text },
  secondary: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 10, minHeight: 44, borderRadius: 8, borderWidth: 1, borderColor: colors.border },
  secondaryText: { fontFamily: fonts.semibold, fontSize: 14, color: colors.text },
  leave: { borderColor: '#8c3737' },
  leaveText: { fontFamily: fonts.semibold, fontSize: 14, color: '#e27878' },
});
