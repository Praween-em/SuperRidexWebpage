import React, { useState, useEffect } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, Image,
  StyleSheet, StatusBar, KeyboardAvoidingView,
  Platform, Alert, ActivityIndicator, ScrollView,
} from 'react-native';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';
import { saveUser } from '../utils/storage';
import { replaceRoot } from '../navigation/rootNavigation';
import { verifyOtpWithServer } from '../services/api';
import { initOtpWidget, isOtpConfigured, sendOtp, retryOtp, verifyOtp, OTP_CHANNEL } from '../services/otp';

/** Pure black — readable on light fills even if the OS is in dark mode. */
const INPUT_TEXT = '#000000';
const INPUT_PLACEHOLDER = '#6B7280';

export default function LoginScreen({ navigation }) {
  const [phone, setPhone] = useState('');
  const [otp, setOtp] = useState('');
  const [referralCode, setReferralCode] = useState('');
  const [step, setStep] = useState('phone');
  const [reqId, setReqId] = useState(null);
  const [sending, setSending] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [resending, setResending] = useState(false);

  useEffect(() => {
    initOtpWidget();
  }, []);

  const handleSendOtp = async () => {
    if (phone.length !== 10) return;
    if (!isOtpConfigured()) {
      Alert.alert(
        'OTP Not Configured',
        'Add EXPO_PUBLIC_MSG91_WIDGET_ID and EXPO_PUBLIC_MSG91_AUTH_TOKEN to your .env, then restart Expo.'
      );
      return;
    }
    setSending(true);
    try {
      const result = await sendOtp(phone);
      if (result.alreadyVerified && result.accessToken) {
        await finishLogin(result.accessToken);
        return;
      }
      setReqId(result.reqId);
      setOtp('');
      setStep('otp');
    } catch (err) {
      Alert.alert(
        'Could not send OTP',
        err.message || 'SMS could not be sent. Check internet, try again, or wait a minute if you hit the daily SMS limit.'
      );
    } finally {
      setSending(false);
    }
  };

  const handleResendOtp = async (channel = OTP_CHANNEL.SMS) => {
    if (!reqId) {
      await handleSendOtp();
      return;
    }
    setResending(true);
    try {
      const { reqId: id } = await retryOtp(reqId, channel);
      setReqId(id);
      const via =
        channel === OTP_CHANNEL.VOICE
          ? 'You should get a phone call with the code.'
          : channel === OTP_CHANNEL.WHATSAPP
            ? 'Check WhatsApp for the new code.'
            : 'Check your SMS for a new code. If Wi‑Fi only, turn on mobile data once.';
      Alert.alert('OTP resent', via);
    } catch (err) {
      Alert.alert('Resend failed', err.message || 'Try again');
    } finally {
      setResending(false);
    }
  };

  const finishLogin = async (accessToken) => {
    if (!accessToken) {
      throw new Error('OTP token missing. Please request a new OTP.');
    }

    const reg = await verifyOtpWithServer({
      phone,
      accessToken,
      referralCode: referralCode.trim() || undefined,
    });

    await saveUser({
      phone,
      active: !!reg.active,
      referralCode: reg.referralCode || null,
      appliedReferralCode: referralCode.trim() || null,
      subscriptionEnd: reg.subscriptionEnd || null,
      planType: reg.planType || null,
    });
    replaceRoot(navigation, 'Main');
  };

  const handleVerifyOtp = async () => {
    if (otp.length < 4) return;
    setVerifying(true);
    try {
      const { accessToken } = await verifyOtp(reqId, otp);
      await finishLogin(accessToken);
    } catch (err) {
      Alert.alert('Verification failed', err.message || 'Invalid OTP');
    } finally {
      setVerifying(false);
    }
  };

  const busy = sending || verifying || resending;

  return (
    <Screen>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      >
        <ScrollView
          contentContainerStyle={styles.scroll}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
        >
          <View style={styles.brandBlock}>
            <Image
              source={require('../../assets/superridextitle.png')}
              style={styles.brandTitle}
              resizeMode="contain"
              accessibilityLabel="SUPER RIDEX"
            />
          </View>

          <View style={styles.card}>
            <View style={styles.steps}>
              <View style={[styles.stepDot, styles.stepDotActive]} />
              <View style={[styles.stepLine, step === 'otp' && styles.stepLineActive]} />
              <View style={[styles.stepDot, step === 'otp' && styles.stepDotActive]} />
            </View>

            {step === 'phone' ? (
              <>
                <Text style={styles.cardTitle}>Welcome to Super Ridex</Text>
                <Text style={styles.cardSub}>Enter your mobile number to get a one-time SMS code</Text>

                <View style={styles.phoneRow}>
                  <View style={styles.flagBox}>
                    <Text style={styles.flagText}>🇮🇳 +91</Text>
                  </View>
                  <TextInput
                    style={styles.input}
                    placeholder="10-digit number"
                    placeholderTextColor={INPUT_PLACEHOLDER}
                    keyboardType="phone-pad"
                    keyboardAppearance="light"
                    maxLength={10}
                    value={phone}
                    onChangeText={setPhone}
                    editable={!busy}
                    cursorColor={INPUT_TEXT}
                    selectionColor={colors.purpleBright}
                    underlineColorAndroid="transparent"
                    textAlignVertical="center"
                  />
                </View>

                <Text style={styles.refLabel}>Referral Code (optional)</Text>
                <TextInput
                  style={styles.inputStandalone}
                  placeholder="e.g. SR1234ABC"
                  placeholderTextColor={INPUT_PLACEHOLDER}
                  autoCapitalize="characters"
                  keyboardAppearance="light"
                  value={referralCode}
                  onChangeText={setReferralCode}
                  editable={!busy}
                  cursorColor={INPUT_TEXT}
                  selectionColor={colors.purpleBright}
                  underlineColorAndroid="transparent"
                  textAlignVertical="center"
                />

                <TouchableOpacity
                  style={[styles.btn, (phone.length !== 10 || busy) && styles.btnDisabled]}
                  onPress={handleSendOtp}
                  disabled={phone.length !== 10 || busy}
                  activeOpacity={0.8}
                >
                  {sending ? (
                    <ActivityIndicator color={colors.white} />
                  ) : (
                    <Text style={styles.btnText}>Send OTP →</Text>
                  )}
                </TouchableOpacity>
              </>
            ) : (
              <>
                <Text style={styles.cardTitle}>Verify OTP</Text>
                <Text style={styles.cardSub}>
                  Sent to +91 {phone}{'  '}
                  <Text
                    style={styles.link}
                    onPress={() => {
                      if (busy) return;
                      setStep('phone');
                      setOtp('');
                    }}
                  >
                    Change
                  </Text>
                </Text>

                <TextInput
                  style={[styles.inputStandalone, styles.otpInput]}
                  placeholder="· · · · · ·"
                  placeholderTextColor={INPUT_PLACEHOLDER}
                  keyboardType="number-pad"
                  keyboardAppearance="light"
                  maxLength={6}
                  value={otp}
                  onChangeText={setOtp}
                  autoFocus
                  editable={!busy}
                  cursorColor={INPUT_TEXT}
                  selectionColor={colors.purpleBright}
                  underlineColorAndroid="transparent"
                  textAlignVertical="center"
                />

                <TouchableOpacity
                  style={[styles.btn, (otp.length < 4 || busy) && styles.btnDisabled]}
                  onPress={handleVerifyOtp}
                  disabled={otp.length < 4 || busy}
                  activeOpacity={0.8}
                >
                  {verifying ? (
                    <ActivityIndicator color={colors.white} />
                  ) : (
                    <Text style={styles.btnText}>Verify & Continue →</Text>
                  )}
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.resendRow}
                  onPress={() => handleResendOtp(OTP_CHANNEL.SMS)}
                  disabled={busy}
                >
                  <Text style={styles.cardSub}>Didn&apos;t receive?  </Text>
                  <Text style={styles.link}>{resending ? 'Sending…' : 'Resend SMS'}</Text>
                </TouchableOpacity>

                <View style={styles.altChannels}>
                  <TouchableOpacity
                    onPress={() => handleResendOtp(OTP_CHANNEL.VOICE)}
                    disabled={busy}
                    hitSlop={8}
                  >
                    <Text style={styles.link}>Call me (Voice OTP)</Text>
                  </TouchableOpacity>
                  <Text style={styles.cardSub}> · </Text>
                  <TouchableOpacity
                    onPress={() => handleResendOtp(OTP_CHANNEL.WHATSAPP)}
                    disabled={busy}
                    hitSlop={8}
                  >
                    <Text style={styles.link}>WhatsApp OTP</Text>
                  </TouchableOpacity>
                </View>

                <Text style={styles.carrierHint}>
                  Some carriers block silent verification on Wi‑Fi. Use mobile data, or try Voice / WhatsApp above.
                </Text>
              </>
            )}
          </View>

          <Text style={styles.terms}>
            By continuing you agree to our{' '}
            <Text style={styles.termsLink} onPress={() => navigation.navigate('Terms')}>
              Terms & Conditions
            </Text>
            {' '}and{' '}
            <Text style={styles.termsLink} onPress={() => navigation.navigate('PrivacyPolicy')}>
              Privacy Policy
            </Text>
            . SUPER RIDEX is an accessibility assistive tool for drivers who cannot reliably press Accept.
          </Text>
        </ScrollView>
      </KeyboardAvoidingView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  scroll: {
    flexGrow: 1,
    paddingHorizontal: 24,
    paddingVertical: 28,
    justifyContent: 'center',
    gap: 22,
  },

  brandBlock: { alignItems: 'center', marginBottom: 4 },
  brandTitle: {
    width: '100%',
    maxWidth: 320,
    height: 72,
  },

  card: {
    backgroundColor: colors.surface,
    borderRadius: 18,
    padding: 24,
    borderWidth: 1,
    borderColor: colors.border,
    gap: 16,
  },

  steps: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 4 },
  stepDot: {
    width: 10, height: 10, borderRadius: 5,
    backgroundColor: colors.border,
  },
  stepDotActive: { backgroundColor: colors.purple },
  stepLine: { flex: 1, height: 2, backgroundColor: colors.border, borderRadius: 2 },
  stepLineActive: { backgroundColor: colors.purple },

  cardTitle: { fontSize: 20, fontWeight: '800', color: colors.icy },
  cardSub: { fontSize: 13, color: colors.icyDim, lineHeight: 18 },

  phoneRow: { flexDirection: 'row', gap: 10 },
  flagBox: {
    backgroundColor: '#EEF3F9',
    borderRadius: 12, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: 14, justifyContent: 'center',
  },
  flagText: { color: colors.icy, fontSize: 14 },
  refLabel: { fontSize: 12, color: colors.icyDim, fontWeight: '600' },

  input: {
    flex: 1,
    backgroundColor: '#FFFFFF',
    borderRadius: 12, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: 16, paddingVertical: 14,
    color: INPUT_TEXT,
    fontSize: 16,
    fontWeight: '600',
  },
  inputStandalone: {
    width: '100%',
    backgroundColor: '#FFFFFF',
    borderRadius: 12, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: 16, paddingVertical: 14,
    color: INPUT_TEXT,
    fontSize: 16,
    fontWeight: '600',
  },
  otpInput: {
    textAlign: 'center', letterSpacing: 10,
    fontSize: 24, fontWeight: '700',
    color: INPUT_TEXT,
  },

  btn: {
    backgroundColor: colors.purple,
    borderRadius: 12, paddingVertical: 16,
    alignItems: 'center',
  },
  btnDisabled: {
    backgroundColor: colors.surfaceLight,
    borderWidth: 1, borderColor: colors.border,
  },
  btnText: { color: colors.white, fontSize: 16, fontWeight: '700' },

  resendRow: { flexDirection: 'row', justifyContent: 'center' },
  altChannels: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    alignItems: 'center',
    gap: 4,
  },
  carrierHint: {
    fontSize: 11,
    color: colors.icyMuted,
    textAlign: 'center',
    lineHeight: 16,
  },
  link: { color: colors.purpleBright, fontSize: 13, fontWeight: '600' },
  terms: { textAlign: 'center', color: colors.icyMuted, fontSize: 12, marginBottom: 8, lineHeight: 18 },
  termsLink: { color: colors.purpleBright, fontWeight: '700' },
});
