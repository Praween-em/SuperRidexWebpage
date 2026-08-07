import React, { useState, useEffect } from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView,
  TouchableOpacity,
} from 'react-native';
import Screen from '../components/Screen';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import { navigateRoot, replaceRoot } from '../navigation/rootNavigation';
import { getSettings, saveSettings } from '../utils/settingsStorage';
import { getStoredUser, clearUser } from '../utils/storage';
import { usePermissions } from '../hooks/usePermissions';

const DELAYS = [0, 50, 100];

function SectionLabel({ children }) {
  return (
    <View style={styles.sectionLabelRow}>
      <View style={styles.sectionBar} />
      <Text style={styles.sectionLabel}>{children}</Text>
    </View>
  );
}

function SettingRow({ icon, title, sub, subColor, onPress, right, separator = true }) {
  const Wrap = onPress ? TouchableOpacity : View;
  return (
    <>
      <Wrap style={styles.row} onPress={onPress} activeOpacity={0.7}>
        <View style={styles.rowIconBox}>{icon}</View>
        <View style={styles.rowContent}>
          <Text style={styles.rowTitle}>{title}</Text>
          {sub ? <Text style={[styles.rowSub, subColor && { color: subColor }]}>{sub}</Text> : null}
        </View>
        {right}
        {onPress && <Text style={styles.chevron}>›</Text>}
      </Wrap>
      {separator && <View style={styles.separator} />}
    </>
  );
}

