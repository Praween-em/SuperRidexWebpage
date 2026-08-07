import React, { useEffect, useRef } from 'react';
import { View, Image, StyleSheet, StatusBar } from 'react-native';

/**
 * Launch frame on cold start — light JPEG so low-end devices decode quickly.
 */
export default function LoadingScreen({ onVisible, onReady }) {
  const sent = useRef(false);

  useEffect(() => {
    if (sent.current) return;
    sent.current = true;
    onVisible?.();
    onReady?.();
  }, [onVisible, onReady]);

  return (
    <View
      style={styles.root}
      onLayout={() => {
        if (sent.current) return;
        sent.current = true;
        onVisible?.();
        onReady?.();
      }}
    >
      <StatusBar barStyle="light-content" backgroundColor="#000000" translucent={false} />
      <Image
        source={require('../../assets/splash-blank.jpg')}
        style={styles.splash}
        resizeMode="contain"
        fadeDuration={0}
        accessibilityLabel="SUPER RIDEX"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#000000',
    alignItems: 'center',
    justifyContent: 'center',
  },
  splash: {
    width: '100%',
    height: '100%',
  },
});
