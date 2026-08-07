package com.rapido.tap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-priority alert when Accept was ON but AccessibilityService dies / is disabled.
 * Separate from the silent engine FG channel — works even if JS UI is dead after swipe-kill.
 */
public final class AccessibilityDeathAlert {

  private static final String TAG = "A11yDeathAlert";
  private static final String CHANNEL_ID = "superridex_a11y_death";
  private static final int NOTIFY_ID = 7143;
  /** Not in all SDK stubs — use string action (API 33+) */
  private static final String ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
      "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
  /** Coalesce storm of destroy + interrupt + state listeners. */
  private static final long DEBOUNCE_MS = 12_000L;

  private static final AtomicBoolean monitoring = new AtomicBoolean(false);
  private static volatile long lastAlertUptimeMs = 0L;
  private static volatile AccessibilityManager.AccessibilityStateChangeListener stateListener;
  private static volatile Object servicesStateListener; // API 33+

  private AccessibilityDeathAlert() {}

  /** Register once from Application/Module/Service — survives without JS. */
  public static void ensureMonitoring(Context context) {
    if (context == null || monitoring.get()) return;
    Context app = context.getApplicationContext();
    AutoClickerConfig.ensureInit(app);
    AccessibilityManager am =
        (AccessibilityManager) app.getSystemService(Context.ACCESSIBILITY_SERVICE);
    if (am == null) return;
    if (!monitoring.compareAndSet(false, true)) return;

    stateListener = enabled -> {
      // Global a11y flip or OEM churn — re-check our service.
      checkAcceptOnWithoutA11y(app, "a11y_state");
    };
    try {
      am.addAccessibilityStateChangeListener(stateListener);
    } catch (Throwable t) {
      Log.w(TAG, "state listener failed: " + t.getMessage());
    }

    if (Build.VERSION.SDK_INT >= 33) {
      try {
        AccessibilityManager.AccessibilityServicesStateChangeListener ssl =
            manager -> checkAcceptOnWithoutA11y(app, "a11y_services");
        servicesStateListener = ssl;
        am.addAccessibilityServicesStateChangeListener(app.getMainExecutor(), ssl);
      } catch (Throwable t) {
        Log.w(TAG, "services listener failed: " + t.getMessage());
      }
    }

    // Stale case: Accept ON on disk, a11y already off (process restart / boot).
    checkAcceptOnWithoutA11y(app, "monitor_start");
    Log.i(TAG, "MONITORING_ON");
  }

  /** Call from AutoClickerService onServiceConnected — clear gap + cancel stale alert. */
  public static void onServiceConnected(Context context) {
    if (context == null) return;
    ensureMonitoring(context);
    lastAlertUptimeMs = 0L;
    cancelAlert(context);
  }

  /** Call from onDestroy / onInterrupt when the a11y service is dying. */
  public static void onServiceDying(Context context, String reason) {
    maybeAlert(context, reason != null ? reason : "service_death", true);
  }

  /**
   * Accept was ON but our AccessibilityService is not enabled — post death alert.
   * Used by state listeners and Module/Boot cold checks.
   */
  public static void checkAcceptOnWithoutA11y(Context context, String reason) {
    if (context == null) return;
    Context app = context.getApplicationContext();
    AutoClickerConfig.ensureInit(app);
    if (!AutoClickerConfig.isEnabled()) return;
    if (isOurAccessibilityServiceEnabled(app)) return;
    maybeAlert(app, reason != null ? reason : "a11y_missing", false);
  }

  private static void maybeAlert(Context context, String reason, boolean forceDeathPath) {
    if (context == null) return;
    Context app = context.getApplicationContext();
    AutoClickerConfig.ensureInit(app);
    if (!AutoClickerConfig.isEnabled()) return;

    // Death path (destroy/interrupt): service is going away — alert even if Settings
    // still briefly lists us. Listener path: only when we are truly not enabled.
    if (!forceDeathPath && isOurAccessibilityServiceEnabled(app)) return;

    long now = SystemClock.uptimeMillis();
    if (now - lastAlertUptimeMs < DEBOUNCE_MS) {
      Log.i(TAG, "ALERT_COALESCE reason=" + reason);
      return;
    }
    lastAlertUptimeMs = now;

    try {
      ensureChannel(app);
      int icon = app.getApplicationInfo().icon;
      if (icon == 0) icon = android.R.drawable.ic_dialog_alert;

      PendingIntent tap = accessibilitySettingsPendingIntent(app);
      Notification.Builder b;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        b = new Notification.Builder(app, CHANNEL_ID);
      } else {
        b = new Notification.Builder(app);
        b.setPriority(Notification.PRIORITY_DEFAULT);
      }
      Notification.Builder nb = b
          .setContentTitle("Accept stopped — tap to fix")
          .setContentText("Accessibility turned off. Re-enable SUPER RIDEX to keep Accept on.")
          .setSmallIcon(icon)
          .setAutoCancel(true)
          .setOnlyAlertOnce(true)
          .setContentIntent(tap);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        nb.setCategory(Notification.CATEGORY_ERROR);
        nb.setVisibility(Notification.VISIBILITY_PUBLIC);
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
        nb.setLocalOnly(false);
      }

