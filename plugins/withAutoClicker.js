const {
  withAndroidManifest,
  withStringsXml,
  withDangerousMod,
  AndroidConfig,
} = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/** Must match app.config.js android.package */
const APP_PACKAGE = 'com.rapido.tap';
const JAVA_DIR = `app/src/main/java/${APP_PACKAGE.replace(/\./g, '/')}`;
const JAVA_FILES = [
  'AutoClickerConfig.java',
  'AutoClickerService.java',
  'AutoClickerModule.java',
  'AutoClickerPackage.java',
  'BootReceiver.java',
  'RideAlertListener.java',
];

const ACCESSIBILITY_XML = `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged|typeNotificationStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:notificationTimeout="50"
    android:description="@string/accessibility_service_description" />
`;

// MSG91 Invisible OTP / Silent Network Auth hits carrier HTTP endpoints
// (Jio, Airtel, Vi, etc.). Release builds block cleartext unless permitted —
// MSG91 docs require base cleartext for invisible OTP to work on all carriers.
const NETWORK_SECURITY_CONFIG_XML = `<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
        <domain includeSubdomains="true">partnerapi.jio.com</domain>
        <domain includeSubdomains="true">jio.com</domain>
        <domain includeSubdomains="true">airtel.in</domain>
        <domain includeSubdomains="true">vodafoneidea.com</domain>
        <domain includeSubdomains="true">myvi.in</domain>
        <domain includeSubdomains="true">control.msg91.com</domain>
        <domain includeSubdomains="true">msg91.com</domain>
        <domain includeSubdomains="true">80.in.safr.sekuramobile.com</domain>
        <domain includeSubdomains="true">safr.sekuramobile.com</domain>
        <domain includeSubdomains="true">api.msg91.com</domain>
    </domain-config>
</network-security-config>
`;

const ABI_SPLITS_BLOCK = `
    // Single universal phone APK (arm32 + arm64). No per-ABI split outputs.
    splits {
        abi {
            enable false
        }
    }
`;

function writeNetworkSecurityConfig(platformRoot) {
  const xmlDir = path.join(platformRoot, 'app/src/main/res/xml');
  fs.mkdirSync(xmlDir, { recursive: true });
  fs.writeFileSync(
    path.join(xmlDir, 'network_security_config.xml'),
    NETWORK_SECURITY_CONFIG_XML
  );
  console.log('[withAutoClicker] wrote network_security_config.xml (MSG91/Jio OTP)');
}

function copyNativeSources(projectRoot, platformRoot) {
  const srcDir = path.join(projectRoot, 'native-android', 'src');
  const destDir = path.join(platformRoot, JAVA_DIR);
  if (!fs.existsSync(srcDir)) {
    console.warn('[withAutoClicker] missing native-android/src — skip Java sync');
    return;
  }

  // Drop stale packages from older branding (playnix / rapidotap)
  const javaRoot = path.join(platformRoot, 'app/src/main/java');
  for (const stale of ['com/playnix', 'com/rapidotap']) {
    const stalePath = path.join(javaRoot, stale);
    if (fs.existsSync(stalePath)) {
      fs.rmSync(stalePath, { recursive: true, force: true });
      console.log(`[withAutoClicker] removed stale package tree ${stale}`);
    }
  }

  fs.mkdirSync(destDir, { recursive: true });
  let copied = 0;
  for (const file of JAVA_FILES) {
    const from = path.join(srcDir, file);
    const to = path.join(destDir, file);
    if (!fs.existsSync(from)) {
      console.warn(`[withAutoClicker] missing source ${file}`);
      continue;
    }
    fs.copyFileSync(from, to);
    copied++;
  }
  console.log(`[withAutoClicker] synced ${copied}/${JAVA_FILES.length} Java files → ${JAVA_DIR}`);

  const xmlDir = path.join(platformRoot, 'app/src/main/res/xml');
  fs.mkdirSync(xmlDir, { recursive: true });
  const xmlPath = path.join(xmlDir, 'accessibility_service_config.xml');
  fs.writeFileSync(xmlPath, ACCESSIBILITY_XML);
  const xml = fs.readFileSync(xmlPath, 'utf8');
  if (!xml.includes('canPerformGestures="true"')
      || !xml.includes('notificationTimeout="50"')
      || xml.includes('typeAllMask')) {
    throw new Error('[withAutoClicker] accessibility_service_config.xml missing required flags');
  }

  writeNetworkSecurityConfig(platformRoot);
}

