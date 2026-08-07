import React from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView,
  TouchableOpacity, Platform,
} from 'react-native';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';
import { usePermissions } from '../hooks/usePermissions';
import { replaceRoot } from '../navigation/rootNavigation';
import {
  openAccessibilitySettings,
  openAppInfoSettings,
  openOverlaySettings,
  openNotificationListenerSettings,
  requestBatteryOptimizationExemption,
  needsRestrictedSettingsUnlock,
} from '../services/permissions';

const STEPS = [
  {
    id: 'accessibility',
    num: '01',
    title: 'Accessibility Service',
    description: 'Lets Super Ridex detect and tap the Accept button inside Ola, Uber, and other driver apps. This is the core permission.',
    instruction: null, // custom UI below for Android 13+
    action: openAccessibilitySettings,
    actionLabel: 'Open Accessibility Settings',
    accentColor: colors.purple,
    accentBorder: colors.borderPurple,
    accentGlow: colors.purpleGlow,
  },
  {
    id: 'notificationListener',
    num: '02',
    title: 'Notification Access',
    description: 'Required on almost all phones. Arms the Accept race the instant a ride alert arrives — without this, many OEMs never click.',
    instruction: 'Settings → Apps → Special app access → Notification access → SUPER RIDEX → Allow',
    action: openNotificationListenerSettings,
    actionLabel: 'Open Notification Access',
    accentColor: colors.blue,
    accentBorder: colors.borderBlue,
    accentGlow: colors.blueGlow,
  },
  {
    id: 'overlay',
    num: '03',
    title: 'Display Over Other Apps',
    description: 'Shows a floating status indicator while you\'re inside driver apps confirming auto-clicker is running.',
    instruction: 'Settings → Apps → SUPER RIDEX → Display over other apps → Allow',
    action: openOverlaySettings,
    actionLabel: 'Open Overlay Settings',
    accentColor: colors.blue,
    accentBorder: colors.borderBlue,
    accentGlow: colors.blueGlow,
  },
  {
    id: 'battery',
    num: '04',
    title: 'Battery Optimization Exemption',
    description: 'Critical on Realme, Redmi, Vivo, Oppo: without this, Accept works for 1–3 rides or dies overnight. Also turn ON Autostart / allow background activity.',
    instruction: '1) Tap below → Unrestricted / Don\'t optimize. 2) App info → Battery → Unrestricted. 3) Autostart ON (phone settings). Without Autostart, next-day Accept often fails.',
    action: requestBatteryOptimizationExemption,
    actionLabel: 'Request Battery Exemption',
    accentColor: colors.purple,
    accentBorder: colors.borderPurple,
    accentGlow: colors.purpleGlow,
  },
];