      NotificationManager nm =
          (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm == null) return;
      nm.notify(NOTIFY_ID, nb.build());
      Log.w(TAG, "ALERT_POSTED reason=" + reason + " enabled=" + AutoClickerConfig.isEnabled());
    } catch (Throwable t) {
      Log.e(TAG, "ALERT_FAIL reason=" + reason, t);
    }
  }

  public static void cancelAlert(Context context) {
    if (context == null) return;
    try {
      NotificationManager nm =
          (NotificationManager) context.getApplicationContext()
              .getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null) nm.cancel(NOTIFY_ID);
    } catch (Throwable ignored) {
    }
  }

  private static void ensureChannel(Context app) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    try {
      NotificationManager nm =
          (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm == null) return;
      if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
      // DEFAULT — not MIN/silent; user must notice Accept died.
      NotificationChannel ch = new NotificationChannel(
          CHANNEL_ID,
          "Accept stopped alerts",
          NotificationManager.IMPORTANCE_DEFAULT
      );
      ch.setDescription("Alerts when Accept was on but Accessibility stopped");
      ch.setShowBadge(true);
      ch.enableVibration(true);
      nm.createNotificationChannel(ch);
    } catch (Throwable ignored) {
    }
  }

  private static PendingIntent accessibilitySettingsPendingIntent(Context app) {
    Intent intent = buildAccessibilitySettingsIntent(app);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }
    return PendingIntent.getActivity(app, NOTIFY_ID, intent, flags);
  }

  static Intent buildAccessibilitySettingsIntent(Context ctx) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      try {
        Intent details = new Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
        details.putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            new ComponentName(ctx.getPackageName(), AutoClickerService.class.getName())
        );
        details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return details;
      } catch (Throwable ignored) {
      }
    }
    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return intent;
  }

  public static boolean isOurAccessibilityServiceEnabled(Context context) {
    if (context == null) return false;
    Context app = context.getApplicationContext();
    AccessibilityManager am =
        (AccessibilityManager) app.getSystemService(Context.ACCESSIBILITY_SERVICE);
    if (am != null) {
      List<AccessibilityServiceInfo> services =
          am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
      if (services != null) {
        String ourPackage = app.getPackageName();
        String ourName = AutoClickerService.class.getName();
        for (AccessibilityServiceInfo info : services) {
          if (info.getResolveInfo() != null
              && info.getResolveInfo().serviceInfo != null
              && ourPackage.equals(info.getResolveInfo().serviceInfo.packageName)
              && ourName.equals(info.getResolveInfo().serviceInfo.name)) {
            return true;
          }
        }
      }
    }

    String enabled = Settings.Secure.getString(
        app.getContentResolver(),
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    );
    if (TextUtils.isEmpty(enabled)) return false;
    String needle = new ComponentName(
        app.getPackageName(),
        AutoClickerService.class.getName()
    ).flattenToString();
    for (String entry : enabled.split(":")) {
      if (needle.equalsIgnoreCase(entry)) return true;
      ComponentName cn = ComponentName.unflattenFromString(entry);
      if (cn != null
          && app.getPackageName().equals(cn.getPackageName())
          && AutoClickerService.class.getName().equals(cn.getClassName())) {
        return true;
      }
    }
    return false;
  }

  /** Post check on main thread (safe from binder / NLS). */
  public static void checkAcceptOnWithoutA11yAsync(Context context, String reason) {
    if (context == null) return;
    Context app = context.getApplicationContext();
    Handler h = new Handler(Looper.getMainLooper());
    h.post(() -> checkAcceptOnWithoutA11y(app, reason));
  }
}