function withAutoClickerManifest(config) {
  return withAndroidManifest(config, (mod) => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(mod.modResults);

    app.service = app.service ?? [];

    const a11ySvc = app.service.find((s) => s.$?.['android:name'] === '.AutoClickerService');
    if (a11ySvc?.$) {
      a11ySvc.$['android:foregroundServiceType'] = 'specialUse';
      a11ySvc.property = a11ySvc.property ?? [];
      const hasProp = a11ySvc.property.some(
        (p) => p.$?.['android:name'] === 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE'
      );
      if (!hasProp) {
        a11ySvc.property.push({
          $: {
            'android:name': 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',
            'android:value': 'accessibility_race_engine',
          },
        });
      }
    }

    const hasA11y = app.service.some((s) => s.$?.['android:name'] === '.AutoClickerService');
    if (!hasA11y) {
      app.service.push({
        $: {
          'android:name': '.AutoClickerService',
          'android:exported': 'true',
          'android:label': '@string/app_name',
          'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
          'android:foregroundServiceType': 'specialUse',
        },
        'intent-filter': [
          {
            action: [
              { $: { 'android:name': 'android.accessibilityservice.AccessibilityService' } },
            ],
          },
        ],
        'meta-data': [
          {
            $: {
              'android:name': 'android.accessibilityservice',
              'android:resource': '@xml/accessibility_service_config',
            },
          },
        ],
        property: [
          {
            $: {
              'android:name': 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',
              'android:value': 'accessibility_race_engine',
            },
          },
        ],
      });
    }

    const hasNls = app.service.some((s) => s.$?.['android:name'] === '.RideAlertListener');
    if (!hasNls) {
      app.service.push({
        $: {
          'android:name': '.RideAlertListener',
          'android:label': 'SUPER RIDEX Alerts',
          'android:permission': 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE',
          'android:exported': 'true',
        },
        'intent-filter': [
          {
            action: [
              {
                $: {
                  'android:name': 'android.service.notification.NotificationListenerService',
                },
              },
            ],
          },
        ],
      });
    }

    app.receiver = app.receiver ?? [];
    const hasBoot = app.receiver.some((r) => r.$?.['android:name'] === '.BootReceiver');
    if (!hasBoot) {
      app.receiver = [
        ...app.receiver,
        {
          $: {
            'android:name': '.BootReceiver',
            'android:exported': 'true',
            'android:enabled': 'true',
          },
          'intent-filter': [
            {
              action: [{ $: { 'android:name': 'android.intent.action.BOOT_COMPLETED' } }],
            },
          ],
        },
      ];
    }

    if (app['provider']) {
      app['provider'] = app['provider'].filter(
        (p) => p.$?.['android:name'] !== 'rikka.shizuku.ShizukuProvider'
      );
    }

    app.$['android:networkSecurityConfig'] = '@xml/network_security_config';
    // Required for MSG91 invisible OTP on carriers that use cleartext HTTP
    app.$['android:usesCleartextTraffic'] = 'true';

    return mod;
  });
}

function withAutoClickerStrings(config) {
  return withStringsXml(config, (mod) => {
    mod.modResults.resources.string = [
      ...(mod.modResults.resources.string ?? []).filter(
        (s) => s.$.name !== 'accessibility_service_description'
      ),
      {
        $: { name: 'accessibility_service_description' },
        _: 'SUPER RIDEX monitors ride requests in Ola, Uber and other driver apps and automatically taps Accept when the fare meets your minimum price.',
      },
    ];
    return mod;
  });
}

function findMainApplication(platformRoot) {
  const candidates = [
    path.join(platformRoot, JAVA_DIR, 'MainApplication.kt'),
    path.join(platformRoot, JAVA_DIR, 'MainApplication.java'),
    path.join(platformRoot, 'app/src/main/java/com/playnix/app/MainApplication.kt'),
    path.join(platformRoot, 'app/src/main/java/com/rapidotap/app/MainApplication.kt'),
  ];
  for (const p of candidates) {
    if (fs.existsSync(p)) return p;
  }
  // Fallback: search
  const javaRoot = path.join(platformRoot, 'app/src/main/java');
  if (!fs.existsSync(javaRoot)) return null;
  const stack = [javaRoot];
  while (stack.length) {
    const dir = stack.pop();
    for (const name of fs.readdirSync(dir)) {
      const full = path.join(dir, name);
      const st = fs.statSync(full);
      if (st.isDirectory()) stack.push(full);
      else if (name === 'MainApplication.kt' || name === 'MainApplication.java') return full;
    }
  }
  return null;
}

