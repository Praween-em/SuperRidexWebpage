package com.rapido.tap;

import android.app.Notification;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Locale;

/**
 * Earliest ride ping. Only forwards real ride alerts — never status/earnings pings.
 */
public class RideAlertListener extends NotificationListenerService {

  private static final String TAG = "RideAlertListener";
  private static final long SKIP_LOG_MIN_INTERVAL_MS = 1500L;

  private final StringBuilder scratchSb = new StringBuilder(256);
  private long lastSkipDisabledLogUptime = 0L;

  @Override
  public void onListenerConnected() {
    super.onListenerConnected();
    AutoClickerConfig.init(this);
    AutoClickerConfig.forceSyncCriticalFromDisk();
    Log.i(TAG, "NLS_CONNECTED enabled=" + AutoClickerConfig.isEnabled()
        + " nuclear=" + AutoClickerConfig.isNuclearMode());
  }

  @Override
  public void onNotificationPosted(StatusBarNotification sbn) {
    if (sbn == null) return;

    AutoClickerConfig.ensureInit(this);
    String pkg = sbn.getPackageName();
    if (pkg == null || !AutoClickerConfig.isPackageMonitored(pkg)) return;
    if (!AutoClickerConfig.isEnabled()) {
      long now = SystemClock.uptimeMillis();
      if (now - lastSkipDisabledLogUptime >= SKIP_LOG_MIN_INTERVAL_MS) {
        lastSkipDisabledLogUptime = now;
        Log.w(TAG, "SKIP_DISABLED pkg=" + pkg
            + " — Auto-accept OFF (turn ON in SUPER RIDEX Home)");
      }
      return;
    }

    final long tReceive = SystemClock.uptimeMillis();
    String text = notifText(sbn);
    Notification n = sbn.getNotification();

    if (AutoClickerService.isSpamText(text)) {
      return;
    }
    // Nuclear no longer arms on every Captain ping — only ride alerts
    if (!AutoClickerService.isRideAlert(text, n)) {
      return;
    }

    AutoClickerService.onRideSignal(pkg, n, text, tReceive, "NLS");
    Log.w(TAG, "NLS_POST pkg=" + pkg
        + " ongoing=" + sbn.isOngoing()
        + " rideAlert=true"
        + " textLen=" + (text != null ? text.length() : 0));
  }

  private String notifText(StatusBarNotification sbn) {
    StringBuilder sb = scratchSb;
    sb.setLength(0);
    Notification n = sbn.getNotification();
    if (n == null) return "";
    if (n.tickerText != null) sb.append(n.tickerText).append(' ');
    Bundle extras = n.extras;
    if (extras != null) {
      append(sb, extras.getCharSequence(Notification.EXTRA_TITLE));
      append(sb, extras.getCharSequence(Notification.EXTRA_TEXT));
      append(sb, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
      append(sb, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
      append(sb, extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
      append(sb, extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
      CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
      if (lines != null) {
        for (CharSequence line : lines) append(sb, line);
      }
    }
    if (n.actions != null) {
      for (Notification.Action a : n.actions) {
        if (a != null && a.title != null) sb.append(" action:").append(a.title).append(' ');
      }
    }
    return sb.toString().toLowerCase(Locale.US);
  }

  private static void append(StringBuilder sb, CharSequence v) {
    if (v != null) sb.append(v).append(' ');
  }
}
