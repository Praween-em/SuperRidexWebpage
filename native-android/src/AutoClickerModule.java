package com.rapido.tap;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AutoClickerModule extends ReactContextBaseJavaModule {

  private static final String MODULE_NAME = "AutoClickerModule";
  /** Not in all SDK stubs — use string action (API 33+) */
  private static final String ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
      "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
  private static ReactApplicationContext reactContext;

  public AutoClickerModule(ReactApplicationContext context) {
    super(context);
    reactContext = context;
    AutoClickerConfig.init(context);
  }

  @Override
  public String getName() {
    return MODULE_NAME;
  }

  // ─── Permission helpers ───────────────────────────────────────────────────

  @ReactMethod
  public void isAccessibilityEnabled(Promise promise) {
    promise.resolve(isOurAccessibilityServiceEnabled(getReactApplicationContext()));
  }

  @ReactMethod
  public void openAccessibilitySettings() {
    Context ctx = getReactApplicationContext();
    // Android 13+: opens directly on this app's accessibility page (triggers restricted dialog)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      try {
        Intent details = new Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
        details.putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            new ComponentName(ctx.getPackageName(), AutoClickerService.class.getName())
        );
        details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(details);
        return;
      } catch (Exception ignored) {
        // fall through to generic accessibility settings
      }
    }
    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ctx.startActivity(intent);
  }

  /**
   * Opens this app's system settings page where Android 13+ users can tap
   * the ⋮ menu → "Allow restricted settings" (required for sideloaded APKs).
   */
  @ReactMethod
  public void openAppInfoSettings() {
    Context ctx = getReactApplicationContext();
    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
    intent.setData(Uri.parse("package:" + ctx.getPackageName()));
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ctx.startActivity(intent);
  }

  @ReactMethod
  public void isNotificationListenerEnabled(Promise promise) {
    promise.resolve(isOurNotificationListenerEnabled(getReactApplicationContext()));
  }

  @ReactMethod
  public void openNotificationListenerSettings() {
    Context ctx = getReactApplicationContext();
    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ctx.startActivity(intent);
  }

  @ReactMethod
  public void isOverlayPermissionGranted(Promise promise) {
    promise.resolve(Settings.canDrawOverlays(getReactApplicationContext()));
  }

  @ReactMethod
  public void openOverlaySettings() {
    Context ctx = getReactApplicationContext();
    Intent intent = new Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + ctx.getPackageName())
    );
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ctx.startActivity(intent);
  }

  @ReactMethod
  public void isBatteryOptimizationIgnored(Promise promise) {
    PowerManager pm = (PowerManager) getReactApplicationContext().getSystemService(Context.POWER_SERVICE);
    if (pm == null) {
      promise.resolve(false);
      return;
    }
    promise.resolve(pm.isIgnoringBatteryOptimizations(getReactApplicationContext().getPackageName()));
  }

  @ReactMethod
  public void openBatteryOptimizationSettings() {
    Context ctx = getReactApplicationContext();
    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
    intent.setData(Uri.parse("package:" + ctx.getPackageName()));
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ctx.startActivity(intent);
  }

  // ─── Service config ───────────────────────────────────────────────────────

  @ReactMethod
  public void setServiceEnabled(boolean enabled, Promise promise) {
    boolean prev = AutoClickerConfig.isEnabled();
    AutoClickerConfig.setEnabled(enabled);
    AutoClickerService.onConfigChanged();
    if (prev != enabled) {
      android.util.Log.i("AutoClickerModule", "setServiceEnabled " + prev + "→" + enabled);
    }
    promise.resolve(enabled);
  }

  @ReactMethod
  public void isServiceEnabled(Promise promise) {
    promise.resolve(AutoClickerConfig.isEnabled());
  }

  /** No-op — min-fare filtering removed; always reports 0. */
  @ReactMethod
  public void setMinPrice(int price, Promise promise) {
    AutoClickerConfig.setMinPrice(0);
    promise.resolve(0);
  }

  @ReactMethod
  public void setDelayMs(int delay, Promise promise) {
    AutoClickerConfig.setDelayMs(delay);
    promise.resolve(delay);
  }

  @ReactMethod
  public void setNuclearMode(boolean enabled, Promise promise) {
    AutoClickerConfig.setNuclearMode(enabled);
    // Nuclear always runs at 0ms delay
    if (enabled) {
      AutoClickerConfig.setDelayMs(0);
    }
    AutoClickerService.onConfigChanged();
    promise.resolve(enabled);
  }

  /** Continuous FG spray API — HARD OFF (MeClicker hunt→click→micro-burst only). */
  @ReactMethod
  public void setContinuousForegroundTap(boolean enabled, Promise promise) {
    AutoClickerConfig.setContinuousForegroundTap(false);
    AutoClickerService.onConfigChanged();
    promise.resolve(false);
  }

  @ReactMethod
  public void setMonitoredPackages(ReadableArray packages, Promise promise) {
    Set<String> set = new HashSet<>();
    for (int i = 0; i < packages.size(); i++) {
      String pkg = packages.getString(i);
      if (pkg != null && !pkg.isEmpty()) {
        set.add(pkg);
      }
    }
    AutoClickerConfig.setMonitoredPackages(set);
    promise.resolve(true);
  }

  @ReactMethod
  public void getServiceStatus(Promise promise) {
    WritableMap map = Arguments.createMap();
    map.putBoolean("enabled", AutoClickerConfig.isEnabled());
    map.putBoolean("nuclearMode", AutoClickerConfig.isNuclearMode());
    map.putBoolean("continuousForegroundTap", AutoClickerConfig.isContinuousForegroundTap());
    map.putInt("minPrice", AutoClickerConfig.getMinPrice());
    map.putInt("delayMs", AutoClickerConfig.getDelayMs());
    map.putBoolean("accessibilityEnabled", isOurAccessibilityServiceEnabled(getReactApplicationContext()));
    map.putBoolean(
        "notificationListenerEnabled",
        isOurNotificationListenerEnabled(getReactApplicationContext())
    );

    WritableArray pkgs = Arguments.createArray();
    for (String pkg : AutoClickerConfig.getMonitoredPackages()) {
      pkgs.pushString(pkg);
    }
    map.putArray("monitoredPackages", pkgs);
    promise.resolve(map);
  }

  // Required for NativeEventEmitter (no ride-history events emitted)
  @ReactMethod
  public void addListener(String eventName) {}

  @ReactMethod
  public void removeListeners(double count) {}

  private static boolean isOurNotificationListenerEnabled(Context context) {
    String flat = Settings.Secure.getString(
        context.getContentResolver(),
        "enabled_notification_listeners"
    );
    if (TextUtils.isEmpty(flat)) return false;
    String needle = new ComponentName(
        context.getPackageName(),
        RideAlertListener.class.getName()
    ).flattenToString();
    for (String entry : flat.split(":")) {
      if (needle.equalsIgnoreCase(entry)) return true;
      ComponentName cn = ComponentName.unflattenFromString(entry);
      if (cn != null
          && context.getPackageName().equals(cn.getPackageName())
          && RideAlertListener.class.getName().equals(cn.getClassName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isOurAccessibilityServiceEnabled(Context context) {
    AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    if (am == null) return false;

    List<AccessibilityServiceInfo> services =
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

    String ourPackage = context.getPackageName();
    for (AccessibilityServiceInfo info : services) {
      if (info.getResolveInfo() != null
          && info.getResolveInfo().serviceInfo != null
          && ourPackage.equals(info.getResolveInfo().serviceInfo.packageName)
          && AutoClickerService.class.getName().equals(info.getResolveInfo().serviceInfo.name)) {
        return true;
      }
    }

    // Fallback: check Settings.Secure string
    String enabled = Settings.Secure.getString(
        context.getContentResolver(),
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    );
    return enabled != null && enabled.contains(ourPackage);
  }
}
