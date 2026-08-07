import React, { useState, useRef, useEffect, useCallback } from 'react';
import {
  View, Text, TouchableOpacity,
  StyleSheet, StatusBar, ScrollView,
  Animated, Alert, Linking,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import Screen from '../components/Screen';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import { usePermissions } from '../hooks/usePermissions';
import { useSubscription } from '../hooks/useSubscription';
import {
  openAccessibilitySettings,
  openAppInfoSettings,
  openNotificationListenerSettings,
  needsRestrictedSettingsUnlock,
} from '../services/permissions';
import { navigateRoot, replaceRoot } from '../navigation/rootNavigation';
import { clearUser, getStoredUser } from '../utils/storage';
import { getSettings } from '../utils/settingsStorage';
import { formatExpiryDate } from '../utils/formatDate';
import AppSwitch from '../components/AppSwitch';
import { checkEntitlement, ApiError, getSocialLinks } from '../services/api';
import {
  getServiceStatus,
  saveSettings,
  setServiceEnabled,
  setNuclearMode,
  isNativeAvailable,
} from '../services/autoclicker';

/** Offline fallback only — live URLs come from GET /socials (admin panel). */
const DEFAULT_SOCIAL_LINKS = {
  whatsapp: '',
  youtube: '',
  telegram: '',
};

const openSocialLink = async (url) => {
  if (!url) {
    Alert.alert('Link unavailable', 'This social link is not configured yet.');
    return;
  }
  try {
    await Linking.openURL(url);
  } catch {
    Alert.alert('Unable to open link', 'Please try again later.');
  }
};

const DEFAULT_PACKAGES = [
  'com.rapido.rider',
  'com.rapido.captain',
  'com.rapido.driver',
  'com.rideandhra.driverapp',
  'com.olacabs.driver',
  'com.olacabs.oladriver',
  'com.ubercab.driver',
];

export default function HomeScreen({ navigation }) {
  const { status: permStatus } = usePermissions();
  const {
    isActive,
    subscriptionEnd,
    subscription,
    loading: subLoading,
    refresh: refreshSub,
    deviceAllowed,
  } = useSubscription();

  const [enabled, setEnabled]       = useState(false);
  const [nuclearMode, setNuclear]   = useState(true);
  const [saving, setSaving]         = useState(false);
  const [socialLinks, setSocialLinks] = useState(DEFAULT_SOCIAL_LINKS);

  const accessOn = permStatus.accessibility;
  const nlsOn = !!permStatus.notificationListener;
  const pulseAnim = useRef(new Animated.Value(1)).current;

  const handleLogout = async () => {
    await clearUser();
    replaceRoot(navigation, 'Login');
  };

  const featuresAllowed = isActive && deviceAllowed;

  const shutOffAutoAccept = async () => {
    setEnabled(false);
    if (isNativeAvailable()) {
      try {
        await setServiceEnabled(false);
      } catch {
        // ignore — UI already off
      }
    }
  };

  const handleRefresh = async () => {
    try {
      await refreshSub();
      // Force-off runs via featuresAllowed effect after refresh updates isActive
      if (isNativeAvailable()) {
        const status = await getServiceStatus();
        setNuclear(status.nuclearMode !== false);
      }
    } catch (err) {
      console.warn('Refresh error:', err);
    }
  };

  useEffect(() => {
    if (!isNativeAvailable()) return;
    getServiceStatus()
      .then(async (status) => {
        setNuclear(status.nuclearMode !== false);
        if (subLoading) return;
        const wantOn = !!status.enabled && featuresAllowed;
        setEnabled(wantOn);
        // Re-assert into a11y process memory (ColorOS often keeps stale enabled=false)
        if (wantOn) {
          try {
            await setServiceEnabled(true);
          } catch {
            // ignore
          }
        }
      })
      .catch(() => {});
  }, [subLoading, featuresAllowed]);

  // Force native OFF only when plan end is past or device is blocked.
  // Never treat a bare active===false (API glitch / loading) as shutoff —
  // that was wiping Auto-accept and making zero rides click.
  useEffect(() => {
    if (subLoading) return;
    if (!subscription) return;
    const endPast = !!subscription.subscriptionEnd
      && new Date(subscription.subscriptionEnd) <= new Date();
    if (endPast || deviceAllowed === false) {
      shutOffAutoAccept();
    }
  }, [subLoading, subscription, deviceAllowed]);

  useFocusEffect(
    useCallback(() => {
      let cancelled = false;
      getSocialLinks()
        .then((data) => {
          if (cancelled || !data) return;
          setSocialLinks({
            whatsapp: data.whatsapp || '',
            youtube: data.youtube || '',
            telegram: data.telegram || '',
            support: data.support || '',
          });
        })
        .catch(() => {
          // Keep last known / empty offline
        });
      return () => { cancelled = true; };
    }, [])
  );

  const handleModeChange = async (nuclear) => {
    setNuclear(nuclear);
    if (isNativeAvailable()) {
      try {
        await setNuclearMode(nuclear);
      } catch {
        setNuclear(!nuclear);
      }
    }
  };

  const handleToggleEnabled = async (value) => {
    if (value) {
      if (!deviceAllowed) {
        Alert.alert(
          'Device Not Allowed',
          'Log out and sign in again with OTP — that moves your plan to this phone. If it still fails, use Reset devices in the admin panel.'
        );
        return;
      }
      if (!isActive) {
        Alert.alert(
          'Subscription Required',
          'You need an active plan to turn on auto-accept.',
          [
            { text: 'View Plans', onPress: () => navigateRoot(navigation, 'Plans') },
            { text: 'Cancel', style: 'cancel' },
          ]
        );
        return;
      }

      try {
        const user = await getStoredUser();
        if (!user?.phone) {
          Alert.alert('Login Required', 'Please log in again.');
          return;
        }
        const ent = await checkEntitlement(user.phone);
        if (!ent.active) {
          Alert.alert(
            'Subscription Required',
            'Your plan is not active. Please renew to use auto-accept.',
            [
              { text: 'View Plans', onPress: () => navigateRoot(navigation, 'Plans') },
              { text: 'Cancel', style: 'cancel' },
            ]
          );
          await refreshSub();
          return;
        }
        if (ent.deviceAllowed === false) {
          Alert.alert(
            'Device Not Allowed',
            'Log out and sign in again with OTP — that moves your plan to this phone. If it still fails, use Reset devices in the admin panel.'
          );
          return;
        }
      } catch (err) {
        if (err instanceof ApiError && err.code === 'DEVICE_LIMIT') {
          Alert.alert('Device Not Allowed', err.message);
          return;
        }
        if (err instanceof ApiError && err.code === 'BLOCKED') {
          Alert.alert('Account Blocked', err.message);
          return;
        }
        // Offline grace: local isActive already verified above
      }
    }

    if (value && !accessOn) {
      const buttons = needsRestrictedSettingsUnlock()
        ? [
            { text: 'Allow Restricted Settings', onPress: openAppInfoSettings },
            { text: 'Open Accessibility', onPress: openAccessibilitySettings },
            { text: 'Cancel', style: 'cancel' },
          ]
        : [
            { text: 'Open Settings', onPress: openAccessibilitySettings },
            { text: 'Cancel', style: 'cancel' },
          ];
      Alert.alert(
        'Accessibility Required',
        needsRestrictedSettingsUnlock()
          ? 'If you see "Restricted setting", first open App Settings → ⋮ → Allow restricted settings. Then enable SUPER RIDEX in Accessibility.'
          : 'Enable SUPER RIDEX in Settings → Accessibility before starting auto-accept.',
        buttons
      );
      return;
    }
    if (value && accessOn && !nlsOn) {
      Alert.alert(
        'Enable Notification Access',
        'For best speed, turn ON SUPER RIDEX Alerts in Notification access.',
        [
          { text: 'Open Notification Access', onPress: openNotificationListenerSettings },
          { text: 'Continue anyway', style: 'cancel' },
        ]
      );
    }
    setEnabled(value);
    if (isNativeAvailable()) {
      try {
        await setServiceEnabled(value);
      } catch {
        setEnabled(!value);
      }
    }
  };

  const handleSave = async () => {
    if (!isNativeAvailable()) {
      Alert.alert('Android Only', 'Native auto-clicker runs on a built Android app.');
      return;
    }
    if (subLoading) {
      Alert.alert('Please wait', 'Subscription is still loading — try Save again in a moment.');
      return;
    }
    setSaving(true);
    try {
      const settings = await getSettings();
      const nextEnabled = featuresAllowed ? enabled : false;
      await saveSettings({
        enabled: nextEnabled,
        nuclearMode,
        delayMs: nuclearMode ? 0 : (settings.delayMs ?? 0),
        monitoredPackages: DEFAULT_PACKAGES,
      });
      Alert.alert('Settings Saved', 'Your settings have been saved successfully.');
    } catch (err) {
      Alert.alert('Error', err.message || 'Could not save settings.');
    } finally {
      setSaving(false);
    }
  };

  useEffect(() => {
    if (enabled) {
      Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, { toValue: 1.2, duration: 900, useNativeDriver: true }),
          Animated.timing(pulseAnim, { toValue: 1, duration: 900, useNativeDriver: true }),
        ])
      ).start();
    } else {
      pulseAnim.stopAnimation();
      pulseAnim.setValue(1);
    }
  }, [enabled]);

  return (
    <Screen edges={['top']}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />

      <View style={styles.nav}>
        <TouchableOpacity style={styles.navBtn} onPress={handleRefresh} disabled={subLoading}>
          {subLoading ? (
            <Ionicons name="hourglass-outline" size={18} color={colors.blue} />
          ) : (
            <Ionicons name="refresh-outline" size={18} color={colors.blue} />
          )}
          <Text style={[styles.navLabel, { color: colors.blue }]}>Refresh</Text>
        </TouchableOpacity>

        <View style={styles.navCenter}>
          <Text style={styles.navTitle}>SUPER RIDEX</Text>
        </View>

        <TouchableOpacity style={styles.navBtn} onPress={handleLogout}>
          <Ionicons name="log-out-outline" size={18} color={colors.offRed} />
          <Text style={[styles.navLabel, { color: colors.offRed }]}>Logout</Text>
        </TouchableOpacity>
      </View>

      {!subLoading && !isActive && (
        <TouchableOpacity
          style={styles.paywallBanner}
          onPress={() => navigateRoot(navigation, 'Plans')}
          activeOpacity={0.85}
        >
          <Text style={styles.paywallText}>No active plan — tap to subscribe</Text>
        </TouchableOpacity>
      )}

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>

        {/* Subscription — expire date only */}
        <View style={[styles.expiryCard, isActive ? styles.expiryActive : styles.expiryExpired]}>
          <Text style={styles.expiryLabel}>EXPIRES</Text>
          <Text style={styles.expiryDate}>{formatExpiryDate(subscriptionEnd)}</Text>
          <TouchableOpacity
            style={styles.renewBtn}
            onPress={() => navigateRoot(navigation, 'Plans')}
            activeOpacity={0.85}
          >
            <Text style={styles.renewBtnText}>Renew Plan →</Text>
          </TouchableOpacity>
        </View>

        {/* Master toggle */}
        <View style={[styles.masterCard, enabled ? styles.masterCardOn : styles.masterCardOff]}>
          <Animated.View style={[styles.orb, enabled ? styles.orbOn : styles.orbOff, { transform: [{ scale: pulseAnim }] }]}>
            <Ionicons
              name={enabled ? 'checkmark-circle' : 'power-outline'}
              size={22}
              color={enabled ? colors.onGreen : colors.offRed}
            />
          </Animated.View>
          <View style={styles.masterText}>
            <Text style={[styles.masterStatus, enabled ? styles.textOn : styles.textOff]}>
              {enabled ? 'AUTO-ACCEPT ON' : 'AUTO-ACCEPT OFF'}
            </Text>
            <Text style={styles.masterSub}>
              {enabled ? 'Auto-accept running' : 'Toggle to start monitoring'}
            </Text>
          </View>
          <AppSwitch
            value={enabled}
            onValueChange={handleToggleEnabled}
            disabled={!featuresAllowed && !enabled}
          />
        </View>

        {/* Mode row — Nuclear vs Standard */}
        <View style={styles.modeRow}>
          <TouchableOpacity
            style={[styles.modePill, nuclearMode ? styles.modePillNuclear : styles.modePillIdle]}
            onPress={() => handleModeChange(true)}
            activeOpacity={0.85}
          >
            <Ionicons name="flash-outline" size={16} color={nuclearMode ? colors.white : colors.icyMuted} />
            <Text style={[styles.modePillText, nuclearMode && styles.modePillTextOn]}>Nuclear</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.modePill, !nuclearMode ? styles.modePillStandard : styles.modePillIdle]}
            onPress={() => handleModeChange(false)}
            activeOpacity={0.85}
          >
            <Ionicons name="shield-checkmark-outline" size={16} color={!nuclearMode ? colors.white : colors.icyMuted} />
            <Text style={[styles.modePillText, !nuclearMode && styles.modePillTextOn]}>Standard</Text>
          </TouchableOpacity>
        </View>
        <Text style={styles.modeHint}>
          {nuclearMode
            ? 'Nuclear — fastest auto-accept (0 ms delay).'
            : 'Standard — slight delay before accept.'}
        </Text>

        <View style={styles.modeRow}>
          <TouchableOpacity
            style={[styles.accessPill, accessOn ? styles.accessOn : styles.accessOff]}
            onPress={openAccessibilitySettings}
            activeOpacity={0.8}
          >
            <Ionicons
              name="accessibility-outline"
              size={16}
              color={accessOn ? colors.onGreen : colors.offRed}
            />
            <Text style={[styles.accessPillText, accessOn ? styles.textOn : styles.textOff]}>
              A11y {accessOn ? 'ON' : 'OFF'}
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.accessPill, nlsOn ? styles.accessOn : styles.accessOff]}
            onPress={openNotificationListenerSettings}
            activeOpacity={0.8}
          >
            <Ionicons
              name="notifications-outline"
              size={16}
              color={nlsOn ? colors.onGreen : colors.offRed}
            />
            <Text style={[styles.accessPillText, nlsOn ? styles.textOn : styles.textOff]}>
              Notif {nlsOn ? 'ON' : 'OFF'}
            </Text>
          </TouchableOpacity>
        </View>

        <TouchableOpacity
          style={[styles.saveBtn, saving && { opacity: 0.6 }]}
          activeOpacity={0.85}
          onPress={handleSave}
          disabled={saving}
        >
          <Text style={styles.saveBtnText}>{saving ? 'SAVING…' : 'SAVE SETTINGS'}</Text>
        </TouchableOpacity>

        <View style={styles.helpSocialCard}>
          {!!socialLinks.whatsapp && (
            <TouchableOpacity
              style={styles.helpBtn}
              onPress={() => openSocialLink(socialLinks.whatsapp)}
              activeOpacity={0.75}
            >
              <Ionicons name="logo-whatsapp" size={16} color="#25D366" />
              <Text style={styles.helpText}>Need help?</Text>
            </TouchableOpacity>
          )}

          <View style={styles.socialsDivider} />

          <Text style={styles.socialsLabel}>SOCIALS</Text>
          <View style={styles.socialsRow}>
            {!!socialLinks.youtube && (
              <TouchableOpacity
                style={styles.socialBtn}
                onPress={() => openSocialLink(socialLinks.youtube)}
                activeOpacity={0.75}
              >
                <Ionicons name="logo-youtube" size={22} color="#FF0000" />
                <Text style={styles.socialBtnText}>YouTube</Text>
              </TouchableOpacity>
            )}
            {!!socialLinks.telegram && (
              <TouchableOpacity
                style={styles.socialBtn}
                onPress={() => openSocialLink(socialLinks.telegram)}
                activeOpacity={0.75}
              >
                <Ionicons name="paper-plane" size={22} color="#229ED9" />
                <Text style={styles.socialBtnText}>Telegram</Text>
              </TouchableOpacity>
            )}
          </View>
        </View>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  nav: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginHorizontal: 16, marginTop: 8, marginBottom: 4,
    paddingHorizontal: 16, paddingVertical: 14,
    backgroundColor: colors.surface, borderRadius: 16,
    borderWidth: 1, borderColor: colors.border,
  },
  navCenter: { flex: 1, flexDirection: 'row', justifyContent: 'center', alignItems: 'center' },
  navTitle: { fontSize: 20, fontWeight: '800', color: colors.purple, letterSpacing: 0.5 },
  navBtn: { alignItems: 'center', minWidth: 52 },
  navIcon: { fontSize: 18 },
  navLabel: { fontSize: 10, marginTop: 2, fontWeight: '600' },

  paywallBanner: {
    marginHorizontal: 16, marginBottom: 8, padding: 12,
    backgroundColor: colors.warningDim, borderRadius: 12,
    borderWidth: 1, borderColor: colors.warning + '44',
  },
  paywallText: { color: colors.warning, fontSize: 13, fontWeight: '600', textAlign: 'center' },

  scroll: { paddingHorizontal: 16, paddingTop: 12, paddingBottom: 32, gap: 14 },

  expiryCard: {
    borderRadius: 14, padding: 18, gap: 8,
    borderWidth: 1,
  },
  expiryActive: { backgroundColor: colors.onGreenDim, borderColor: colors.onGreen + '44' },
  expiryExpired: { backgroundColor: colors.offRedDim, borderColor: colors.offRed + '44' },
  expiryLabel: { fontSize: 11, fontWeight: '700', color: colors.icyMuted, letterSpacing: 1 },
  expiryDate: { fontSize: 22, fontWeight: '700', color: colors.icy },
  textOn: { color: colors.onGreen },
  textOff: { color: colors.offRed },
  renewBtn: {
    backgroundColor: colors.purple, borderRadius: 10,
    paddingVertical: 14, alignItems: 'center', marginTop: 4,
  },
  renewBtnText: { color: colors.white, fontSize: 14, fontWeight: '700' },

  masterCard: {
    flexDirection: 'row', alignItems: 'center', gap: 14,
    borderRadius: 14, padding: 18, borderWidth: 1,
  },
  masterCardOn: { backgroundColor: colors.onGreenDim, borderColor: colors.onGreen + '55' },
  masterCardOff: { backgroundColor: colors.offRedDim, borderColor: colors.offRed + '55' },
  orb: {
    width: 48, height: 48, borderRadius: 24,
    alignItems: 'center', justifyContent: 'center', borderWidth: 1,
  },
  orbOn: { backgroundColor: colors.onGreenDim, borderColor: colors.onGreen },
  orbOff: { backgroundColor: colors.offRedDim, borderColor: colors.offRed },
  orbIcon: { fontSize: 22 },
  masterText: { flex: 1 },
  masterStatus: { fontSize: 14, fontWeight: '700', letterSpacing: 0.3 },
  masterSub: { fontSize: 12, color: colors.icyMuted, marginTop: 3 },

  modeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  modePill: {
    flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    gap: 8, paddingVertical: 12, borderRadius: 10, borderWidth: 1,
    minWidth: '30%',
  },
  modePillNuclear: { backgroundColor: colors.purple, borderColor: colors.purple },
  modePillStandard: { backgroundColor: colors.blue, borderColor: colors.blue },
  modePillIdle: { backgroundColor: colors.surface, borderColor: colors.border },
  modePillText: { fontSize: 14, fontWeight: '600', color: colors.icyMuted },
  modePillTextOn: { color: colors.white },
  modeHint: { fontSize: 12, color: colors.icyMuted, marginTop: -4, marginBottom: 4, paddingHorizontal: 4 },
  accessPill: {
    flexGrow: 1, flexBasis: '30%', flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    gap: 6, paddingVertical: 12, paddingHorizontal: 8, borderRadius: 10, borderWidth: 1,
  },
  accessOn: { backgroundColor: colors.onGreenDim, borderColor: colors.onGreen + '55' },
  accessOff: { backgroundColor: colors.offRedDim, borderColor: colors.offRed + '55' },
  accessPillIcon: { fontSize: 16 },
  accessPillText: { fontSize: 12, fontWeight: '700' },

  saveBtn: {
    backgroundColor: colors.purple, borderRadius: 12, paddingVertical: 18,
    alignItems: 'center',
  },
  saveBtnText: { color: colors.white, fontSize: 15, fontWeight: '700', letterSpacing: 0.8 },

  helpSocialCard: {
    padding: 14,
    backgroundColor: colors.surface, borderRadius: 12, borderWidth: 1, borderColor: colors.border,
    gap: 10,
  },
  helpBtn: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, paddingVertical: 4 },
  helpIcon: { fontSize: 14 },
  helpText: { color: colors.icy, fontSize: 13, fontWeight: '700' },
  socialsDivider: { height: 1, backgroundColor: colors.border, marginVertical: 2 },
  socialsLabel: {
    fontSize: 10, fontWeight: '800', color: colors.icyMuted,
    letterSpacing: 1.2, textAlign: 'center',
  },
  socialsRow: { flexDirection: 'row', justifyContent: 'center', gap: 12 },
  socialBtn: {
    flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    gap: 8, paddingVertical: 10, borderRadius: 10,
    backgroundColor: colors.surfaceLight, borderWidth: 1, borderColor: colors.border,
  },
  socialBtnText: { fontSize: 12, fontWeight: '700', color: colors.icy },
});
