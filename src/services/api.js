/**
 * api.js — HTTP client for SUPER RIDEX backend (Postgres / Railway)
 */

import Constants from 'expo-constants';
import { getDeviceId, getDeviceLabel } from '../utils/deviceId';

function resolveBaseUrl() {
  const fromExtra = Constants.expoConfig?.extra?.apiUrl;
  const fromEnv = process.env.EXPO_PUBLIC_API_URL;
  let url = String(fromExtra || fromEnv || '').trim();
  // Guard against .env mistakes like: EXPO_PUBLIC_API_URL=EXPO_PUBLIC_API_URL=https://...
  url = url.replace(/^EXPO_PUBLIC_API_URL=/i, '').trim();
  url = url.replace(/\/$/, '');
  if (url && !/^https?:\/\//i.test(url)) {
    url = `https://${url}`;
  }
  // Production Railway backend (admin panel + DB social links live here)
  return url || 'https://superridexversion2-production.up.railway.app';
}

const BASE_URL = resolveBaseUrl();
/** Abort hung requests so Home/Plans/Login never spin forever on bad networks. */
const REQUEST_TIMEOUT_MS = 12000;

if (__DEV__) {
  console.log('[api] BASE_URL =', BASE_URL);
}

export function getApiBaseUrl() {
  return BASE_URL;
}

export class ApiError extends Error {
  constructor(message, { status, code, data } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.data = data;
  }
}

async function request(method, path, body, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;

  const deviceId = await getDeviceId();
  const deviceLabel = await getDeviceLabel();
  if (deviceId) headers['x-device-id'] = deviceId;
  if (deviceLabel) headers['x-device-label'] = deviceLabel;

  const payload = body
    ? { ...body, deviceId: body.deviceId || deviceId, deviceLabel: body.deviceLabel || deviceLabel }
    : undefined;

  // Always send phone in headers when present (backend auth reads body / Bearer / x-phone)
  const phone = payload?.phone || body?.phone;
  if (phone) {
    const digits = String(phone).replace(/\D/g, '').slice(-10);
    if (digits.length === 10) {
      headers['x-phone'] = digits;
      if (!token) headers.Authorization = `Bearer ${digits}`;
    }
  }

  const url = `${BASE_URL}${path}`;
  if (__DEV__) {
    console.log(`[api] ${method} ${url}`);
  }

  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const timer = controller
    ? setTimeout(() => {
        try {
          controller.abort();
        } catch {
          // ignore
        }
      }, REQUEST_TIMEOUT_MS)
    : null;

  let res;
  try {
    res = await fetch(url, {
      method,
      headers,
      body: payload ? JSON.stringify(payload) : undefined,
      signal: controller?.signal,
    });
  } catch (err) {
    const aborted = err?.name === 'AbortError' || controller?.signal?.aborted;
    throw new ApiError(
      aborted
        ? 'Request timed out. Check your internet and try again.'
        : `Cannot reach backend (${BASE_URL}). Check EXPO_PUBLIC_API_URL and rebuild the app.`,
      { status: 0, code: aborted ? 'TIMEOUT' : 'NETWORK', data: { cause: err?.message } }
    );
  } finally {
    if (timer) clearTimeout(timer);
  }

  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new ApiError(data.message || 'Request failed', {
      status: res.status,
      code: data.code,
      data,
    });
  }
  return data;
}

export async function getSubscriptionPlans() {
  return request('GET', '/subscription/plans');
}

export async function createOrder(planId, phone) {
  return request('POST', '/subscription/create-order', { planId, phone });
}

export async function verifyPayment({ razorpayOrderId, razorpayPaymentId, razorpaySignature, phone, planId }) {
  return request('POST', '/subscription/verify-payment', {
    razorpayOrderId,
    razorpayPaymentId,
    razorpaySignature,
    phone,
    planId,
  });
}

export async function getSubscriptionStatus(phone) {
  const deviceId = await getDeviceId();
  const qs = new URLSearchParams({ phone });
  if (deviceId) qs.set('deviceId', deviceId);
  return request('GET', `/subscription/status?${qs.toString()}`);
}

export async function checkEntitlement(phone) {
  return request('POST', '/entitlement/check', { phone });
}

export async function getReferralStatus(phone) {
  return request('GET', `/referral/status?phone=${encodeURIComponent(phone)}`);
}

export async function applyReferralCode(phone, code) {
  return request('POST', '/referral/apply', { phone, code });
}

export async function registerUser(phone, referralCode) {
  return request('POST', '/referral/register', { phone, referralCode });
}

/**
 * After client MSG91 verifyOTP, confirm JWT on our server and register user.
 */
export async function verifyOtpWithServer({ phone, accessToken, referralCode }) {
  return request('POST', '/auth/verify-otp', {
    phone,
    accessToken,
    'access-token': accessToken,
    referralCode,
  });
}

/** Public social / help links from DB (admin-editable). */
export async function getSocialLinks() {
  return request('GET', '/socials');
}
