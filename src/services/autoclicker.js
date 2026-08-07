/**
 * autoclicker.js
 * JavaScript bridge to the native AutoClickerModule (Android only).
 */

import { NativeModules, Platform } from 'react-native';

const { AutoClickerModule } = NativeModules;

function unavailable() {
  return Promise.reject(new Error('AutoClickerModule is only available on Android'));
}

export function isNativeAvailable() {
  return Platform.OS === 'android' && !!AutoClickerModule;
}

export function setServiceEnabled(enabled) {
  return AutoClickerModule?.setServiceEnabled(enabled) ?? unavailable();
}

export function getServiceStatus() {
  return AutoClickerModule?.getServiceStatus() ?? unavailable();
}

/** Native: Accept ON + a11y OFF → death alert (also registered without JS). */
export function checkAccessibilityHealth() {
  if (!AutoClickerModule?.checkAccessibilityHealth) {
    return Promise.resolve({ acceptEnabled: false, accessibilityEnabled: false });
  }
  return AutoClickerModule.checkAccessibilityHealth();
}

/** No-op — min-fare filtering removed from the native engine. */
export function setMinPrice() {
  return AutoClickerModule?.setMinPrice(0) ?? Promise.resolve(0);
}

export function setDelayMs(delay) {
  return AutoClickerModule?.setDelayMs(delay) ?? unavailable();
}

export function setNuclearMode(enabled) {
  return AutoClickerModule?.setNuclearMode(enabled) ?? unavailable();
}

export function setContinuousForegroundTap(enabled) {
  return AutoClickerModule?.setContinuousForegroundTap(enabled) ?? unavailable();
}

export function setMonitoredPackages(packages) {
  return AutoClickerModule?.setMonitoredPackages(packages) ?? unavailable();
}

export function saveSettings({ enabled, delayMs, monitoredPackages, nuclearMode }) {
  if (!AutoClickerModule) return unavailable();
  const nuclear = nuclearMode !== false;
  return Promise.all([
    AutoClickerModule.setServiceEnabled(!!enabled),
    AutoClickerModule.setNuclearMode(nuclear),
    // Nuclear always 0; Standard can use a small delay if set
    AutoClickerModule.setDelayMs(nuclear ? 0 : (delayMs ?? 0)),
    AutoClickerModule.setMonitoredPackages(monitoredPackages),
  ]);
}

/** Driver app package ids (not passenger apps). */
export const APP_PACKAGES = {
  rapido: 'com.rapido.rider',
  rideAndhra: 'com.rideandhra.driverapp',
  ola: 'com.olacabs.driver',
  uber: 'com.ubercab.driver',
};