export default function PermissionsSetupScreen({ navigation }) {
  const { status, check } = usePermissions();
  const allDone = status.accessibility && status.notificationListener && status.overlay && status.battery;

  return (
    <Screen>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />

      <View style={styles.header}>
        <View style={styles.headerIcon}>
          <Text style={styles.headerIconText}>SR</Text>
        </View>
        <View style={styles.headerText}>
          <Text style={styles.headerTitle}>Setup Required</Text>
          <Text style={styles.headerSub}>Grant all 4 permissions — needed on every phone brand</Text>
        </View>
      </View>

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>

        {STEPS.map((step, idx) => {
          const granted = status[step.id];
          return (
            <View
              key={step.id}
              style={[
                styles.card,
                granted
                  ? { borderColor: step.accentColor + '88', backgroundColor: step.accentGlow }
                  : { borderColor: colors.border },
              ]}
            >
              {/* Step badge + title */}
              <View style={styles.cardHead}>
                <View style={[
                  styles.badge,
                  granted
                    ? { backgroundColor: step.accentColor }
                    : { backgroundColor: colors.surfaceLight, borderWidth: 1, borderColor: colors.border },
                ]}>
                  <Text style={[styles.badgeText, granted && { color: colors.white }]}>
                    {granted ? '✓' : step.num}
                  </Text>
                </View>

                <View style={styles.cardHeadText}>
                  <Text style={styles.stepTitle}>{step.title}</Text>
                    <View style={[styles.chip, granted ? styles.chipOn : styles.chipOff]}>
                      <Text style={[styles.chipText, granted ? styles.textOn : styles.textOff]}>
                        {granted ? 'GRANTED' : 'REQUIRED'}
                      </Text>
                    </View>
                </View>
              </View>

              <Text style={styles.stepDesc}>{step.description}</Text>

              {step.id === 'accessibility' && needsRestrictedSettingsUnlock() && !granted && (
                <View style={styles.restrictedBox}>
                  <Text style={styles.restrictedTitle}>Don't see "Allow restricted settings"?</Text>
                  <Text style={styles.restrictedText}>
                    Android hides that option until you try to enable accessibility first. Follow this exact order:
                  </Text>
                  <Text style={styles.restrictedStep}>1. Tap "Try Enable Accessibility" below — toggle SUPER RIDEX ON</Text>
                  <Text style={styles.restrictedStep}>2. You'll see "Restricted setting" — that's normal, go back</Text>
                  <Text style={styles.restrictedStep}>3. Tap "Open App Settings" → menu → Allow restricted settings</Text>
                  <Text style={styles.restrictedStep}>4. Confirm PIN/fingerprint → return to Accessibility → turn ON again</Text>
                  <Text style={styles.restrictedNote}>
                    Still blocked? Common fixes:{'\n'}
                    • Rebuilding/reinstalling the app RESETS this — you must unlock again{'\n'}
                    • Uninstall SUPER RIDEX fully → reinstall APK by tapping the file{'\n'}
                    • Long-press app icon → App info → look for menu or "Allow restricted settings"{'\n'}
                    • Samsung: option may be on App Info page directly (scroll down){'\n'}
                    • After allowing, come back here and tap Re-check Permissions
                  </Text>
                  <TouchableOpacity
                    style={[styles.restrictedBtn, { borderColor: colors.purple, backgroundColor: colors.purpleGlow }]}
                    onPress={openAccessibilitySettings}
                    activeOpacity={0.85}
                  >
                    <Text style={[styles.restrictedBtnText, { color: colors.purpleBright }]}>
                      Try Enable Accessibility
                    </Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[styles.restrictedBtn, { borderColor: colors.warning }]}
                    onPress={openAppInfoSettings}
                    activeOpacity={0.85}
                  >
                    <Text style={[styles.restrictedBtnText, { color: colors.warning }]}>
                      Open App Settings
                    </Text>
                  </TouchableOpacity>
                </View>
              )}

              {/* Instruction */}
              <View style={[styles.instructionBox, { borderLeftColor: step.accentColor }]}>
                <Text style={[styles.instructionLabel, { color: step.accentColor }]}>HOW TO ENABLE</Text>
                <Text style={styles.instructionText}>
                  {step.id === 'accessibility'
                    ? 'Settings → Accessibility → Downloaded / Installed apps → SUPER RIDEX → ON'
                    : step.instruction}
                </Text>
              </View>

              {/* Action button */}
              {!granted && (
                <TouchableOpacity
                  style={[styles.actionBtn, { backgroundColor: step.accentColor }]}
                  onPress={step.action}
                  activeOpacity={0.85}
                >
                  <Text style={styles.actionBtnText}>
                    {step.id === 'accessibility' && needsRestrictedSettingsUnlock()
                      ? 'Step 2 — Open Accessibility →'
                      : `${step.actionLabel} →`}
                  </Text>
                </TouchableOpacity>
              )}
            </View>
          );
        })}

        {/* Re-check */}
        <TouchableOpacity style={styles.recheckBtn} onPress={check}>
          <Text style={styles.recheckText}>↻  Re-check Permissions</Text>
        </TouchableOpacity>

        {/* Continue */}
        <TouchableOpacity
          style={[
            styles.continueBtn,
            allDone
              ? { backgroundColor: colors.purple }
              : { backgroundColor: colors.surfaceLight, borderWidth: 1, borderColor: colors.border },
          ]}
          onPress={() => replaceRoot(navigation, 'Main')}
          activeOpacity={0.85}
        >
          <Text style={[styles.continueBtnText, !allDone && { color: colors.icyMuted }]}>
            {allDone ? 'All Set — Open Home' : 'Continue anyway (auto-accept may miss rides)'}
          </Text>
        </TouchableOpacity>

      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row', alignItems: 'center', gap: 14,
    paddingHorizontal: 20, paddingVertical: 20,
    borderBottomWidth: 1, borderBottomColor: colors.border,
  },
  headerIcon: {
    width: 52, height: 52, borderRadius: 12,
    backgroundColor: colors.surfaceLight,
    borderWidth: 1, borderColor: colors.border,
    alignItems: 'center', justifyContent: 'center',
  },
  headerIconText: { fontSize: 16, fontWeight: '700', color: colors.purpleBright },
  headerText:     { flex: 1 },
  headerTitle:    { fontSize: 20, fontWeight: '700', color: colors.icy },
  headerSub:      { fontSize: 13, color: colors.icyDim, marginTop: 3 },

  scroll: { padding: 20, gap: 14, paddingBottom: 40 },

  card: {
    backgroundColor: colors.surface,
    borderRadius: 14, padding: 18,
    borderWidth: 1, gap: 12,
  },
  cardHead: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  badge: {
    width: 42, height: 42, borderRadius: 21,
    alignItems: 'center', justifyContent: 'center',
  },
  badgeText: { fontSize: 14, fontWeight: '900', color: colors.icyMuted },

  cardHeadText: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 10, flexWrap: 'wrap' },
  stepTitle:    { fontSize: 16, fontWeight: '800', color: colors.icy },

  chip: {
    borderWidth: 1, borderRadius: 6,
    paddingHorizontal: 8, paddingVertical: 3,
  },
  chipOn: { backgroundColor: colors.onGreenDim, borderColor: colors.onGreen },
  chipOff: { backgroundColor: colors.offRedDim, borderColor: colors.offRed },
  chipText: { fontSize: 9, fontWeight: '900', letterSpacing: 0.8 },
  textOn: { color: colors.onGreen },
  textOff: { color: colors.offRed },

  stepDesc: { fontSize: 13, color: colors.icyDim, lineHeight: 20 },

  restrictedBox: {
    backgroundColor: colors.warningDim,
    borderRadius: 12, padding: 14, gap: 8,
    borderWidth: 1, borderColor: colors.warning + '55',
  },
  restrictedTitle: { fontSize: 14, fontWeight: '800', color: colors.warning },
  restrictedText: { fontSize: 12, color: colors.icyDim, lineHeight: 18 },
  restrictedStep: { fontSize: 12, color: colors.icy, lineHeight: 18, paddingLeft: 4 },
  restrictedNote: { fontSize: 11, color: colors.icyMuted, lineHeight: 17, marginTop: 4, fontStyle: 'italic' },
  restrictedBtn: {
    marginTop: 6, borderWidth: 1.5, borderRadius: 10,
    paddingVertical: 12, alignItems: 'center',
  },
  restrictedBtnText: { fontSize: 13, fontWeight: '800' },

  instructionBox: {
    backgroundColor: colors.surfaceLight,
    borderRadius: 10, padding: 12,
    borderLeftWidth: 3, gap: 4,
  },
  instructionLabel: { fontSize: 9, fontWeight: '900', letterSpacing: 1 },
  instructionText:  { fontSize: 12, color: colors.icyDim, lineHeight: 18 },

  actionBtn: {
    borderRadius: 10, paddingVertical: 14,
    alignItems: 'center',
  },
  actionBtnText: { color: colors.white, fontSize: 14, fontWeight: '700' },

  recheckBtn: { alignItems: 'center', paddingVertical: 12 },
  recheckText:{ color: colors.icyDim, fontSize: 14, fontWeight: '600' },

  continueBtn: {
    borderRadius: 12, paddingVertical: 18,
    alignItems: 'center',
  },
  continueBtnText: { color: colors.white, fontSize: 15, fontWeight: '700' },
});
