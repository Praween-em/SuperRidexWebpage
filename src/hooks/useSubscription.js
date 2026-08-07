/**
 * useSubscription.js
 * Manages subscription state: load from storage, refresh from backend,
 * expose isActive flag and expiry details (includes device entitlement).
 * Recomputes local expiry on a timer and refreshes when app returns to foreground
 * so auto-accept can be forced off the moment a plan ends.
 *
 * Local storage paints first; remote refresh is soft (no full-screen reload).
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { AppState } from 'react-native';
import { getStoredUser, saveUser } from '../utils/storage';
import { getSubscriptionStatus, ApiError } from '../services/api';

function computeLocalActive(subscriptionEnd) {
  if (!subscriptionEnd) return false;
  return new Date(subscriptionEnd) > new Date();
}

export function useSubscription() {
  const [subscription, setSubscription] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [deviceAllowed, setDeviceAllowed] = useState(true);
  const [nowTick, setNowTick] = useState(Date.now());
  const appStateRef = useRef(AppState.currentState);
  const refreshInflight = useRef(null);

  const refresh = useCallback(async ({ soft = false } = {}) => {
    // Soft refreshes (foreground / interval) must not flip loading=true —
    // that blocked Home controls and felt like lag on slow networks.
    if (!soft) setLoading(true);
    setError(null);

    const run = async () => {
      try {
        const user = await getStoredUser();
        if (!user?.phone) {
          setSubscription({ active: false });
          setDeviceAllowed(true);
          return;
        }

        if (user.subscriptionEnd) {
          const isLocallyActive = computeLocalActive(user.subscriptionEnd);
          setSubscription({
            active: isLocallyActive,
            subscriptionStart: user.subscriptionStart,
            subscriptionEnd: user.subscriptionEnd,
            planType: user.planType,
            phone: user.phone,
          });
        } else {
          setSubscription({ active: false, phone: user.phone });
        }

        // Initial paint can finish as soon as local data is applied
        if (!soft) {
          setLoading(false);
        }

        try {
          const remote = await getSubscriptionStatus(user.phone);
          const updated = {
            active: !!remote.active,
            subscriptionStart: remote.subscriptionStart,
            subscriptionEnd: remote.subscriptionEnd,
            planType: remote.planType,
            phone: user.phone,
          };
          setSubscription(updated);
          setDeviceAllowed(remote.deviceAllowed !== false);
          await saveUser({ ...user, ...updated });
        } catch (err) {
          if (err instanceof ApiError && err.code === 'DEVICE_LIMIT') {
            setDeviceAllowed(false);
            setSubscription((prev) => ({
              ...(prev || {}),
              active: false,
              phone: user.phone,
              deviceBlocked: true,
            }));
            setError(err.message);
          } else if (!user.subscriptionEnd) {
            setSubscription({ active: false, phone: user.phone });
          } else {
            // Offline / timeout: keep local expiry as source of truth
            setSubscription((prev) => ({
              ...(prev || {}),
              active: computeLocalActive(user.subscriptionEnd),
              subscriptionEnd: user.subscriptionEnd,
              subscriptionStart: user.subscriptionStart,
              planType: user.planType,
              phone: user.phone,
            }));
          }
        }
      } catch (err) {
        setError(err.message);
      } finally {
        setLoading(false);
        setNowTick(Date.now());
      }
    };

    // Coalesce parallel soft refreshes; hard refresh always re-runs after wait
    if (refreshInflight.current) {
      await refreshInflight.current;
      if (soft) return;
    }
    refreshInflight.current = run().finally(() => {
      refreshInflight.current = null;
    });
    await refreshInflight.current;
  }, []);

  useEffect(() => {
    refresh({ soft: false });
  }, [refresh]);

  // Recompute local expiry every 30s (and right when end time passes)
  useEffect(() => {
    const end = subscription?.subscriptionEnd;
    if (!end) return undefined;

    const endMs = new Date(end).getTime();
    if (Number.isNaN(endMs)) return undefined;

    const tick = () => setNowTick(Date.now());
    const interval = setInterval(tick, 30_000);

    const msUntilEnd = endMs - Date.now();
    let endTimeout;
    if (msUntilEnd > 0 && msUntilEnd < 24 * 60 * 60 * 1000) {
      endTimeout = setTimeout(tick, msUntilEnd + 250);
    }

    return () => {
      clearInterval(interval);
      if (endTimeout) clearTimeout(endTimeout);
    };
  }, [subscription?.subscriptionEnd]);

  // Soft refresh when app returns to foreground
  useEffect(() => {
    const sub = AppState.addEventListener('change', (next) => {
      const prev = appStateRef.current;
      appStateRef.current = next;
      if (prev.match(/inactive|background/) && next === 'active') {
        refresh({ soft: true });
      }
    });
    return () => sub.remove();
  }, [refresh]);

  // Soft remote refresh every 5 minutes while mounted
  useEffect(() => {
    const id = setInterval(() => {
      refresh({ soft: true });
    }, 5 * 60 * 1000);
    return () => clearInterval(id);
  }, [refresh]);

  const endDate = subscription?.subscriptionEnd ?? null;
  const locallyActive = computeLocalActive(endDate);
  // Remote active is enough when end date is missing; never stay active past a known end.
  const isActive = !!subscription?.active && (!endDate || locallyActive);

  // Force inactive in state when local clock says expired (keeps UI in sync)
  useEffect(() => {
    if (!subscription?.subscriptionEnd) return;
    if (subscription.active && !locallyActive) {
      setSubscription((prev) => (prev ? { ...prev, active: false } : prev));
    }
  }, [nowTick, locallyActive, subscription?.active, subscription?.subscriptionEnd]);

  const daysRemaining = endDate
    ? Math.max(
        0,
        Math.ceil((new Date(endDate) - new Date(nowTick)) / (1000 * 60 * 60 * 24))
      )
    : 0;

  return {
    subscription,
    loading,
    error,
    refresh: () => refresh({ soft: false }),
    isActive,
    deviceAllowed,
    daysRemaining,
    subscriptionEnd: endDate,
    planType: subscription?.planType ?? null,
    subscriptionStart: subscription?.subscriptionStart ?? null,
  };
}
