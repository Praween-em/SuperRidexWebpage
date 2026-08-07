import React, { useEffect, useState, useCallback, useRef } from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import * as SplashScreen from 'expo-splash-screen';

import LoginScreen from '../screens/LoginScreen';
import PlansScreen from '../screens/PlansScreen';
import PermissionsSetupScreen from '../screens/PermissionsSetupScreen';
import LoadingScreen from '../screens/LoadingScreen';
import PrivacyPolicyScreen from '../screens/PrivacyPolicyScreen';
import TermsScreen from '../screens/TermsScreen';
import MainTabs from './MainTabs';
import { getStoredUser } from '../utils/storage';

const Stack = createNativeStackNavigator();
/** Short branded flash — long min time felt like lag on low-end phones. */
const MIN_LOADING_MS = 600;

function resolveInitialRoute(user) {
  if (!user?.phone) return 'Login';
  return 'Main';
}

export default function RootNavigator() {
  const [initialRoute, setInitialRoute] = useState(null);
  const [minTimeDone, setMinTimeDone] = useState(false);
  const [splashHidden, setSplashHidden] = useState(false);
  const bootStartedAt = useRef(Date.now());

  useEffect(() => {
    let cancelled = false;
    getStoredUser()
      .then((user) => {
        if (!cancelled) setInitialRoute(resolveInitialRoute(user));
      })
      .catch(() => {
        if (!cancelled) setInitialRoute('Login');
      });
    // Never block forever on AsyncStorage / slow flash storage
    const fallback = setTimeout(() => {
      if (!cancelled) setInitialRoute((r) => r || 'Login');
    }, 800);
    return () => {
      cancelled = true;
      clearTimeout(fallback);
    };
  }, []);

  // Brief branded loading UI, then enter app as soon as route is known
  useEffect(() => {
    const elapsed = Date.now() - bootStartedAt.current;
    const remaining = Math.max(0, MIN_LOADING_MS - elapsed);
    const t = setTimeout(() => setMinTimeDone(true), remaining);
    return () => clearTimeout(t);
  }, []);

  const hideNativeSplash = useCallback(async () => {
    if (splashHidden) return;
    try {
      await SplashScreen.hideAsync();
    } catch {
      // ignore
    } finally {
      setSplashHidden(true);
    }
  }, [splashHidden]);

  // Hand off from native splash to our LoadingScreen quickly
  useEffect(() => {
    hideNativeSplash();
    const t = setTimeout(hideNativeSplash, 200);
    return () => clearTimeout(t);
  }, [hideNativeSplash]);

  const showLoading = !initialRoute || !minTimeDone;

  if (showLoading) {
    return (
      <SafeAreaProvider style={{ flex: 1, backgroundColor: '#000000' }}>
        <LoadingScreen onVisible={hideNativeSplash} onReady={hideNativeSplash} />
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider onLayout={hideNativeSplash}>
      <NavigationContainer>
        <Stack.Navigator
          initialRouteName={initialRoute}
          screenOptions={{
            headerShown: false,
            animation: 'fade',
            animationDuration: 100,
          }}
        >
          <Stack.Screen name="Login" component={LoginScreen} />
          <Stack.Screen name="Plans" component={PlansScreen} />
          <Stack.Screen name="PermissionsSetup" component={PermissionsSetupScreen} />
          <Stack.Screen name="Main" component={MainTabs} />
          <Stack.Screen name="PrivacyPolicy" component={PrivacyPolicyScreen} />
          <Stack.Screen name="Terms" component={TermsScreen} />
        </Stack.Navigator>
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
