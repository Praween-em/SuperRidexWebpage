package com.rapido.tap;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared config for a11y engine (MeClicker-style). UI writes; service reads.
 */
public final class AutoClickerConfig {

  private static final String PREFS = "superridex_race";

  /** Mode 0 = price filter; else = distance filter (MeClicker). */
  public static final int MODE_PRICE = 0;
  public static final int MODE_DISTANCE = 1;

  /** Default OFF — JS turns on only while subscription is active. */
  private static final AtomicBoolean enabled = new AtomicBoolean(false);
  private static final AtomicBoolean nuclearMode = new AtomicBoolean(true);
  /**
   * Continuous FG spray — HARD OFF. MeClicker hunt→click→micro-burst only.
   * Pref ignored; API always reports/stores false.
   */
  private static final AtomicBoolean continuousForegroundTap = new AtomicBoolean(false);
  /** Removed — always 0. Kept only so old prefs / bridge calls stay harmless. */
  private static final AtomicInteger minPrice = new AtomicInteger(0);
  private static final AtomicInteger delayMs = new AtomicInteger(0);
  private static final AtomicInteger filterMode = new AtomicInteger(MODE_PRICE);
  private static final AtomicReference<Float> maxPickup = new AtomicReference<>(99f);
  private static final AtomicReference<Float> maxDrop = new AtomicReference<>(0f);
  private static final AtomicLong lastClickAt = new AtomicLong(0);
  private static final Set<String> monitoredPackages = Collections.synchronizedSet(new HashSet<>());
  private static final Map<String, int[]> cachedTapByPackage =
      Collections.synchronizedMap(new HashMap<>());
  /** Accept coords on floating ride-alert overlays (separate from in-app CTA). */
  private static final Map<String, int[]> cachedOverlayTapByPackage =
      Collections.synchronizedMap(new HashMap<>());

  private static volatile SharedPreferences prefs;
  private static volatile Context appContext;

  static {
    // MeClicker targets + existing Playnix extras
    monitoredPackages.add("com.rapido.rider");
    monitoredPackages.add("com.rapido.captain");
    monitoredPackages.add("com.rapido.driver");
    monitoredPackages.add("com.olacabs.oladriver");
    monitoredPackages.add("com.olacabs.driver");
    monitoredPackages.add("com.rideandhra.driverapp");
    monitoredPackages.add("com.ubercab.driver");
  }

  private AutoClickerConfig() {}

  public static void init(Context context) {
    if (context == null) return;
    appContext = context.getApplicationContext();
    prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    reloadFromPrefs();
    // Persist defaults on first run so a11y/NLS/JS share the same prefs file
    if (!prefs.contains("enabled") || !prefs.contains("nuclear_mode")
        || prefs.getBoolean("continuous_fg_tap", false)) {
      prefs.edit()
          .putBoolean("enabled", enabled.get())
          .putBoolean("nuclear_mode", nuclearMode.get())
          .putBoolean("continuous_fg_tap", false)
          .putInt("min_price", 0)
          .putInt("delay_ms", delayMs.get())
          .apply();
    }
  }

  /** Re-read disk prefs into memory (NLS/a11y can start before JS Module). */
  public static void reloadFromPrefs() {
    SharedPreferences p = prefs;
    if (p == null && appContext != null) {
      prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
      p = prefs;
    }
    if (p == null) return;
    for (String pkg : monitoredPackages) {
      int x = p.getInt("tap_x_" + pkg, -1);
      int y = p.getInt("tap_y_" + pkg, -1);
      if (x > 0 && y > 0) {
        cachedTapByPackage.put(pkg, new int[] { x, y });
      }
      int ox = p.getInt("overlay_tap_x_" + pkg, -1);
      int oy = p.getInt("overlay_tap_y_" + pkg, -1);
      if (ox > 0 && oy > 0) {
        cachedOverlayTapByPackage.put(pkg, new int[] { ox, oy });
      }
    }
    filterMode.set(p.getInt("filter_mode", MODE_PRICE));
    maxPickup.set(p.getFloat("max_pickup", 99f));
    maxDrop.set(p.getFloat("max_drop", 0f));
    continuousForegroundTap.set(false); // HARD OFF — ignore stale pref
    minPrice.set(0); // min-fare feature removed
    nuclearMode.set(p.getBoolean("nuclear_mode", true));
    enabled.set(p.getBoolean("enabled", false));
    delayMs.set(Math.max(0, p.getInt("delay_ms", 0)));
  }