export default function SettingsScreen({ navigation }) {
  const { status: permStatus } = usePermissions();
  const [delay, setDelay] = useState(0);
  const [phone, setPhone] = useState('');

  useEffect(() => {
    getSettings().then(s => setDelay(s.delayMs ?? 0));
    getStoredUser().then(u => setPhone(u?.phone ? `+91 ${u.phone}` : ''));
  }, []);

  const pickDelay = async (d) => {
    setDelay(d);
    await saveSettings({ delayMs: d });
  };

  const handleLogout = async () => {
    await clearUser();
    replaceRoot(navigation, 'Login');
  };

  return (
    <Screen edges={['top']}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />
      <View style={styles.header}>
        <Text style={styles.headerTitle}>Settings</Text>
      </View>

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <SectionLabel>Account</SectionLabel>
        <View style={styles.group}>
          <SettingRow icon={<Ionicons name="phone-portrait-outline" size={18} color={colors.purpleBright} />} title="Mobile Number" sub={phone || '—'} />
          <SettingRow
            icon={<Ionicons name="card-outline" size={18} color={colors.purpleBright} />} title="Subscription / Renewal"
            sub="View plans"
            subColor={colors.purpleBright}
            onPress={() => navigateRoot(navigation, 'Plans')}
            separator={false}
          />
        </View>

        <SectionLabel>Tap Behavior</SectionLabel>
        <View style={styles.group}>
          <View style={styles.row}>
            <View style={styles.rowIconBox}>
              <Ionicons name="timer-outline" size={18} color={colors.purpleBright} />
            </View>
            <View style={styles.rowContent}>
              <Text style={styles.rowTitle}>Response Delay</Text>
              <Text style={styles.rowSub}>Standard mode only. Nuclear always uses 0ms.</Text>
            </View>
          </View>
          <View style={styles.chipRow}>
            {DELAYS.map(d => (
              <TouchableOpacity
                key={d}
                style={[styles.chip, delay === d && styles.chipActive]}
                onPress={() => pickDelay(d)}
              >
                <Text style={[styles.chipText, delay === d && styles.chipTextActive]}>{d}ms</Text>
              </TouchableOpacity>
            ))}
          </View>
        </View>

        <SectionLabel>Permissions</SectionLabel>
        <View style={styles.group}>
          <SettingRow
            icon={<Ionicons name="accessibility-outline" size={18} color={colors.purpleBright} />} title="Accessibility Service"
            sub={permStatus.accessibility ? 'Enabled' : 'Not enabled — Tap to fix'}
            subColor={permStatus.accessibility ? colors.onGreen : colors.offRed}
            onPress={() => navigateRoot(navigation, 'PermissionsSetup')}
          />
          <SettingRow
            icon={<Ionicons name="copy-outline" size={18} color={colors.purpleBright} />} title="Display Over Other Apps"
            sub={permStatus.overlay ? 'Enabled' : 'Not enabled'}
            subColor={permStatus.overlay ? colors.onGreen : colors.offRed}
            onPress={() => navigateRoot(navigation, 'PermissionsSetup')}
          />
          <SettingRow
            icon={<Ionicons name="battery-charging-outline" size={18} color={colors.purpleBright} />} title="Battery Optimization"
            sub={permStatus.battery ? 'Exemption granted' : 'Not granted'}
            subColor={permStatus.battery ? colors.onGreen : colors.offRed}
            onPress={() => navigateRoot(navigation, 'PermissionsSetup')}
            separator={false}
          />
        </View>

        <SectionLabel>Legal</SectionLabel>
        <View style={styles.group}>
          <SettingRow
            icon={<Ionicons name="document-text-outline" size={18} color={colors.purpleBright} />}
            title="Privacy Policy"
            sub="How we handle your data"
            onPress={() => navigateRoot(navigation, 'PrivacyPolicy')}
          />
          <SettingRow
            icon={<Ionicons name="shield-checkmark-outline" size={18} color={colors.purpleBright} />}
            title="Terms & Conditions"
            sub="Accessibility assistive use terms"
            onPress={() => navigateRoot(navigation, 'Terms')}
            separator={false}
          />
        </View>

        <TouchableOpacity style={styles.logoutBtn} onPress={handleLogout} activeOpacity={0.85}>
          <Text style={styles.logoutText}>LOG OUT</Text>
        </TouchableOpacity>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: {
    paddingHorizontal: 16, paddingVertical: 14,
    borderBottomWidth: 1, borderBottomColor: colors.border, alignItems: 'center',
  },
  headerTitle: { fontSize: 18, fontWeight: '800', color: colors.icy },
  scroll: { paddingHorizontal: 16, paddingTop: 16, paddingBottom: 48, gap: 8 },
  sectionLabelRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 12, marginBottom: 6, marginLeft: 4 },
  sectionBar: { width: 3, height: 14, borderRadius: 2, backgroundColor: colors.purple },
  sectionLabel: { fontSize: 11, fontWeight: '800', color: colors.icyMuted, letterSpacing: 1.5, textTransform: 'uppercase' },
  group: { backgroundColor: colors.surface, borderRadius: 16, borderWidth: 1, borderColor: colors.border, overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 14, gap: 14 },
  rowIconBox: { width: 36, height: 36, borderRadius: 10, backgroundColor: colors.surfaceLight, alignItems: 'center', justifyContent: 'center' },
  rowIcon: { fontSize: 18 },
  rowContent: { flex: 1 },
  rowTitle: { fontSize: 15, fontWeight: '600', color: colors.icy },
  rowSub: { fontSize: 12, color: colors.icyDim, marginTop: 2 },
  chevron: { fontSize: 20, color: colors.icyMuted },
  separator: { height: 1, backgroundColor: colors.border, marginLeft: 66 },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 10, paddingHorizontal: 16, paddingBottom: 14 },
  chip: {
    paddingHorizontal: 18, paddingVertical: 12, borderRadius: 50,
    backgroundColor: colors.surfaceLight, borderWidth: 1.5, borderColor: colors.border,
  },
  chipActive: { backgroundColor: colors.onGreenDim, borderColor: colors.onGreen },
  chipText: { fontSize: 14, color: colors.icyMuted, fontWeight: '700' },
  chipTextActive: { color: colors.onGreen },
  logoutBtn: {
    marginTop: 16, borderWidth: 1.5, borderColor: colors.offRed,
    borderRadius: 14, paddingVertical: 16, alignItems: 'center', backgroundColor: colors.offRedDim,
  },
  logoutText: { color: colors.offRed, fontSize: 14, fontWeight: '900', letterSpacing: 1.5 },
});
