# Native Android (SUPER RIDEX)

Source of truth for the accessibility / notification race engine:

- **`native-android/src/`** — Java sources (`package com.rapido.tap`)
- Synced into `android/` on every Expo prebuild by `plugins/withAutoClicker.js`

## Fresh Android build

```bash
# Remove old generated project (old package / fat APKs)
rm -rf android

npx expo prebuild --platform android --clean
cd android
./gradlew assembleRelease
```

Install the universal release APK from:

`android/app/build/outputs/apk/release/app-release.apk`

(One APK for both 32-bit and 64-bit phones — no separate arm64 / armeabi builds.)

### Device profile (Accept race)

- **Stock** (Pixel / Motorola / Nothing / Fairphone): shorter taps, faster polls
- **Everyone else** (Xiaomi, Vivo, Oppo, Realme, Samsung, Tecno, unknown…): longer taps (~72ms), slower polls — keeps Accessibility alive and registers presses on skinned OEMs

After install: Accessibility ON + Notification access ON + Battery unrestricted + Autostart (OEM).

## Enable auto-accept

1. Install the new APK (`com.rapido.tap` — uninstall any old `com.playnix.app` build first)
2. **Settings → Accessibility → Installed services → SUPER RIDEX → Enable**
3. **Settings → Notification access → SUPER RIDEX Alerts → ON** (recommended)
4. Open SUPER RIDEX → turn Auto-accept ON