  /** Ensure prefs loaded before any enable check (safe from NLS binder thread). */
  public static void ensureInit(Context context) {
    if (prefs != null) return;
    if (context != null) {
      init(context);
    } else if (appContext != null) {
      init(appContext);
    }
  }

  public static Context getAppContext() {
    return appContext;
  }

  public static boolean isEnabled() {
    syncCriticalFromDisk();
    return enabled.get();
  }

  public static void setEnabled(boolean value) {
    enabled.set(value);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putBoolean("enabled", value).commit(); // commit — a11y must see it now
  }

  private static volatile long lastDiskSyncUptimeMs = 0L;

  /**
   * Re-read enabled/nuclear from disk. Fixes ColorOS process reuse where
   * in-memory AtomicBooleans stay false after prefs were updated (JS / adb).
   * Throttled — a11y hot path calls isEnabled() extremely often.
   */
  private static void syncCriticalFromDisk() {
    syncCriticalFromDisk(false);
  }

  /** Unthrottled sync — call from a11y/NLS onServiceConnected / onListenerConnected. */
  public static void forceSyncCriticalFromDisk() {
    syncCriticalFromDisk(true);
  }

  private static void syncCriticalFromDisk(boolean force) {
    if (prefs == null && appContext != null) {
      init(appContext);
    }
    SharedPreferences p = prefs;
    if (p == null) return;
    long now = android.os.SystemClock.uptimeMillis();
    if (!force && now - lastDiskSyncUptimeMs < 400L) return;
    lastDiskSyncUptimeMs = now;
    enabled.set(p.getBoolean("enabled", false));
    nuclearMode.set(p.getBoolean("nuclear_mode", true));
    minPrice.set(0); // min-fare feature removed
    delayMs.set(Math.max(0, p.getInt("delay_ms", 0)));
  }

  public static boolean isNuclearMode() {
    syncCriticalFromDisk();
    return nuclearMode.get();
  }

  public static void setNuclearMode(boolean value) {
    nuclearMode.set(value);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putBoolean("nuclear_mode", value).apply();
  }

  public static boolean isContinuousForegroundTap() {
    return continuousForegroundTap.get();
  }

  public static void setContinuousForegroundTap(boolean value) {
    // HARD OFF — ignore enable attempts (MeClicker path only)
    continuousForegroundTap.set(false);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putBoolean("continuous_fg_tap", false).apply();
  }

  /** Always 0 — min-fare filtering removed from the race engine. */
  public static int getMinPrice() {
    return 0;
  }

  /** No-op — kept so older JS / bridge callers do not crash. */
  public static void setMinPrice(int value) {
    minPrice.set(0);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putInt("min_price", 0).apply();
  }

  public static int getDelayMs() {
    return delayMs.get();
  }

  public static void setDelayMs(int value) {
    int v = Math.max(0, value);
    delayMs.set(v);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putInt("delay_ms", v).apply();
  }

  public static int getFilterMode() {
    return filterMode.get();
  }

  public static void setFilterMode(int mode) {
    filterMode.set(mode);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putInt("filter_mode", mode).apply();
  }

  public static float getMaxPickup() {
    Float v = maxPickup.get();
    return v != null ? v : 99f;
  }

  public static void setMaxPickup(float km) {
    maxPickup.set(km);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putFloat("max_pickup", km).apply();
  }

  public static float getMaxDrop() {
    Float v = maxDrop.get();
    return v != null ? v : 0f;
  }

  public static void setMaxDrop(float km) {
    maxDrop.set(km);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putFloat("max_drop", km).apply();
  }

  public static boolean isPackageMonitored(String packageName) {
    if (packageName == null) return false;
    return monitoredPackages.contains(packageName);
  }

  public static boolean isRapidoPackage(String packageName) {
    if (packageName == null) return false;
    // SUPER RIDEX itself is com.rapido.tap — NEVER treat it as Captain
    // (contains("rapido") used to match our app → continuous mid-screen taps)
    if (packageName.equals("com.rapido.tap")) return false;
    return packageName.equals("com.rapido.rider")
        || packageName.equals("com.rapido.captain")
        || packageName.equals("com.rapido.driver");
  }

