import React, { useEffect, useState, useCallback } from 'react';
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

function resolveInitialRoute(user) {
  if (!user?.phone) return 'Login';
  return 'Main';
}

export default function RootNavigator() {
  const [initialRoute, setInitialRoute] = useState(null);
  const [splashHidden, setSplashHidden] = useState(false);

  useEffect(() => {
    let cancelled = false;
    // Local session only — never wait on network / native status before first route
    getStoredUser()
      .then((user) => {
        if (!cancelled) setInitialRoute(resolveInitialRoute(user));
      })
      .catch(() => {
        if (!cancelled) setInitialRoute('Login');
      });
    // Fast fallback on slow flash storage (was 800ms — felt like a black stall)
    const fallback = setTimeout(() => {
      if (!cancelled) setInitialRoute((r) => r || 'Login');
    }, 350);
    return () => {
      cancelled = true;
      clearTimeout(fallback);
    };
  }, []);

  const hideNativeSplash = useCallback(() => {
    if (splashHidden) return;
    setSplashHidden(true);
    // Prefer sync hide — await hideAsync delayed first paint after swipe-kill
    try {
      if (typeof SplashScreen.hide === 'function') {
        SplashScreen.hide();
      } else {
        SplashScreen.hideAsync().catch(() => {});
      }
    } catch {
      SplashScreen.hideAsync().catch(() => {});
    }
  }, [splashHidden]);

  // Hand off from native splash as soon as JS mounts (don't wait for route)
  useEffect(() => {
    hideNativeSplash();
    const t = setTimeout(hideNativeSplash, 120);
    return () => clearTimeout(t);
  }, [hideNativeSplash]);

  // No artificial MIN_LOADING_MS — cold start after swipe-kill must not force a black flash
  if (!initialRoute) {
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
