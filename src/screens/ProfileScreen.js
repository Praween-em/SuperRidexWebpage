import React, { useState, useEffect } from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView,
  TouchableOpacity, Alert, Share,
} from 'react-native';
import Screen from '../components/Screen';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import { replaceRoot } from '../navigation/rootNavigation';
import { getStoredUser, clearUser } from '../utils/storage';
import { useSubscription } from '../hooks/useSubscription';
import { useReferral } from '../hooks/useReferral';
import { formatExpiryDate } from '../utils/formatDate';

const REFERRAL_GOAL = 10;

export default function ProfileScreen({ navigation }) {
  const { planType, subscriptionEnd, daysRemaining, isActive } = useSubscription();
  const {
    referralCode,
    totalReferrals,
    paidReferrals,
    referralRewardsEarned,
    progress,
    remaining,
    loading: refLoading,
    refresh,
  } = useReferral();

  const [phone, setPhone] = useState('');

  useEffect(() => {
    getStoredUser().then(u => setPhone(u?.phone || ''));
  }, []);

  const handleCopyCode = async () => {
    const apkUrl = 'https://www.superridex.in/downloads/superridex.apk';
    const message =
      `Join SUPER RIDEX! Use my referral code ${referralCode} when you sign up.\n`
      + `Download the app: ${apkUrl}`;
    try {
      await Share.share({ message, url: apkUrl });
    } catch {
      Alert.alert('Your Referral Code', referralCode);
    }
  };

  const handleLogout = async () => {
    await clearUser();
    replaceRoot(navigation, 'Login');
  };

  const progressPct = Math.min(100, (progress / REFERRAL_GOAL) * 100);

  return (
    <Screen edges={['top']}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />
      <View style={styles.header}>
        <Text style={styles.headerTitle}>My Profile</Text>
      </View>

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <View style={styles.userCard}>
          <View style={styles.avatarRow}>
            <View style={styles.avatar}>
              <Ionicons name="person" size={28} color={colors.purpleBright} />
            </View>
            <View style={styles.userMeta}>
              <Text style={styles.userName}>Driver</Text>
              <Text style={styles.userPhone}>{phone ? `+91 ${phone}` : '—'}</Text>
            </View>
            <View style={[styles.statusBadge, isActive ? styles.badgeOn : styles.badgeOff]}>
              <Text style={[styles.statusBadgeText, isActive ? styles.textOn : styles.textOff]}>
                {isActive ? 'ON' : 'OFF'}
              </Text>
            </View>
          </View>

          <View style={styles.divider} />

          {[
            { label: 'Phone', value: phone || '—' },
            { label: 'Plan', value: planType === 'quarterly' ? '3 Months' : planType === 'monthly' ? '1 Month' : '—' },
            { label: 'Plan Expiry', value: formatExpiryDate(subscriptionEnd) },
            {
              label: 'Days Remaining',
              value: isActive ? `${daysRemaining} days` : 'Expired',
              valueColor: isActive ? colors.onGreen : colors.offRed,
            },
          ].map((row, i) => (
            <View key={i} style={styles.detailRow}>
              <Text style={styles.detailLabel}>{row.label}</Text>
              <Text style={[styles.detailValue, row.valueColor && { color: row.valueColor }]}>
                {row.value}
              </Text>
            </View>
          ))}
        </View>

        {/* Referral Program */}
        <View style={styles.referralHeader}>
          <View style={[styles.sectionBar, { backgroundColor: colors.purple }]} />
          <Text style={[styles.sectionTitle, { color: colors.icy }]}>Referral Program</Text>
        </View>

        <View style={styles.referralCard}>
          <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: 8 }}>
            <Ionicons name="gift-outline" size={18} color={colors.onGreen} style={{ marginTop: 2 }} />
            <Text style={[styles.referralReward, { flex: 1 }]}>
              Refer {REFERRAL_GOAL} people who pay for at least 1 month → get <Text style={styles.rewardHighlight}>1 month FREE</Text>
            </Text>
          </View>

          <View style={styles.inviteRow}>
            <Text style={styles.inviteLabel}>Your Invite Code</Text>
            <TouchableOpacity style={styles.inviteCodeBtn} onPress={handleCopyCode} activeOpacity={0.85}>
              <Text style={styles.inviteCode}>{refLoading ? '…' : referralCode}</Text>
              <Text style={styles.copyIcon}>Share</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.progressWrap}>
            <View style={styles.progressBarBg}>
              <View style={[styles.progressBarFill, { width: `${progressPct}%` }]} />
            </View>
            <Text style={styles.progressText}>
              {progress} / {REFERRAL_GOAL} paid referrals toward next free month
              {remaining > 0 ? ` · ${remaining} more needed` : ' · Reward unlocked!'}
            </Text>
          </View>

          <View style={styles.divider} />

          <View style={styles.refStats}>
            <View style={styles.refStat}>
              <Text style={styles.refStatValue}>{totalReferrals}</Text>
              <Text style={styles.refStatLabel}>Total Invited</Text>
            </View>
            <View style={styles.refDivider} />
            <View style={styles.refStat}>
              <Text style={[styles.refStatValue, { color: colors.onGreen }]}>{paidReferrals}</Text>
              <Text style={styles.refStatLabel}>Paid (≥1 mo)</Text>
            </View>
            <View style={styles.refDivider} />
            <View style={styles.refStat}>
              <Text style={[styles.refStatValue, { color: colors.purpleBright }]}>{referralRewardsEarned}</Text>
              <Text style={styles.refStatLabel}>Free Months Won</Text>
            </View>
          </View>

          <Text style={styles.refRules}>
            • Only counts when referred user pays 1-month or 3-month plan{'\n'}
            • Each referred user counts once{'\n'}
            • Free month added automatically when you hit {REFERRAL_GOAL} paid referrals
          </Text>

          <TouchableOpacity style={[styles.refreshBtn, { flexDirection: 'row', alignItems: 'center', gap: 6 }]} onPress={refresh}>
            <Ionicons name="refresh-outline" size={14} color={colors.blue} />
            <Text style={styles.refreshText}>Refresh referral stats</Text>
          </TouchableOpacity>
        </View>

        <TouchableOpacity style={styles.logoutBtn} onPress={handleLogout} activeOpacity={0.85}>
          <Text style={styles.logoutText}>LOGOUT SECURELY</Text>
        </TouchableOpacity>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { paddingHorizontal: 20, paddingVertical: 14, borderBottomWidth: 1, borderBottomColor: colors.border, alignItems: 'center' },
  headerTitle: { fontSize: 18, fontWeight: '800', color: colors.icy },
  scroll: { padding: 20, gap: 16, paddingBottom: 40 },

  userCard: {
    backgroundColor: colors.surface, borderRadius: 14, padding: 20,
    borderWidth: 1, borderColor: colors.border, gap: 12,
  },
  avatarRow: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  avatar: {
    width: 60, height: 60, borderRadius: 30, backgroundColor: colors.surfaceLight,
    borderWidth: 1, borderColor: colors.border, alignItems: 'center', justifyContent: 'center',
  },
  avatarGlyph: { fontSize: 28 },
  userMeta: { flex: 1 },
  userName: { fontSize: 20, fontWeight: '800', color: colors.icy },
  userPhone: { fontSize: 13, color: colors.icyDim, marginTop: 3 },
  statusBadge: { borderRadius: 8, paddingHorizontal: 10, paddingVertical: 5 },
  badgeOn: { backgroundColor: colors.onGreenDim, borderWidth: 1, borderColor: colors.onGreen },
  badgeOff: { backgroundColor: colors.offRedDim, borderWidth: 1, borderColor: colors.offRed },
  statusBadgeText: { fontSize: 11, fontWeight: '900', letterSpacing: 1 },
  textOn: { color: colors.onGreen },
  textOff: { color: colors.offRed },
  divider: { height: 1, backgroundColor: colors.border },
  detailRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  detailLabel: { fontSize: 14, color: colors.icyDim },
  detailValue: { fontSize: 14, fontWeight: '700', color: colors.icy },

  referralHeader: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  sectionBar: { width: 4, height: 20, borderRadius: 2 },
  sectionTitle: { fontSize: 14, fontWeight: '700', letterSpacing: 0.5 },

  referralCard: {
    backgroundColor: colors.surface, borderRadius: 14, padding: 20,
    borderWidth: 1, borderColor: colors.border, gap: 14,
  },
  referralReward: { fontSize: 14, color: colors.icyDim, lineHeight: 22 },
  rewardHighlight: { color: colors.onGreen, fontWeight: '900' },
  inviteRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  inviteLabel: { fontSize: 14, color: colors.icyDim },
  inviteCodeBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 8,
    backgroundColor: colors.purple, paddingHorizontal: 16, paddingVertical: 12, borderRadius: 12,
  },
  inviteCode: { fontSize: 17, fontWeight: '900', color: colors.white, letterSpacing: 2 },
  copyIcon: { fontSize: 12, color: colors.white, fontWeight: '700' },

  progressWrap: { gap: 8 },
  progressBarBg: { height: 10, backgroundColor: colors.border, borderRadius: 5, overflow: 'hidden' },
  progressBarFill: { height: 10, backgroundColor: colors.onGreen, borderRadius: 5 },
  progressText: { fontSize: 12, color: colors.icyDim },

  refStats: { flexDirection: 'row', alignItems: 'center' },
  refStat: { flex: 1, alignItems: 'center', gap: 4 },
  refStatValue: { fontSize: 28, fontWeight: '900', color: colors.icy },
  refStatLabel: { fontSize: 11, color: colors.icyDim, textAlign: 'center' },
  refDivider: { width: 1, height: 40, backgroundColor: colors.border },
  refRules: { fontSize: 12, color: colors.icyMuted, lineHeight: 20 },
  refreshBtn: { alignItems: 'center', paddingVertical: 8 },
  refreshText: { color: colors.blue, fontSize: 13, fontWeight: '600' },

  logoutBtn: {
    borderWidth: 1.5, borderColor: colors.offRed, borderRadius: 14,
    paddingVertical: 16, alignItems: 'center', backgroundColor: colors.offRedDim,
  },
  logoutText: { color: colors.offRed, fontSize: 14, fontWeight: '900', letterSpacing: 1.5 },
});