  public static boolean isOlaPackage(String packageName) {
    if (packageName == null) return false;
    return packageName.equals("com.olacabs.oladriver")
        || packageName.equals("com.olacabs.driver")
        || packageName.contains("oladriver")
        || packageName.contains("olacabs");
  }

  public static boolean isUberPackage(String packageName) {
    if (packageName == null) return false;
    return packageName.equals("com.ubercab.driver")
        || packageName.equals("com.ubercab.driver.debug")
        || packageName.startsWith("com.ubercab.driver");
  }

  /** Rapido / Ola / Uber / RideAndhra — any monitored ride app. */
  public static boolean isRideAppPackage(String packageName) {
    if (packageName == null) return false;
    return isRapidoPackage(packageName)
        || isOlaPackage(packageName)
        || isUberPackage(packageName)
        || packageName.contains("rideandhra");
  }

  public static void setMonitoredPackages(Set<String> packages) {
    synchronized (monitoredPackages) {
      monitoredPackages.clear();
      if (packages != null) {
        monitoredPackages.addAll(packages);
      }
    }
  }

  public static Set<String> getMonitoredPackages() {
    synchronized (monitoredPackages) {
      return new HashSet<>(monitoredPackages);
    }
  }

  public static void cacheTapPoint(String packageName, int x, int y) {
    if (packageName == null || x <= 0 || y <= 0) return;
    cachedTapByPackage.put(packageName, new int[] { x, y });
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit()
          .putInt("tap_x_" + packageName, x)
          .putInt("tap_y_" + packageName, y)
          .apply();
    }
  }

  public static void cacheOverlayTapPoint(String packageName, int x, int y) {
    if (packageName == null || x <= 0 || y <= 0) return;
    cachedOverlayTapByPackage.put(packageName, new int[] { x, y });
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit()
          .putInt("overlay_tap_x_" + packageName, x)
          .putInt("overlay_tap_y_" + packageName, y)
          .apply();
    }
  }

  /** Returns cached array (do not mutate). Avoids per-call int[] alloc on spray path. */
  public static int[] getCachedTapPoint(String packageName) {
    if (packageName == null) return null;
    return cachedTapByPackage.get(packageName);
  }

  /** Returns cached array (do not mutate). */
  public static int[] getOverlayTapPoint(String packageName) {
    if (packageName == null) return null;
    return cachedOverlayTapByPackage.get(packageName);
  }

  /** Cached Accept point only — never invent a blind default. */
  public static int[] getKnownTapPoint(String packageName) {
    return getCachedTapPoint(packageName);
  }

  /** @deprecated Prefer getKnownTapPoint — blind defaults caused random taps. */
  @Deprecated
  public static int[] getRaceTapPoint(String packageName) {
    int[] cached = getCachedTapPoint(packageName);
    if (cached != null) return cached;
    Context ctx = appContext;
    if (ctx == null) return null;
    try {
      WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
      DisplayMetrics real = new DisplayMetrics();
      if (wm != null && wm.getDefaultDisplay() != null) {
        wm.getDefaultDisplay().getRealMetrics(real);
        return new int[] { real.widthPixels / 2, (int) (real.heightPixels * 0.92f) };
      }
    } catch (Exception ignored) {
    }
    DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
    return new int[] { dm.widthPixels / 2, (int) (dm.heightPixels * 0.92f) };
  }

  public static boolean hasCachedTapPoint(String packageName) {
    return packageName != null && cachedTapByPackage.containsKey(packageName);
  }

  /** Drop in-memory + prefs tap caches so post-Accept we never re-spray stale coords. */
  public static void clearCachedTapPoints(String packageName) {
    if (packageName == null) {
      cachedTapByPackage.clear();
      cachedOverlayTapByPackage.clear();
      return;
    }
    cachedTapByPackage.remove(packageName);
    cachedOverlayTapByPackage.remove(packageName);
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit()
          .remove("tap_x_" + packageName)
          .remove("tap_y_" + packageName)
          .remove("overlay_tap_x_" + packageName)
          .remove("overlay_tap_y_" + packageName)
          .apply();
    }
  }

  public static boolean tryAcquireClickLock(long debounceMs) {
    long now = System.currentTimeMillis();
    long last = lastClickAt.get();
    if (now - last < debounceMs) {
      return false;
    }
    return lastClickAt.compareAndSet(last, now);
  }
}