function patchMainApplication(platformRoot) {
  const mainAppPath = findMainApplication(platformRoot);
  if (!mainAppPath) return;

  let contents = fs.readFileSync(mainAppPath, 'utf8');
  if (contents.includes('AutoClickerPackage()')) return;

  contents = contents.replace(
    /PackageList\(this\)\.packages\.apply\s*\{[^}]*\}/,
    `PackageList(this).packages.apply {\n          add(AutoClickerPackage())\n        }`
  );
  fs.writeFileSync(mainAppPath, contents);
  console.log(`[withAutoClicker] registered AutoClickerPackage in ${path.relative(platformRoot, mainAppPath)}`);
}

function copyBrandIcons(projectRoot, platformRoot) {
  const srcRoot = path.join(projectRoot, 'assets', 'AppIcons', 'android');
  const resRoot = path.join(platformRoot, 'app', 'src', 'main', 'res');
  if (!fs.existsSync(srcRoot)) {
    console.warn('[withAutoClicker] missing assets/AppIcons/android — skip icon sync');
    return;
  }

  const densities = [
    'mipmap-mdpi',
    'mipmap-hdpi',
    'mipmap-xhdpi',
    'mipmap-xxhdpi',
    'mipmap-xxxhdpi',
  ];

  for (const folder of [...densities, 'mipmap-anydpi-v26', 'drawable']) {
    const dir = path.join(resRoot, folder);
    if (!fs.existsSync(dir)) continue;
    for (const file of fs.readdirSync(dir)) {
      if (/^ic_launcher/i.test(file) || file === 'splash_full.png') {
        fs.unlinkSync(path.join(dir, file));
      }
    }
  }

  let copied = 0;
  for (const density of densities) {
    const fromDir = path.join(srcRoot, density);
    const toDir = path.join(resRoot, density);
    if (!fs.existsSync(fromDir)) continue;
    fs.mkdirSync(toDir, { recursive: true });
    const launcher = path.join(fromDir, 'ic_launcher.png');
    if (fs.existsSync(launcher)) {
      fs.copyFileSync(launcher, path.join(toDir, 'ic_launcher.png'));
      fs.copyFileSync(launcher, path.join(toDir, 'ic_launcher_round.png'));
      copied += 2;
    }
  }

  const drawableDir = path.join(resRoot, 'drawable');
  fs.mkdirSync(drawableDir, { recursive: true });
  fs.writeFileSync(
    path.join(drawableDir, 'ic_launcher_background.xml'),
    `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#000000" android:pathData="M0,0h108v108h-108z"/>
</vector>
`
  );

  // One foreground mipmap only (xxxhdpi) — avoid shipping the same ~700KB PNG 5×
  const fgSrc = path.join(srcRoot, 'adaptive-foreground.png');
  if (fs.existsSync(fgSrc)) {
    const toDir = path.join(resRoot, 'mipmap-xxxhdpi');
    fs.mkdirSync(toDir, { recursive: true });
    fs.copyFileSync(fgSrc, path.join(toDir, 'ic_launcher_foreground.png'));
    copied++;
  }

  const anydpiTo = path.join(resRoot, 'mipmap-anydpi-v26');
  fs.mkdirSync(anydpiTo, { recursive: true });
  const adaptiveXml = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
`;
  fs.writeFileSync(path.join(anydpiTo, 'ic_launcher.xml'), adaptiveXml);
  fs.writeFileSync(path.join(anydpiTo, 'ic_launcher_round.xml'), adaptiveXml);

  console.log(`[withAutoClicker] synced ${copied} icons from assets/AppIcons/android`);
}

/**
 * Android 12+ always shows a circular splash icon. Branding is JS LoadingScreen —
 * replace the native logo with a solid black drawable (invisible on black splash).
 */
function neutralizeNativeSplashIcon(platformRoot) {
  const resRoot = path.join(platformRoot, 'app', 'src', 'main', 'res');
  const densityDirs = [
    'drawable-mdpi',
    'drawable-hdpi',
    'drawable-xhdpi',
    'drawable-xxhdpi',
    'drawable-xxxhdpi',
    'drawable-night-mdpi',
    'drawable-night-hdpi',
    'drawable-night-xhdpi',
    'drawable-night-xxhdpi',
    'drawable-night-xxxhdpi',
  ];

  for (const dir of densityDirs) {
    const png = path.join(resRoot, dir, 'splashscreen_logo.png');
    if (fs.existsSync(png)) fs.unlinkSync(png);
    const xml = path.join(resRoot, dir, 'splashscreen_logo.xml');
    if (fs.existsSync(xml)) fs.unlinkSync(xml);
  }

  const drawableDir = path.join(resRoot, 'drawable');
  fs.mkdirSync(drawableDir, { recursive: true });
  const drawablePng = path.join(drawableDir, 'splashscreen_logo.png');
  if (fs.existsSync(drawablePng)) fs.unlinkSync(drawablePng);
  const splashFull = path.join(drawableDir, 'splash_full.png');
  if (fs.existsSync(splashFull)) fs.unlinkSync(splashFull);

  fs.writeFileSync(
    path.join(drawableDir, 'splashscreen_logo.xml'),
    `<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#000000"/>
    <size android:width="1dp" android:height="1dp"/>
</shape>
`
  );
  console.log('[withAutoClicker] native splash icon neutralized (black / invisible)');
}

function patchReleaseSize(platformRoot) {
  const propsPath = path.join(platformRoot, 'gradle.properties');
  if (fs.existsSync(propsPath)) {
    let props = fs.readFileSync(propsPath, 'utf8');
    const setProp = (key, value) => {
      const re = new RegExp(`^${key}=.*$`, 'm');
      if (re.test(props)) props = props.replace(re, `${key}=${value}`);
      else props += `\n${key}=${value}\n`;
    };
    setProp('reactNativeArchitectures', 'armeabi-v7a,arm64-v8a');
    setProp('android.enableMinifyInReleaseBuilds', 'true');
    setProp('android.enableShrinkResourcesInReleaseBuilds', 'true');
    setProp('expo.gif.enabled', 'false');
    setProp('expo.webp.animated', 'false');
    setProp('android.enableBundleCompression', 'true');
    fs.writeFileSync(propsPath, props);
    console.log('[withAutoClicker] gradle.properties: phone ABIs + minify/shrink');
  }

  const gradlePath = path.join(platformRoot, 'app/build.gradle');
  if (!fs.existsSync(gradlePath)) return;
  let contents = fs.readFileSync(gradlePath, 'utf8');
  contents = contents
    .replace(/\n\s*implementation\("dev\.rikka\.shizuku:api:[^"]+"\)/g, '')
    .replace(/\n\s*implementation\("dev\.rikka\.shizuku:provider:[^"]+"\)/g, '');

  // Remove any previously injected ABI split block (old dual-APK setup)
  contents = contents.replace(
    /\n\s*\/\/ Phone ABIs only[\s\S]*?splits\s*\{\s*abi\s*\{[\s\S]*?\}\s*\}\s*/g,
    '\n'
  );
  contents = contents.replace(
    /\n\s*\/\/ Single universal phone APK[\s\S]*?splits\s*\{\s*abi\s*\{[\s\S]*?\}\s*\}\s*/g,
    '\n'
  );
  contents = contents.replace(
    /\n\s*splits\s*\{\s*abi\s*\{[^}]*enable\s+true[^}]*\}\s*\}\s*/g,
    '\n'
  );

  if (!contents.includes('Single universal phone APK')) {
    contents = contents.replace(
      /android\s*\{/,
      (m) => `${m}\n${ABI_SPLITS_BLOCK}`
    );
    console.log('[withAutoClicker] app/build.gradle: single universal APK (ABI splits off)');
  }

  fs.writeFileSync(gradlePath, contents);
}

function withAutoClickerSources(config) {
  return withDangerousMod(config, [
    'android',
    async (config) => {
      const root = config.modRequest.projectRoot;
      const androidRoot = config.modRequest.platformProjectRoot;
      copyNativeSources(root, androidRoot);
      copyBrandIcons(root, androidRoot);
      neutralizeNativeSplashIcon(androidRoot);
      patchMainApplication(androidRoot);
      patchReleaseSize(androidRoot);
      return config;
    },
  ]);
}

function withAutoClicker(config) {
  config = withAutoClickerManifest(config);
  config = withAutoClickerStrings(config);
  config = withAutoClickerSources(config);
  return config;
}

module.exports = withAutoClicker;
