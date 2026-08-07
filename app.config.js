import 'dotenv/config';

/** @type {import('expo/config').ExpoConfig} */
export default {
  expo: {
    name: 'SUPER RIDEX',
    slug: 'superridex',
    version: '1.0.0',
    orientation: 'portrait',
    icon: './assets/AppIcons/playstore.png',
    userInterfaceStyle: 'light',
    // Native splash is black-only (Android forces a circle — we hide it).
    // Branded splash is LoadingScreen via splash-blank.jpg (light JPEG).
    splash: {
      image: './assets/splash-blank.png',
      resizeMode: 'contain',
      backgroundColor: '#000000',
    },
    plugins: [
      [
        'expo-splash-screen',
        {
          backgroundColor: '#000000',
          image: './assets/splash-blank.png',
          // Must be a valid resize width — imageWidth:1 crashes jimp ("Invalid settings for the resizer")
          imageWidth: 48,
          resizeMode: 'contain',
        },
      ],
      // Runs after expo-splash-screen so we can wipe the circular logo
      './plugins/withAutoClicker.js',
    ],
    ios: {
      supportsTablet: false,
      bundleIdentifier: 'com.rapido.tap',
      icon: './assets/AppIcons/appstore.png',
    },
    android: {
      package: 'com.rapido.tap',
      icon: './assets/AppIcons/android/mipmap-xxxhdpi/ic_launcher.png',
      adaptiveIcon: {
        backgroundColor: '#000000',
        foregroundImage: './assets/AppIcons/android/adaptive-foreground.png',
      },
      permissions: [
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.VIBRATE',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.WAKE_LOCK',
        'android.permission.INTERNET',
        'android.permission.ACCESS_NETWORK_STATE',
      ],
    },
    web: {
      favicon: './assets/AppIcons/playstore.png',
    },
    extra: {
      apiUrl:
        process.env.EXPO_PUBLIC_API_URL ||
        'https://superridexversion2-production.up.railway.app',
      razorpayKeyId: process.env.EXPO_PUBLIC_RAZORPAY_KEY_ID,
      razorpayMode: process.env.EXPO_PUBLIC_RAZORPAY_MODE || 'test',
      msg91WidgetId: process.env.EXPO_PUBLIC_MSG91_WIDGET_ID,
      msg91AuthToken: process.env.EXPO_PUBLIC_MSG91_AUTH_TOKEN,
    },
  },
};
