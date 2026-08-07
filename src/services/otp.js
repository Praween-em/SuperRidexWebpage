/**
 * otp.js — MSG91 OTP Widget (client-side SDK)
 *
 * OTP only: initializeWidget / sendOTP / retryOTP / verifyOTP.
 * Do not import BiometricAuth from this package — not used in this app.
 *
 * Requires EXPO_PUBLIC_MSG91_WIDGET_ID + EXPO_PUBLIC_MSG91_AUTH_TOKEN
 * Enable "Mobile Integration" on the MSG91 widget in the dashboard.
 *
 * Channel codes (retryOTP): SMS=11, VOICE=4, WHATSAPP=12, EMAIL=3
 *
 * MSG91 response shapes (from SDK types):
 *   sendOTP success  → { type: 'success', message: '<reqId>' }
 *   verifyOTP success → { type: 'success', 'access-token': '<jwt>' }
 *                       (some widgets also put the JWT in message)
 */

import { OTPWidget } from '@msg91comm/sendotp-react-native';

const WIDGET_ID = process.env.EXPO_PUBLIC_MSG91_WIDGET_ID || '';
const AUTH_TOKEN = process.env.EXPO_PUBLIC_MSG91_AUTH_TOKEN || '';

/** MSG91 retry channel codes */
export const OTP_CHANNEL = {
  SMS: 11,
  VOICE: 4,
  WHATSAPP: 12,
  EMAIL: 3,
};

let initialized = false;

export function isOtpConfigured() {
  return !!(WIDGET_ID && AUTH_TOKEN);
}

export function initOtpWidget() {
  if (!isOtpConfigured()) {
    console.warn('[otp] MSG91 widgetId / authToken not set in env');
    return false;
  }
  if (initialized) return true;
  OTPWidget.initializeWidget(String(WIDGET_ID), String(AUTH_TOKEN));
  initialized = true;
  return true;
}

/**
 * Normalize to Indian mobile 10 digits starting 6–9.
 * Rejects landlines / bad series that MSG91 + carriers often block.
 */
export function normalizeIndianMobile10(phoneInput) {
  let digits = String(phoneInput || '').replace(/\D/g, '');
  if (digits.length === 12 && digits.startsWith('91')) digits = digits.slice(2);
  if (digits.length === 11 && digits.startsWith('0')) digits = digits.slice(1);
  if (digits.length > 10 && digits.startsWith('91')) digits = digits.slice(-10);
  if (!/^[6-9]\d{9}$/.test(digits)) return null;
  return digits;
}

/** India mobile → identifier without + (e.g. 919876543210) */
export function toOtpIdentifier(phone10) {
  const ten = normalizeIndianMobile10(phone10);
  if (!ten) return String(phone10 || '').replace(/\D/g, '');
  return `91${ten}`;
}

function looksLikeJwt(value) {
  if (typeof value !== 'string') return false;
  const parts = value.split('.');
  return parts.length >= 3 && value.length > 40;
}

function extractReqId(response) {
  if (!response) return null;
  if (typeof response.message === 'string' && !looksLikeJwt(response.message)) {
    return response.message;
  }
  return (
    response.reqId ||
    response.requestId ||
    response.message?.reqId ||
    null
  );
}

/**
 * MSG91 puts the JWT on `access-token`, or as a JWT string in `message` (verify only).
 * Do NOT treat every success `message` as a token — sendOTP stores reqId in `message`.
 */
function extractAccessToken(response) {
  if (!response) return null;

  const candidates = [
    response['access-token'],
    response.access_token,
    response.accessToken,
    response.token,
    response.data?.['access-token'],
    response.data?.accessToken,
    typeof response.message === 'object' && response.message
      ? (response.message['access-token'] || response.message.accessToken)
      : null,
  ];

  for (const c of candidates) {
    if (typeof c === 'string' && c.trim().length > 10) return c.trim();
  }

  // verifyOTP success often returns the JWT as message (must look like JWT, not a reqId)
  if (typeof response.message === 'string' && looksLikeJwt(response.message)) {
    return response.message.trim();
  }

  return null;
}

function isSuccess(response) {
  if (!response) return false;
  if (response.type === 'success' || response.success === true) return true;
  if (response.type === 'error' || response.success === false) return false;
  if (extractAccessToken(response) || extractReqId(response)) return true;
  return false;
}

function errorMessage(response, fallback) {
  if (!response) return fallback;
  if (typeof response.message === 'string' && !looksLikeJwt(response.message)) {
    return response.message;
  }
  if (response.message?.message) return response.message.message;
  if (response.error) return String(response.error);
  return fallback;
}

