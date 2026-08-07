import React from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import * as SplashScreen from 'expo-splash-screen';
import RootNavigator from './src/navigation/RootNavigator';
import { colors } from './src/theme/colors';

// Keep the native splash visible until the JS loading UI is ready.
// Do not await — call as early as possible (Expo SDK 57).
SplashScreen.preventAutoHideAsync().catch(() => {});

export default function App() {
  return (
    <GestureHandlerRootView style={{ flex: 1, backgroundColor: colors.background }}>
      <RootNavigator />
    </GestureHandlerRootView>
  );
}