/** Map MSG91 / carrier errors into actionable user text. */
function friendlyOtpError(raw, fallback) {
  const msg = String(raw || fallback || 'Failed to send OTP');
  const lower = msg.toLowerCase();

  if (/dlt|template|sender.?id| entiti/i.test(lower)) {
    return 'SMS blocked by telecom (DLT). Contact support — template/sender ID may need approval.';
  }
  if (/blocked|blacklist|not allowed|restricted|denied/i.test(lower)) {
    return 'This number or carrier is blocked from OTP right now. Try Voice OTP, WhatsApp, or another SIM.';
  }
  if (/invalid.*(mobile|phone|number|identifier)/i.test(lower)) {
    return 'Enter a valid Indian mobile number (10 digits starting with 6–9).';
  }
  if (/limit|quota|exceed|balance|credit|insufficient/i.test(lower)) {
    return 'OTP SMS limit reached for today. Try Voice OTP or try again later.';
  }
  if (/network|timeout|timed out|fetch|internet|failed to connect/i.test(lower)) {
    return 'Network error sending OTP. Turn on mobile data (not only Wi‑Fi) and try again — some carriers need data for verification.';
  }
  if (/invisible|silent|sna|carrier/i.test(lower)) {
    return 'Carrier silent verification failed. Tap Resend SMS or try Voice OTP.';
  }
  return msg;
}

function withTimeout(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(
      () => reject(new Error(`${label} timed out. Check your signal and try again.`)),
      ms
    );
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/**
 * Best-effort force SMS after send — helps when Invisible OTP "succeeds"
 * on Wi‑Fi / unsupported carriers but SMS never arrives.
 */
async function forceSmsFallback(reqId) {
  if (!reqId) return;
  try {
    await withTimeout(
      OTPWidget.retryOTP({ reqId, retryChannel: OTP_CHANNEL.SMS }),
      15000,
      'SMS fallback'
    );
  } catch {
    // Ignore — primary send may already have queued SMS
  }
}

export async function sendOtp(phone10) {
  if (!initOtpWidget()) {
    throw new Error('OTP is not configured. Add MSG91 widget ID and auth token.');
  }
  const ten = normalizeIndianMobile10(phone10);
  if (!ten) {
    throw new Error('Enter a valid 10-digit Indian mobile number (starts with 6–9)');
  }
  const identifier = `91${ten}`;

  let response;
  try {
    response = await withTimeout(
      OTPWidget.sendOTP({ identifier }),
      20000,
      'Sending OTP'
    );
  } catch (err) {
    throw new Error(friendlyOtpError(err?.message, 'Failed to send OTP'));
  }
  if (__DEV__) {
    console.log('[otp] sendOTP type=', response?.type, 'messageLen=', String(response?.message || '').length);
  }

  // Invisible verification may return a real JWT access-token immediately
  const earlyToken = extractAccessToken(response);
  if (earlyToken && looksLikeJwt(earlyToken)) {
    return { reqId: extractReqId(response), accessToken: earlyToken, response, alreadyVerified: true };
  }

  if (!isSuccess(response)) {
    throw new Error(friendlyOtpError(errorMessage(response, 'Failed to send OTP')));
  }
  const reqId = extractReqId(response);
  if (!reqId) {
    throw new Error('OTP sent but request id missing. Check MSG91 widget config.');
  }

  // Force SMS path so Airtel/Vi/BSNL (and Wi‑Fi-only) still get a text
  // when Silent Network Auth does nothing useful.
  await forceSmsFallback(reqId);

  return { reqId, accessToken: null, response, alreadyVerified: false };
}

/**
 * @param {string} reqId
 * @param {number} [retryChannel] — OTP_CHANNEL.SMS | VOICE | WHATSAPP
 */
export async function retryOtp(reqId, retryChannel = OTP_CHANNEL.SMS) {
  if (!initOtpWidget()) {
    throw new Error('OTP is not configured');
  }
  if (!reqId) throw new Error('Missing OTP request id');
  const body = { reqId };
  if (retryChannel != null) body.retryChannel = retryChannel;

  let response;
  try {
    response = await withTimeout(OTPWidget.retryOTP(body), 20000, 'Resending OTP');
  } catch (err) {
    throw new Error(friendlyOtpError(err?.message, 'Failed to resend OTP'));
  }
  if (!isSuccess(response)) {
    throw new Error(friendlyOtpError(errorMessage(response, 'Failed to resend OTP')));
  }
  return { reqId: extractReqId(response) || reqId, response, channel: retryChannel };
}

export async function verifyOtp(reqId, otp) {
  if (!initOtpWidget()) {
    throw new Error('OTP is not configured');
  }
  if (!reqId) throw new Error('Missing OTP request id — send OTP again');
  const code = String(otp || '').trim();
  if (code.length < 4) throw new Error('Enter the OTP you received');

  let response;
  try {
    response = await withTimeout(
      OTPWidget.verifyOTP({ reqId, otp: code }),
      20000,
      'Verifying OTP'
    );
  } catch (err) {
    throw new Error(friendlyOtpError(err?.message, 'Invalid OTP'));
  }
  if (__DEV__) {
    console.log('[otp] verifyOTP raw:', JSON.stringify(response));
  }

  if (!isSuccess(response)) {
    throw new Error(friendlyOtpError(errorMessage(response, 'Invalid OTP')));
  }

  const accessToken = extractAccessToken(response);
  if (!accessToken) {
    throw new Error(
      'OTP verified but access token missing from MSG91 response. ' +
      'Ensure Mobile Integration is enabled on the widget.'
    );
  }

  return { accessToken, response };
}
