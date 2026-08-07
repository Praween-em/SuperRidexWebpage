package com.rapido.tap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.ServiceInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Race engine (Accessibility) — Accept-first pipeline for almost all Android devices:
 * Step1: on-trip/nav chrome never blocks Accept hunt.
 * Step2: IDLE→ARMED→STRIKING→VERIFYING→COOLDOWN(~1s)→IDLE.
 * Step3: Armed hunt poll (stock ~4ms / OEM ~16ms) + multi-window Accept.
 * Step4: Armed overlay over other apps; bubble refuse when idle; APPLICATION overlays OK.
 * Step5: Exact Accept-center dual-strike (no card-center / blind taps).
 * Step6: Device tiers — stock fast; skinned/unknown = long press + slower polls (stay alive).
 * Step7: FOUND_ACCEPT / BLOCKED / PHASE / STRIKE0 logs.
 */
public class AutoClickerService extends AccessibilityService {

  private static final String TAG = "AutoClickerService";
  /** Extra race diagnostics — keep OFF so the click hot path stays light. */
  private static final boolean RACE_DEBUG_LOG = false;

  /** Accept-first race phases (Step 2). */
  private enum RacePhase {
    IDLE,
    ARMED,
    STRIKING,
    VERIFYING,
    COOLDOWN
  }

  private static final long COOLDOWN_MS = 800;
  /**
   * Soft gap between micro-bursts while Accept still visible (retry window).
   * Must NOT be multi-second — a miss must re-strike within tens of ms.
   */
  private static final long RAPIDO_BURST_GAP_MS = 8;
  /** Keep retrying Accept after a miss / partial hit (no long CD inside this window). */
  private static final long RAPIDO_RETRY_WINDOW_MS = 500;
  /**
   * Short COOLDOWN after verified Accept — competitors use near-zero;
   * keep just enough to avoid double-tap on the same card.
   */
  private static final long RACE_COOLDOWN_MS = 350;
  /** @deprecated Prefer {@link #RACE_COOLDOWN_MS}; kept equal for Ola/legacy paths. */
  @Deprecated
  private static final long RAPIDO_POST_HIT_COOLDOWN_MS = RACE_COOLDOWN_MS;
  private static final long OLA_ACCEPT_WINDOW_MS = 910;
  private static final long OLA_GESTURE_MS = 8;
  private static final long OLA_BURST_INTERVAL_MS = 10;
  private static final int OLA_BURST_COUNT = 16;
  /**
   * Strike-0 press length — ALL devices. Android min stroke = 1ms.
   * Longer presses are deferred to backup / micro-burst only (OEM skins).
   */
  private static final long RAPIDO_GESTURE_MS_STRIKE0 = 1;
  private static final long RAPIDO_GESTURE_MS_DEFAULT = 1;
  /** Micro-burst interval length — stock. */
  private static final long RAPIDO_GESTURE_MS_STOCK = 1;
  /**
   * Backup press after strike-0 — ALL OEMs (Realme/ColorOS swallow 1ms alone).
   * Stock phones tolerate the extra press; skins need it to register.
   */
  private static final long RAPIDO_GESTURE_MS_BACKUP = 40;
  /** Legacy alias — device profile still assigns rapidoGestureMs for micro-burst. */
  private static final long RAPIDO_GESTURE_MS_HEAVY = RAPIDO_GESTURE_MS_BACKUP;
  /** Ola/Uber short micro-burst after universal strike (not the old 16-tap spray). */
  private static final int SHARED_MICRO_EXTRA = 3;
  private static final long SHARED_MICRO_INTERVAL_MS = 4;
  /** Micro-burst interval after strike 0 (stock). */
  private static final long RAPIDO_MICRO_INTERVAL_MS = 2;
  private static final long RAPIDO_MICRO_INTERVAL_HEAVY_MS = 4;
  /** Extra dual strikes after the immediate first click (while verifying). */
  private static final int RAPIDO_MICRO_EXTRA = 2;
  private static final int RAPIDO_MICRO_EXTRA_HEAVY = 3;
  /**
   * Accept-hunt poll while racing — keep tight so late-paint Accept is still &lt;10ms of sighting.
   */
  private static final long ACCEPT_HUNT_POLL_MS = 2;
  private static final long ACCEPT_HUNT_POLL_HEAVY_MS = 4;
  /** Armed overlay hunt over other apps. */
  private static final long ARMED_BG_HUNT_POLL_MS = 4;
  private static final long ARMED_BG_HUNT_POLL_HEAVY_MS = 6;
  /** Skip duplicate empty walks during event storms (misses only — not while race hot). */
  private static final long EMPTY_HUNT_COALESCE_MS = 1;
  /** Coalesce WINDOW_CONTENT_CHANGED storms on heavy OEMs (main-thread protection). */
  private static final long CONTENT_EVENT_COALESCE_MS = 4;
  private static final long CONTENT_EVENT_COALESCE_HEAVY_MS = 8;
  /** Keep overlay bounds briefly after a confirmed Accept sighting. */
  private static final long OVERLAY_STICKY_MS = 700;
  /**
   * After NLS/ride ping, arm Accept hunts for BG overlay / late paint.
   * Extended on Accept sighting; capped by {@link #RACE_ARM_MAX_FROM_SIGNAL_MS}.
   */
  private static final long RACE_ARM_MS = 10000;
  /** Refresh arm TTL when Accept is seen (still within max from first signal). */
  private static final long RACE_ARM_EXTEND_MS = 2500;
  /** Hard cap from first arm of this ride signal. */
  private static final long RACE_ARM_MAX_FROM_SIGNAL_MS = 12000;
  /** After strike / PendingIntent: confirm Accept gone before COOLDOWN. */
  private static final long VERIFY_WINDOW_MS = 400;
  private static final long VERIFY_POLL_MS = 50;
  private static final int VERIFY_MAX_RESTRIKES = 2;
  /** After restrike window: soft-watch Accept-gone (no more taps; latch cooldown). */
  private static final long SOFT_CONFIRM_WINDOW_MS = 2500;
  private static final long SOFT_CONFIRM_POLL_MS = 120;
  private static final int TREE_WALK_CAP = 200;
  /** Climb to clickable parent — keep short so find→click stays under ~20ms. */
  private static final int PARENT_CLIMB_CLICK = 8;
  /**
   * Reject clickable ancestors larger than this × Accept-text area.
   * Full ride-card parents make center taps miss the Accept button.
   */
  private static final float MAX_CLICK_AREA_RATIO = 4f;
  private static final int PARENT_CLIMB_OLA = 4;
  private static final long SELF_FG_CACHE_MS = 100;
  private static final int FG_NOTIFY_ID = 7142;
  private static final String FG_CHANNEL_ID = "superridex_engine";
  /** Alias — COOLDOWN duration (was 2500ms; blocked along-route offers). */
  private static final long POST_ACCEPT_IGNORE_MS = RACE_COOLDOWN_MS;

  /** Light hot-path labels — one English findByText (case-insensitive) + Hindi. */
  private static final String[] ACCEPT_LABELS_FAST = {
      "Accept", "स्वीकार",
  };
  /** Full label set — used on all devices (low-end previously skipped these). */
  private static final String[] ACCEPT_LABELS = {
      "Accept", "ACCEPT", "Accept Ride", "स्वीकार",
      "Ride Accept", "Accept Now", "Accept Karo",
      "Accept Booking", "Accept Order", "Accept Trip", "Take Ride",
  };

  /**
   * On-trip / navigation chrome — context only.
   * Rapido still shows new Accept cards on map/nav while these are visible.
   * NEVER use these alone to disarm or skip Accept hunting.
   */
  private static final String[] ON_TRIP_CHROME_LABELS = {
      "Trip started", "Trip Started", "End Ride", "Complete Ride",
      "Go to Pickup", "Navigate to pickup", "On Trip", "Navigate",
  };
  /**
   * Post-accept evidence (Accept button usually gone). Used with Accept-gone for VERIFY only.
   * Do not treat as a hunt block — "Accepted" can appear while another offer still shows Accept.
   */
  private static final String[] JUST_ACCEPTED_LABELS = {
      "Accepted",
  };

  private static final String OLA_PRICE_ID = "com.olacabs.oladriver:id/tv_compact_price";
  private static final String OLA_PICKUP_ID = "com.olacabs.oladriver:id/tv_pickup_distance_time";
  private static final String OLA_DROP_ID = "com.olacabs.oladriver:id/tv_drop_distance_time";
  private static final String OLA_ACCEPT_ID = "com.olacabs.oladriver:id/btn_accept";

  private static final Pattern PRICE_COMBO =
      Pattern.compile("₹\\s*(\\d+(?:\\.\\d+)?)\\s*\\+\\s*₹\\s*(\\d+(?:\\.\\d+)?)");
  private static final Pattern PRICE_SINGLE =
      Pattern.compile("₹\\s*(\\d+(?:\\.\\d+)?)");
  private static final Pattern KM_PATTERN =
      Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*km", Pattern.CASE_INSENSITIVE);
  private static final Pattern FIRST_NUMBER =
      Pattern.compile("(\\d+(?:\\.\\d+)?)");
  /** Rs / INR / ₹ / रू — Rapido & OEM a11y text variants. */
  private static final Pattern PRICE_ANY =
      Pattern.compile(
          "(?:₹|रू|rs\\.?|inr)\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
  /** "120 Rs" / "150/-" / "Fare 90" when symbol is missing or reversed. */
  private static final Pattern PRICE_REVERSED =
      Pattern.compile(
          "(?:(?:fare|price|amt|amount)\\s*[:\\-]?\\s*)?(\\d{2,4}(?:\\.\\d+)?)\\s*(?:₹|रू|rs\\.?|inr|/-)",
          Pattern.CASE_INSENSITIVE);

  /** NLS follow-ups after immediate +0 hunt — spans adaptive arm for late overlays. */
  private static final long[] NLS_FOLLOW_DELAYS_MS = {
      // +0 already hunted in onRideSignal — start at 1ms, denser early window
      1, 3, 6, 10, 16, 24, 36, 50, 75, 110, 160, 240, 400, 700, 1200, 2400
  };
  /** Extra late pulses on heavy OEMs where Accept paints after the notif. */
  private static final long[] NLS_FOLLOW_DELAYS_HEAVY_MS = {
      1, 3, 6, 10, 16, 24, 36, 50, 75, 110, 160, 240, 400, 700, 1200, 2400, 4000, 6000
  };

  /**
   * Chat-head / float-icon rejection (density-independent dp).
   * Prefer missing a ride over reopening Captain via the float icon.
   * HyperOS / MIUI bubbles often ~72–180dp; some OEMs pad to ~200dp near-square.
   * Wide ride sheets (≥50% / ≥280dp) are exempt from bubble class ONLY when Accept is present
   * (enforced at hunt/click — bounds helper keeps wide sheets out of compact rules).
   */
  private static final int BUBBLE_MAX_DP = 180;
  private static final int BUBBLE_SQUARE_MAX_DP = 200;
  private static final float BUBBLE_ASPECT_MIN = 0.65f;
  private static final float BUBBLE_ASPECT_MAX = 1.4f;
  /** Window area below this fraction of screen → treat as bubble chrome (not ride card). */
  private static final float BUBBLE_AREA_FRAC = 0.12f;
  /** Height cap with area rule — float icon, not a bottom sheet. */
  private static final float BUBBLE_AREA_HEIGHT_FRAC = 0.40f;
  /** Width ≥ this fraction of screen → ride card / bottom sheet candidate. */
  private static final float RIDE_CARD_WIDTH_FRAC = 0.42f;
  /** Absolute width floor (dp) for ride-card exemption. */
  private static final int RIDE_CARD_MIN_WIDTH_DP = 240;

  private static volatile AutoClickerService sInstance;

  private final Handler handler = new Handler(Looper.getMainLooper());

  // ── Reused scratch objects (a11y/main thread only — never cross-thread) ──
  private final Rect scratchRect = new Rect();
  private final Rect scratchRect2 = new Rect();
  private final Path scratchPath = new Path();
  private final StringBuilder scratchSb = new StringBuilder(512);
  private final ArrayDeque<AccessibilityNodeInfo> scratchQueue = new ArrayDeque<>(64);
  /** Persistent overlay bounds — mutated via set(), never replaced on hot path. */
  private final Rect overlayCardBoundsRect = new Rect();
  /** Last seen Rapido float-icon window (dp-rejected) — hot-path gesture gate. */
  private final Rect lastBubbleWindowRect = new Rect();
  private volatile boolean lastBubbleWindowValid = false;
  private volatile long lastBubbleSeenAtMs = 0;
  private static final long BUBBLE_STICKY_MS = 1_200;

  private volatile long lastOlaAttemptMs = 0;
  /** Last micro-burst start — used with RAPIDO_BURST_GAP_MS inside retry window. */
  private volatile long lastRapidoAttemptMs = 0;
  /** Soft window: re-burst while Accept still visible (short gap only). */
  private volatile long rapidoRetryUntilMs = 0;
  /** Hard rest after retry window / burst success — not applied on a single miss. */
  private volatile long rapidoCooldownUntilMs = 0;
  private volatile long lastHuntAtMs = 0;
  private volatile boolean olaBurstLock = false;
  private volatile boolean rapidoBurstLock = false;
  private volatile boolean acceptHuntPollScheduled = false;
  private volatile boolean rapidoForeground = false;
  /** Ride alert floating as SYSTEM_ALERT_WINDOW / overlay card (may show over Home). */
  private volatile boolean rideOverlayActive = false;
  private volatile long raceArmedUntilMs = 0;
  /** Wall-clock of first arm for this ride signal (caps refreshRaceArm). */
  private volatile long raceArmedFromMs = 0;
  private volatile int overlayTapX = 0;
  private volatile int overlayTapY = 0;
  private volatile boolean overlayCardBoundsValid = false;
  private volatile String lastPkg = "com.rapido.rider";
  private volatile long overlayStickyUntilMs = 0;
  /** Live Rapido overlay confirmed by findBest — sticky alone must NOT unlock gestures. */
  private volatile long overlayLiveUntilMs = 0;
  /** True after we confirm an Accept hit until next NLS — blocks residual hunts briefly. */
  private volatile boolean acceptSuccessLatch = false;
  /** Hard wall-clock latch after Accept hit (survives NLS clear attempts). */
  private volatile long ignoreRapidoUntilMs = 0;
  /** Captain shows nav / End Ride chrome — still hunt Accept (along-route offers). */
  private volatile boolean driverOnTripChrome = false;
  /** Explicit Accept-first race phase (IDLE→ARMED→STRIKING→VERIFYING→COOLDOWN). */
  private volatile RacePhase racePhase = RacePhase.IDLE;
  /** When current racePhase was entered — detect stuck STRIKING/VERIFYING. */
  private volatile long racePhaseEnteredAtMs = 0;
  /** STRIKING without progress longer than this → force recover (OEM ANR / recycle crash). */
  /** Orphan STRIKING without progress — recover so ride 3+ is not blocked. */
  private static final long STUCK_STRIKING_MS = 900;
  /** VERIFYING longer than this → abandon and allow next ride. */
  private static final long STUCK_VERIFYING_MS = 1200;

  /** VERIFYING: strike/PendingIntent fired — wait for Accept gone before COOLDOWN. */
  private volatile boolean verifyingAccept = false;
  private volatile long verifyUntilMs = 0;
  private volatile int verifyRestrikeCount = 0;
  private volatile int verifyCx = 0;
  private volatile int verifyCy = 0;
  private volatile String verifyPkg = null;
  private volatile String verifyTag = null;
  private volatile long verifyFindAt = 0;
  /** True when verify started from notif Accept action (may succeed with no UI Accept). */
  private volatile boolean verifyFromPendingIntent = false;
  /** True when this race struck a live UI Accept (VERIFY success gate). */
  private volatile boolean uiStrikeValid = false;
  /** True only after Accept was visible post-strike — blocks fake success latch. */
  private volatile boolean acceptSeenDuringVerify = false;
  private volatile long softConfirmUntilMs = 0;

  // Screen metrics cache (refresh rarely)
  private int screenW;
  private int screenH;
  private float screenArea;
  private float density = 3f;
  private long screenMetricsAtMs = 0;
  private static final long SCREEN_METRICS_TTL_MS = 60_000;

  // NLS follow-up coalesce — one chain, cancel/restart on new ping
  private volatile boolean nlsFollowActive = false;
  /** Ride alert arrived during COOLDOWN — flush after ignore window (do not drop). */
  private volatile boolean pendingRideValid = false;
  private volatile String pendingRidePkg = null;
  private volatile Notification pendingRideNotif = null;
  private volatile String pendingRideText = null;
  private volatile long pendingRideT0 = 0;
  private volatile String pendingRideSource = null;
  private String nlsFollowPkg;
  private long nlsFollowT0;
  private int nlsFollowIndex;

  // Rapido micro-burst state (main-thread only for node ref)
  private AccessibilityNodeInfo rapidoBurstNode;
  private int rapidoBurstX;
  private int rapidoBurstY;
  private String rapidoBurstPkg;
  private String rapidoBurstTag;
  private long rapidoBurstT0;
  private int rapidoBurstIndex;
  private boolean rapidoBurstFirstOk;

  /** Cached Accept center from a real Rapido Accept node (gesture gate). */
  private volatile int cachedAcceptX = 0;
  private volatile int cachedAcceptY = 0;
  private volatile boolean cachedAcceptValid = false;
  private volatile String cachedAcceptPkg = null;
  /** Uptime when cache was last refreshed from a live Accept node. */
  private volatile long cachedAcceptAtMs = 0;
  /** CACHE_STRIKE only valid briefly after a real sighting. */
  private static final long CACHED_ACCEPT_STRIKE_MAX_AGE_MS = 12_000;

  // ── Performance / OEM profile (stock = fast; everyone else = reliable) ──
  private boolean lowEndDevice = false; // FG notify hint only
  private boolean heavyOem = false; // Skinned / unknown — longer press, slower polls, stay alive
  private boolean stockOem = false; // Pixel / Moto / known AOSP-like only
  private int treeWalkCap = TREE_WALK_CAP;
  private long acceptHuntPollMs = ACCEPT_HUNT_POLL_MS;
  private long armedBgHuntPollMs = ARMED_BG_HUNT_POLL_MS;
  private long contentEventCoalesceMs = CONTENT_EVENT_COALESCE_MS;
  private long rapidoGestureMs = RAPIDO_GESTURE_MS_DEFAULT;
  private int rapidoMicroExtraStrikes = RAPIDO_MICRO_EXTRA;
  private long rapidoMicroIntervalMs = RAPIDO_MICRO_INTERVAL_MS;
  private long[] nlsFollowDelays = NLS_FOLLOW_DELAYS_MS;
  private long raceArmMs = RACE_ARM_MS;
  private long a11yNotificationTimeoutMs = 40L;
  /** Last WINDOW_CONTENT_CHANGED handled (coalesce OEM event storms). */
  private volatile long lastContentEventAtMs = 0;
  private boolean foregroundStarted = false;
  private volatile long selfFgCachedAtMs = 0;
  private volatile boolean selfFgCached = false;
  /** Last empty Accept hunt (coalesce duplicate misses only — never suppress hits). */
  private volatile long lastEmptyHuntAtMs = 0;
  /** Throttle expensive missed-order tree dumps (not on every strike). */
  private volatile long lastMissedScanAtMs = 0;
  private volatile boolean lastMissedScanHit = false;
  private static final long MISSED_SCAN_THROTTLE_MS = 450;

  /**
   * Accept-hunt poll — FG ~6ms; armed BG ~20ms. findAccept → smartClick.
   */
  private final Runnable acceptHuntPollRunnable = new Runnable() {
    @Override
    public void run() {
      acceptHuntPollScheduled = false;
      try {
        if (!shouldRunAcceptHuntPoll()) {
          return;
        }
        long t0 = SystemClock.uptimeMillis();
        // While verifying a UI strike, burst owns restrikes — skip parallel hunt
        if (!verifyingAccept || verifyFromPendingIntent || !rapidoBurstLock) {
          huntAccept(lastPkg, t0, rapidoForeground ? "FgAcceptPoll" : "ArmedAcceptPoll", !rapidoForeground);
        }
        scheduleAcceptHuntPoll();
      } catch (Throwable t) {
        // Never let hunt crashes disable Accessibility ("app is malfunctioning")
        Log.e(TAG, "HUNT_POLL_SAFE", t);
        try {
          scheduleAcceptHuntPoll();
        } catch (Throwable ignored) {
        }
      }
    }
  };

  /** Extra dual strikes while VERIFYING (Accept still on screen). Always gesture. */
  private final Runnable rapidoMicroBurstRunnable = new Runnable() {
    @Override
    public void run() {
      try {
        runRapidoMicroBurstTick();
      } catch (Throwable t) {
        Log.e(TAG, "MICRO_BURST_SAFE", t);
        try { finishRapidoMicroBurst("crash"); } catch (Throwable ignored) {}
        try {
          if (racePhase == RacePhase.STRIKING) {
            setRacePhase(RacePhase.ARMED, "micro-crash");
          }
        } catch (Throwable ignored) {}
      }
    }
  };

  private void runRapidoMicroBurstTick() {
      if (!AutoClickerConfig.isEnabled() || isRapidoInteractionBlocked()) {
        finishRapidoMicroBurst("abort");
        return;
      }
      if (isSelfAppForeground() && !hasLiveRapidoOverlayCard() && !isAcceptRaceHot()) {
        clearStaleAcceptTapPoints("micro-self");
        finishRapidoMicroBurst("self-ui");
        return;
      }
      // Float icon: never keep striking bubble coords / bubble nodes
      if (rapidoBurstX > 0 && rapidoBurstY > 0 && pointInsideRapidoBubbleWindow(rapidoBurstX, rapidoBurstY)) {
        clearCachedAcceptPoint("bubble-point");
        finishRapidoMicroBurst("bubble");
        return;
      }
      if (rapidoBurstNode != null && isFloatingBubbleNode(rapidoBurstNode)) {
        clearCachedAcceptPoint("bubble-node");
        finishRapidoMicroBurst("bubble");
        return;
      }
      // Re-find live Accept each strike — never reuse stale mid-screen coords
      AccessibilityNodeInfo live = findAcceptNodeAnywhere();
      if (live == null) {
        clearStaleAcceptTapPoints("micro-no-accept");
        finishRapidoMicroBurst("no-accept");
        return;
      }
      AccessibilityNodeInfo target = null;
      try {
        live.getBoundsInScreen(scratchRect);
        if (scratchRect.isEmpty()
            || isExtremeTopChromeAccept(scratchRect)
            || isOversizedAcceptBounds(scratchRect)) {
          clearStaleAcceptTapPoints("micro-bad-bounds");
          finishRapidoMicroBurst("bad-bounds");
          return;
        }
        if (isBubbleLikeClickTarget(scratchRect) && !isAcceptRaceHot()) {
          clearStaleAcceptTapPoints("micro-bubble");
          finishRapidoMicroBurst("bad-bounds");
          return;
        }
        final int cx = scratchRect.centerX();
        final int cy = scratchRect.centerY();
        if (cx <= 0 || cy <= 0) {
          finishRapidoMicroBurst("bad-center");
          return;
        }
        target = resolveAcceptClickTarget(live);
        if (target == null) target = AccessibilityNodeInfo.obtain(live);
        rapidoBurstX = cx;
        rapidoBurstY = cy;
        verifyCx = cx;
        verifyCy = cy;
        // Sticky = Accept LABEL bounds only (never full-card → mid-screen)
        scratchRect2.set(scratchRect);
        markOverlayLive(scratchRect2, cx, cy);
        if (!rapidoForeground) setRideOverlayActive(true, "micro-burst");
        if (!canGestureAt(cx, cy)) {
          finishRapidoMicroBurst("gate");
          return;
        }
        dualStrikeAccept(target, cx, cy, true);
      } finally {
        try { live.recycle(); } catch (Exception ignored) {}
        if (target != null) {
          try { target.recycle(); } catch (Exception ignored) {}
        }
      }
      rapidoBurstIndex++;
      if (rapidoBurstIndex < rapidoMicroExtraStrikes && verifyingAccept) {
        handler.postDelayed(rapidoMicroBurstRunnable, rapidoMicroIntervalMs);
      } else {
        finishRapidoMicroBurst("done");
      }
  }

  /**
   * Post-VERIFY soft watcher — no gestures. If Accept disappears after a real UI
   * strike, latch cooldown. No JS bridge calls.
   */
  private final Runnable softConfirmRunnable = new Runnable() {
    @Override
    public void run() {
      try {
        runSoftConfirmTick();
      } catch (Throwable t) {
        Log.e(TAG, "SOFT_CONFIRM_SAFE", t);
        try {
          clearUiStrikeEvidence();
        } catch (Throwable ignored) {
        }
      }
    }
  };

  private void runSoftConfirmTick() {
      if (!uiStrikeValid) return;
      if (!AutoClickerConfig.isEnabled()) {
        clearUiStrikeEvidence();
        return;
      }
      long now = SystemClock.uptimeMillis();
      boolean acceptVisible = true;
      try {
        acceptVisible = acceptStillVisibleAnywhere();
      } catch (Exception ignored) {
      }
      // ONLY confirm when Accept was seen after our strike, then disappeared.
      if (acceptSeenDuringVerify && !acceptVisible) {
        disarmAfterAcceptSuccess("soft-accept-gone");
        return;
      }
      if (acceptVisible) {
        acceptSeenDuringVerify = true;
      }
      if (now < softConfirmUntilMs) {
        handler.postDelayed(softConfirmRunnable, SOFT_CONFIRM_POLL_MS);
      } else {
        clearUiStrikeEvidence();
        Log.i(TAG, "SOFT_CONFIRM_TIMEOUT");
      }
  }

  private void scheduleSoftConfirmWatch(String reason) {
    if (!uiStrikeValid) return;
    softConfirmUntilMs = SystemClock.uptimeMillis() + SOFT_CONFIRM_WINDOW_MS;
    handler.removeCallbacks(softConfirmRunnable);
    handler.postDelayed(softConfirmRunnable, SOFT_CONFIRM_POLL_MS);
    Log.i(TAG, "SOFT_CONFIRM_WATCH " + reason);
  }

  /**
   * VERIFYING poll: disarm only when Accept is gone or on-trip UI appears.
   * PendingIntent alone never soft-assumes success — keep hunting the overlay Accept.
   * If Accept stays visible, re-strike until budget expires — then stay armed (miss).
   */
  private final Runnable verifyAcceptRunnable = new Runnable() {
    @Override
    public void run() {
      try {
        runVerifyAcceptTick();
      } catch (Throwable t) {
        Log.e(TAG, "VERIFY_SAFE", t);
        try {
          cancelVerify("verify-crash");
          finishRapidoMicroBurst("verify-crash");
          clearFailurePathOverlay("verify-crash");
          recoverIfRaceStuck("verify-crash");
        } catch (Throwable ignored) {
        }
      }
    }
  };

  private void runVerifyAcceptTick() {
      if (!verifyingAccept) return;
      if (!AutoClickerConfig.isEnabled()) {
        cancelVerify("abort");
        return;
      }
      if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) {
        // Cooldown from a prior accept — exit VERIFY cleanly (do not leave phase stuck)
        cancelVerify("ignore-window");
        finishRapidoMicroBurst("ignore-window");
        return;
      }

      long now = SystemClock.uptimeMillis();
      boolean onTrip = rapidoLooksOnTripAnywhere();
      boolean acceptVisible = acceptStillVisibleAnywhere();
      if (onTrip) driverOnTripChrome = true;

      // Dead Missed-order card still shows Accept — never count as a win
      if (rootLooksLikeMissedOfferAnywhere()) {
        Log.i(TAG, "VERIFY_MISS missed-order-ui");
        cancelVerify("verify-missed-order");
        finishRapidoMicroBurst("verify-missed-order");
        clearUiStrikeEvidence();
        clearFailurePathOverlay("verify-missed-order");
        return;
      }

      // PendingIntent path FIRST — send() is not proof of accept.
      // Do NOT treat "Accept not in tree yet" as a win (that aborted all hunting).
      if (verifyFromPendingIntent) {
        if (acceptVisible) {
          acceptSeenDuringVerify = true;
          verifyFromPendingIntent = false; // fall through to UI restrike path
        } else if (now >= verifyUntilMs) {
          cancelVerify("pending-no-ui");
          finishRapidoMicroBurst("pending-no-ui");
          // No UI Accept → no success latch (PendingIntent alone is not a confirmed tap)
          clearUiStrikeEvidence();
          clearFailurePathOverlay("pending-no-ui");
          scheduleAcceptHuntPoll();
          return;
        } else {
          handler.postDelayed(verifyAcceptRunnable, VERIFY_POLL_MS);
          return;
        }
      }

      // Accept gone → confirm ONLY if we struck a live Accept this race
      if (!acceptVisible) {
        clearStaleAcceptTapPoints("verify-accept-gone");
        if (uiStrikeValid && acceptSeenDuringVerify) {
          disarmAfterAcceptSuccess(onTrip ? "verify-on-trip-gone" : "verify-gone");
        } else {
          // No real Accept tap this race — do not latch
          cancelVerify("verify-gone-no-strike");
          finishRapidoMicroBurst("verify-gone-no-strike");
          clearUiStrikeEvidence();
          clearFailurePathOverlay("verify-gone-no-strike");
        }
        return;
      }
      // Accept still on screen after our strike
      acceptSeenDuringVerify = true;

      // Accept still visible (even during on-trip chrome) — re-strike
      if (now < verifyUntilMs && verifyRestrikeCount < VERIFY_MAX_RESTRIKES) {
        refreshRaceArm("verify-restrick");
        verifyRestrikeCount++;
        if (verifyFromPendingIntent) {
          verifyFromPendingIntent = false;
        }
        AccessibilityNodeInfo hit = findAcceptNodeAnywhere();
        if (hit != null) {
          AccessibilityNodeInfo target = null;
          try {
            // Always gesture at Accept LABEL center — parent center is mid-card spam
            hit.getBoundsInScreen(scratchRect);
            int cx = scratchRect.centerX();
            int cy = scratchRect.centerY();
            target = resolveAcceptClickTarget(hit);
            if (target == null) target = AccessibilityNodeInfo.obtain(hit);
            if (cx > 0 && cy > 0 && !isBubbleLikeClickTarget(scratchRect)
                && !isOversizedAcceptBounds(scratchRect)) {
              verifyCx = cx;
              verifyCy = cy;
              cacheAcceptPoint(verifyPkg != null ? verifyPkg : lastPkg, cx, cy);
              scratchRect2.set(scratchRect);
              markOverlayLive(scratchRect2, cx, cy);
              if (!rapidoForeground) setRideOverlayActive(true, "verify-restrick");
              Log.i(TAG, "VERIFY_RESTRIKE @" + cx + "," + cy + " n=" + verifyRestrikeCount
                  + " onTripChrome=" + driverOnTripChrome);
              dualStrikeAccept(target, cx, cy, true);
            } else {
              clearStaleAcceptTapPoints("verify-bad-bounds");
            }
          } finally {
            try { hit.recycle(); } catch (Exception ignored) {}
            if (target != null) {
              try { target.recycle(); } catch (Exception ignored) {}
            }
          }
        }
        // No blind VERIFY_GESTURE — mid-screen spam when Accept node is gone
        handler.postDelayed(verifyAcceptRunnable, VERIFY_POLL_MS);
        return;
      }

      // Timeout with Accept still up — stop restrikes; soft-watch Accept-gone
      Log.i(TAG, "VERIFY_MISS stay-armed acceptStillVisible=true onTripChrome="
          + driverOnTripChrome);
      cancelVerify("verify-miss");
      finishRapidoMicroBurst("verify-miss");
      clearStaleAcceptTapPoints("verify-miss");
      // Keep arm for late paint, but do not leave sticky overlay flag forever
      if (!isRaceArmed()) {
        clearFailurePathOverlay("verify-miss");
      }
      scheduleSoftConfirmWatch("verify-miss");
  }

  /** When soft retry window ends without a new burst, apply post-hit cooldown only on verified hit. */
  private final Runnable rapidoRetryWindowEndRunnable = new Runnable() {
    @Override
    public void run() {
      long now = SystemClock.uptimeMillis();
      if (rapidoBurstLock || verifyingAccept) {
        // Burst / verify still running — re-check after it finishes
        handler.postDelayed(this, rapidoMicroIntervalMs * 2);
        return;
      }
      // Only after verified Accept latch — never on optimistic click boolean
      if (!acceptSuccessLatch) return;
      if (now >= rapidoRetryUntilMs && now >= rapidoCooldownUntilMs) {
        rapidoCooldownUntilMs = now + RAPIDO_POST_HIT_COOLDOWN_MS;
      }
    }
  };

  /** Coalesced NLS follow-up chain — single Runnable, no per-delay lambda alloc. */
  private final Runnable nlsFollowRunnable = new Runnable() {
    @Override
    public void run() {
      if (!AutoClickerConfig.isEnabled()
          || isRapidoInteractionBlocked() || shouldIdleForBubbleOnly()) {
        nlsFollowActive = false;
        return;
      }
      String pkg = nlsFollowPkg != null ? nlsFollowPkg : lastPkg;
      long t0 = nlsFollowT0;
      huntAccept(pkg, t0, "NlsHunt", !rapidoForeground);
      nlsFollowIndex++;
      if (nlsFollowIndex < nlsFollowDelays.length) {
        long delta = nlsFollowDelays[nlsFollowIndex]
            - nlsFollowDelays[nlsFollowIndex - 1];
        handler.postDelayed(this, Math.max(1, delta));
      } else {
        nlsFollowActive = false;
      }
    }
  };

  /** Drop BG arm leftovers when the NLS window expires without a new offer. */
  private final Runnable raceArmExpireRunnable = new Runnable() {
    @Override
    public void run() {
      if (isRaceArmed()) return;
      raceArmedFromMs = 0;
      handler.removeCallbacks(nlsFollowRunnable);
      nlsFollowActive = false;
      // Don't yank VERIFYING/STRIKING/COOLDOWN back to idle on arm TTL alone
      if (racePhase == RacePhase.ARMED || racePhase == RacePhase.IDLE) {
        setRacePhase(RacePhase.IDLE, "arm-expire");
      }
      // Always stop idle hunting/gestures when arm ends — even if Captain is FG
      if (!isRaceActive()) {
        stopAcceptHuntPoll("arm-expire");
        finishRapidoMicroBurst("arm-expire");
        clearStaleAcceptTapPoints("arm-expire");
        clearCachedAcceptPoint("arm-expire");
        clearFailurePathOverlay("arm-expire");
      }
    }
  };

  /** Guaranteed COOLDOWN → IDLE + flush any ride queued during ignore window. */
  private final Runnable cooldownExpireRunnable = new Runnable() {
    @Override
    public void run() {
      long now = SystemClock.uptimeMillis();
      if (now < ignoreRapidoUntilMs) {
        handler.postDelayed(this, Math.max(20L, ignoreRapidoUntilMs - now));
        return;
      }
      acceptSuccessLatch = false;
      if (racePhase == RacePhase.COOLDOWN) {
        setRacePhase(RacePhase.IDLE, "cooldown-timer");
      }
      flushPendingRideSignal();
    }
  };

  /** Start / restart coalesced NLS follow-ups (cancels prior chain). */
  private void scheduleNlsFollowups(String packageName, long tReceive) {
    handler.removeCallbacks(nlsFollowRunnable);
    nlsFollowPkg = packageName;
    nlsFollowT0 = tReceive;
    nlsFollowIndex = 0;
    nlsFollowActive = true;
    if (nlsFollowDelays.length == 0) {
      nlsFollowActive = false;
      return;
    }
    handler.postDelayed(nlsFollowRunnable, nlsFollowDelays[0]);
  }

  /**
   * Device profile for almost all phones:
   * - STOCK (Pixel/Moto/…): short tap + faster poll
   * - HEAVY (MIUI/ColorOS/FunTouch/One UI/Transsion/…): long tap + slower poll (stay alive)
   * - UNKNOWN: treat as HEAVY — never default to ultra-short taps that OEMs swallow
   */
  private void detectAndApplyLowEndProfile() {
    detectAndApplyDeviceProfile();
  }

  private void detectAndApplyDeviceProfile() {
    boolean low = false;
    try {
      ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
      if (am != null && am.isLowRamDevice()) low = true;
    } catch (Exception ignored) {
    }
    try {
      int cores = Runtime.getRuntime().availableProcessors();
      if (cores > 0 && cores <= 4) low = true;
    } catch (Exception ignored) {
    }
    lowEndDevice = low;

    String m = (Build.MANUFACTURER != null ? Build.MANUFACTURER : "").toLowerCase(Locale.US);
    String b = (Build.BRAND != null ? Build.BRAND : "").toLowerCase(Locale.US);
    String d = (Build.DISPLAY != null ? Build.DISPLAY : "").toLowerCase(Locale.US);
    String f = (Build.FINGERPRINT != null ? Build.FINGERPRINT : "").toLowerCase(Locale.US);
    String blob = m + " " + b + " " + d + " " + f;

    // Known near-stock — only these get the fast profile
    stockOem = m.contains("google") || b.contains("google") || b.contains("pixel")
        || m.contains("motorola") || b.contains("motorola") || b.contains("moto")
        || m.contains("nothing") || b.contains("nothing")
        || m.contains("fairphone") || b.contains("fairphone");

    // Skinned / aggressive OEMs + Samsung One UI + budget Transsion family
    heavyOem = m.contains("oppo") || m.contains("realme") || m.contains("oneplus")
        || m.contains("oplus") || m.contains("xiaomi") || m.contains("redmi")
        || m.contains("poco") || m.contains("vivo") || m.contains("iqoo")
        || m.contains("huawei") || m.contains("honor") || m.contains("samsung")
        || m.contains("tecno") || m.contains("infinix") || m.contains("itel")
        || m.contains("lava") || m.contains("micromax") || m.contains("lenovo")
        || b.contains("oppo") || b.contains("realme") || b.contains("oneplus")
        || b.contains("xiaomi") || b.contains("redmi") || b.contains("poco")
        || b.contains("vivo") || b.contains("iqoo") || b.contains("honor")
        || b.contains("samsung") || b.contains("tecno") || b.contains("infinix")
        || b.contains("itel")
        || blob.contains("hyperos") || blob.contains("miui")
        || blob.contains("coloros") || blob.contains("funtouch")
        || blob.contains("originos") || blob.contains("oxygenos")
        || blob.contains("oneui") || blob.contains("magicui")
        || blob.contains("emui");

    // Unknown / uncommon brands: SAFE = heavy timing (register taps, avoid a11y kill)
    if (!stockOem) {
      heavyOem = true;
    }

    treeWalkCap = TREE_WALK_CAP;
    acceptHuntPollMs = ACCEPT_HUNT_POLL_MS;
    rapidoMicroIntervalMs = RAPIDO_MICRO_INTERVAL_MS;
    contentEventCoalesceMs = CONTENT_EVENT_COALESCE_MS;
    a11yNotificationTimeoutMs = 40L;

    if (heavyOem) {
      rapidoGestureMs = RAPIDO_GESTURE_MS_HEAVY;
      rapidoMicroExtraStrikes = RAPIDO_MICRO_EXTRA_HEAVY;
      rapidoMicroIntervalMs = RAPIDO_MICRO_INTERVAL_HEAVY_MS;
      acceptHuntPollMs = ACCEPT_HUNT_POLL_HEAVY_MS;
      armedBgHuntPollMs = ARMED_BG_HUNT_POLL_HEAVY_MS;
      contentEventCoalesceMs = CONTENT_EVENT_COALESCE_HEAVY_MS;
      nlsFollowDelays = NLS_FOLLOW_DELAYS_HEAVY_MS;
      raceArmMs = Math.max(RACE_ARM_MS, 10000L);
      a11yNotificationTimeoutMs = 80L;
    } else {
      rapidoGestureMs = RAPIDO_GESTURE_MS_STOCK;
      rapidoMicroExtraStrikes = RAPIDO_MICRO_EXTRA;
      rapidoMicroIntervalMs = RAPIDO_MICRO_INTERVAL_MS;
      acceptHuntPollMs = ACCEPT_HUNT_POLL_MS;
      armedBgHuntPollMs = ARMED_BG_HUNT_POLL_MS;
      contentEventCoalesceMs = CONTENT_EVENT_COALESCE_MS;
      nlsFollowDelays = NLS_FOLLOW_DELAYS_MS;
      raceArmMs = RACE_ARM_MS;
      a11yNotificationTimeoutMs = 40L;
    }
    if (low) {
      // Keep strike-0 at 8ms; only slow polls slightly on very weak SoCs
      acceptHuntPollMs = Math.max(acceptHuntPollMs, 3L);
      armedBgHuntPollMs = Math.max(armedBgHuntPollMs, 5L);
    }
    Log.i(TAG, "DEVICE_PROFILE heavyOem=" + heavyOem
        + " stockOem=" + stockOem
        + " gestMs=" + rapidoGestureMs
        + " huntPoll=" + acceptHuntPollMs
        + " armMs=" + raceArmMs
        + " mfr=" + Build.MANUFACTURER
        + " brand=" + Build.BRAND
        + " lowEnd=" + lowEndDevice);
  }

  /** Restore last Accept center from disk so NLS can strike before the tree paints. */
  private void hydrateCachedAcceptFromDisk() {
    try {
      for (String pkg : new String[] {
          "com.rapido.rider", "com.rapido.captain", "com.rapido.driver"
      }) {
        int[] pt = AutoClickerConfig.getCachedTapPoint(pkg);
        if (pt != null && pt.length >= 2 && pt[0] > 0 && pt[1] > 0) {
          cachedAcceptX = pt[0];
          cachedAcceptY = pt[1];
          cachedAcceptPkg = pkg;
          cachedAcceptValid = true;
          cachedAcceptAtMs = 0; // disk only — CACHE_STRIKE waits for live sighting
          Log.i(TAG, "CACHE_HYDRATE @" + pt[0] + "," + pt[1] + " pkg=" + pkg);
          return;
        }
      }
    } catch (Exception ignored) {
    }
  }

  /**
   * Instant exact-center tap at last known Accept — only when Accept is still live.
   * Never blind-tap stale Home/WhatsApp coords.
   */
  private void fireCachedAcceptStrike(String pkg, long t0, String source) {
    if (!cachedAcceptValid || cachedAcceptX <= 0 || cachedAcceptY <= 0) return;
    long age = SystemClock.uptimeMillis() - cachedAcceptAtMs;
    if (cachedAcceptAtMs <= 0 || age > CACHED_ACCEPT_STRIKE_MAX_AGE_MS) return;
    if (isRapidoInteractionBlocked() || !canStartRapidoBurst()) return;
    // Fast path: if Accept node is live near cache, use smartClick (full dual-strike)
    AccessibilityNodeInfo live = null;
    try {
      live = findAcceptNodeAnywhere();
      if (live != null) {
        live.getBoundsInScreen(scratchRect);
        int x = cachedAcceptX;
        int y = cachedAcceptY;
        if (!scratchRect.isEmpty()
            && !isExtremeTopChromeAccept(scratchRect)
            && !isBubbleLikeClickTarget(scratchRect)
            && Math.abs(scratchRect.centerX() - x) <= Math.max(96, scratchRect.width())
            && Math.abs(scratchRect.centerY() - y) <= Math.max(96, scratchRect.height())) {
          String usePkg = pkg != null ? pkg : cachedAcceptPkg;
          smartClickAccept(live, usePkg, "CacheLive/" + source, t0);
          live = null; // owned by smartClick
          return;
        }
      }
    } catch (Exception ignored) {
    } finally {
      if (live != null) {
        try { live.recycle(); } catch (Exception ignored) {}
      }
    }
  }

  /** True when UI is the Missed-order card (Accept still drawn but ride is dead). */
  private boolean rootLooksLikeMissedOffer(AccessibilityNodeInfo root) {
    if (root == null) return false;
    try {
      String t = dumpText(root);
      if (t == null || t.isEmpty()) return false;
      String h = t.toLowerCase(Locale.US);
      if (h.contains("you missed the order")) return true;
      if (h.contains("missed the order")) return true;
      if (h.contains("accepted by another captain")) return true;
      if (h.contains("accepted by another")) return true;
      if (h.contains("order was accepted by another")) return true;
      if (h.contains("order expired") || h.contains("offer expired")) return true;
      if (h.contains("no longer available") || h.contains("not available")) return true;
      if (h.contains("ride cancelled") || h.contains("booking cancelled")) return true;
      if (h.contains("already taken") || h.contains("taken by another")) return true;
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean rootLooksLikeMissedOfferAnywhere() {
    long now = SystemClock.uptimeMillis();
    if (now - lastMissedScanAtMs < MISSED_SCAN_THROTTLE_MS) {
      return lastMissedScanHit;
    }
    lastMissedScanAtMs = now;
    boolean hit = false;
    AccessibilityNodeInfo active = null;
    try {
      active = getRootInActiveWindow();
      if (rootLooksLikeMissedOffer(active)) hit = true;
    } catch (Exception ignored) {
    } finally {
      if (active != null) {
        try { active.recycle(); } catch (Exception ignored) {}
      }
    }
    if (!hit) {
      try {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
          for (AccessibilityWindowInfo w : windows) {
            if (w == null) continue;
            AccessibilityNodeInfo root = null;
            try {
              root = w.getRoot();
              if (root != null && allowAsRapidoWindow(packageOf(root), lastPkg)
                  && rootLooksLikeMissedOffer(root)) {
                hit = true;
                break;
              }
            } catch (Exception ignored) {
            } finally {
              if (root != null) {
                try { root.recycle(); } catch (Exception ignored) {}
              }
            }
          }
        }
      } catch (Exception ignored) {
      }
    }
    lastMissedScanHit = hit;
    return hit;
  }

  private void syncEngineForeground() {
    if (AutoClickerConfig.isEnabled()) {
      startEngineForeground();
    } else {
      stopEngineForeground();
    }
  }

  private void startEngineForeground() {
    if (foregroundStarted) return;
    try {
      ensureFgChannel();
      int icon = getApplicationInfo().icon;
      if (icon == 0) icon = android.R.drawable.ic_dialog_info;
      Notification.Builder b;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        b = new Notification.Builder(this, FG_CHANNEL_ID);
      } else {
        b = new Notification.Builder(this);
      }
      // No contentIntent — tapping / OEM quirks must not reopen SUPER RIDEX UI
      Notification.Builder nb = b
          .setContentTitle("SUPER RIDEX")
          .setContentText("Accept engine running")
          .setSmallIcon(icon)
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .setContentIntent(null);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        nb.setCategory(Notification.CATEGORY_SERVICE);
      }
      if (Build.VERSION.SDK_INT >= 21) {
        nb.setVisibility(Notification.VISIBILITY_SECRET);
      }
      // Silent via channel (IMPORTANCE_MIN) + no sound/vibrate.
      // Avoid Notification.Builder#setSilent (API 29) — not on all compile SDK stubs.
      try {
        nb.setSound(null).setVibrate(null);
      } catch (Throwable ignored) {}
      Notification n = nb.build();
      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(FG_NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      } else {
        startForeground(FG_NOTIFY_ID, n);
      }
      foregroundStarted = true;
    } catch (Exception e) {
      Log.w(TAG, "ENGINE_FOREGROUND failed: " + e.getMessage());
    }
  }

  private void stopEngineForeground() {
    if (!foregroundStarted) return;
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE);
      } else {
        stopForeground(true);
      }
    } catch (Exception ignored) {
    }
    foregroundStarted = false;
  }

  private void ensureFgChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    try {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (nm == null) return;
      if (nm.getNotificationChannel(FG_CHANNEL_ID) != null) return;
      NotificationChannel ch = new NotificationChannel(
          FG_CHANNEL_ID,
          "SUPER RIDEX engine",
          NotificationManager.IMPORTANCE_MIN
      );
      ch.setDescription("Keeps Accept race engine alive");
      ch.setShowBadge(false);
      ch.setSound(null, null);
      ch.enableVibration(false);
      nm.createNotificationChannel(ch);
    } catch (Exception ignored) {
    }
  }

  private void ensureScreenMetrics() {
    long now = SystemClock.uptimeMillis();
    if (screenArea > 0 && now - screenMetricsAtMs < SCREEN_METRICS_TTL_MS) return;
    try {
      android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
      screenW = dm.widthPixels;
      screenH = dm.heightPixels;
      density = dm.density > 0f ? dm.density : 3f;
      screenArea = (float) screenW * (float) screenH;
      screenMetricsAtMs = now;
    } catch (Exception ignored) {
    }
  }

  private int dp(int v) {
    ensureScreenMetrics();
    return (int) (v * density + 0.5f);
  }

  /** Aspect ratio (larger/smaller) for near-square chat-head detection. */
  private static float bubbleAspect(int w, int h) {
    if (w <= 0 || h <= 0) return 999f;
    return w >= h ? (float) w / (float) h : (float) h / (float) w;
  }

  private static boolean isNearSquareBubbleAspect(float aspect) {
    return aspect >= BUBBLE_ASPECT_MIN && aspect <= BUBBLE_ASPECT_MAX;
  }

  /**
   * Wide ride card / bottom sheet — never classify as float-icon bubble.
   * Captain home sheets are often moderate height but ≥50% screen (or ≥280dp) wide.
   */
  private boolean isRideCardSizedBounds(Rect r) {
    if (r == null || r.isEmpty()) return false;
    ensureScreenMetrics();
    int w = r.width();
    if (w >= dp(RIDE_CARD_MIN_WIDTH_DP)) return true;
    return screenW > 0 && w >= screenW * RIDE_CARD_WIDTH_FRAC;
  }

  /**
   * Chat-head / floating Rapido launcher window — never a ride Accept card.
   * Uses dp so xxhdpi HyperOS bubbles and mdpi stock icons both reject.
   * Wide sheets (≥50% / ≥280dp) skip compact rules; Accept presence is required
   * separately before any click/hunt treats them as ride surfaces.
   */
  private boolean isFloatingBubbleBounds(Rect r) {
    if (r == null || r.isEmpty()) return true;
    ensureScreenMetrics();
    int w = r.width();
    int h = r.height();
    // Wide ride card / bottom sheet: not a float icon by geometry alone
    if (isRideCardSizedBounds(r)) return false;

    int maxBubble = dp(BUBBLE_MAX_DP);
    int squareMax = dp(BUBBLE_SQUARE_MAX_DP);
    float aspect = bubbleAspect(w, h);

    // Both sides ≤ ~180dp → float icon (any aspect)
    if (w <= maxBubble && h <= maxBubble) return true;

    // Near-square chat-head (max side ≤ ~200dp, aspect ~0.65–1.4)
    if (Math.max(w, h) <= squareMax && isNearSquareBubbleAspect(aspect)) return true;

    // Degenerate strip chrome (window-level only — not Accept CTAs)
    if (w < dp(80) || (h < dp(80) && w < screenW * 0.40f)) return true;

    // Small overlay: area < 12% AND width < 50% AND height < 40% → float icon
    if (screenArea > 0f && screenW > 0 && screenH > 0) {
      float area = (float) w * (float) h;
      if (area > 0f
          && area < screenArea * BUBBLE_AREA_FRAC
          && w < screenW * RIDE_CARD_WIDTH_FRAC
          && h < screenH * BUBBLE_AREA_HEIGHT_FRAC) {
        return true;
      }
    }
    return false;
  }

  /**
   * Node / tap-target bubble check. Compact near-square icon only —
   * does NOT reject wide×short Accept buttons (both sides may be ≤180dp but aspect is flat).
   */
  private boolean isBubbleLikeClickTarget(Rect r) {
    if (r == null || r.isEmpty()) return true;
    // Wide Accept CTAs / sheet rows — never treat as float icon
    if (isRideCardSizedBounds(r)) return false;
    ensureScreenMetrics();
    int w = r.width();
    int h = r.height();
    int maxBubble = dp(BUBBLE_MAX_DP);
    int squareMax = dp(BUBBLE_SQUARE_MAX_DP);
    float aspect = bubbleAspect(w, h);

    // Compact near-square (icon), not a flat Accept bar
    if (w <= maxBubble && h <= maxBubble && isNearSquareBubbleAspect(aspect)) return true;
    // Near-square padded chat-head (max ≤ ~200dp)
    if (Math.max(w, h) <= squareMax && isNearSquareBubbleAspect(aspect)) return true;

    // Icon-tiny area (≪ Accept CTA)
    if (screenArea > 0f && screenW > 0 && screenH > 0) {
      float area = (float) w * (float) h;
      if (area > 0f
          && area < screenArea * BUBBLE_AREA_FRAC
          && w < screenW * RIDE_CARD_WIDTH_FRAC
          && h < screenH * BUBBLE_AREA_HEIGHT_FRAC
          && isNearSquareBubbleAspect(aspect)) {
        return true;
      }
    }
    return false;
  }

  /** True if this node's bounds or its window look like the float icon. */
  private boolean isFloatingBubbleNode(AccessibilityNodeInfo node) {
    if (node == null) return true;
    try {
      node.getBoundsInScreen(scratchRect);
      if (isBubbleLikeClickTarget(scratchRect)) return true;
    } catch (Exception ignored) {
    }
    try {
      AccessibilityWindowInfo w = node.getWindow();
      if (w != null) {
        w.getBoundsInScreen(scratchRect2);
        if (isFloatingBubbleBounds(scratchRect2)) {
          noteBubbleWindow(scratchRect2);
          return true;
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  /** Remember a Rapido float-icon window for cheap gesture/cache gates. */
  private void noteBubbleWindow(Rect r) {
    if (r == null || r.isEmpty()) return;
    lastBubbleWindowRect.set(r);
    lastBubbleWindowValid = true;
    lastBubbleSeenAtMs = SystemClock.uptimeMillis();
  }

  private boolean pointInsideKnownBubble(int x, int y) {
    if (!lastBubbleWindowValid || x <= 0 || y <= 0) return false;
    if (SystemClock.uptimeMillis() - lastBubbleSeenAtMs > BUBBLE_STICKY_MS) {
      lastBubbleWindowValid = false;
      return false;
    }
    return lastBubbleWindowRect.contains(x, y);
  }

  /** True if (x,y) sits inside any Rapido-sized float-icon window. */
  private boolean pointInsideRapidoBubbleWindow(int x, int y) {
    if (x <= 0 || y <= 0) return false;
    if (pointInsideKnownBubble(x, y)) return true;
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows == null) return false;
      for (AccessibilityWindowInfo w : windows) {
        if (w == null) continue;
        try {
          w.getBoundsInScreen(scratchRect);
          if (!isFloatingBubbleBounds(scratchRect)) continue;
          if (!scratchRect.contains(x, y)) continue;
          AccessibilityNodeInfo root = w.getRoot();
          if (root == null) continue;
          try {
            if (allowAsRapidoWindow(packageOf(root), lastPkg)) {
              noteBubbleWindow(scratchRect);
              return true;
            }
          } finally {
            root.recycle();
          }
        } catch (Exception ignored) {
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  /** Hard latch during COOLDOWN only — expires into IDLE so next ride can arm. */
  private boolean isRapidoInteractionBlocked() {
    long now = SystemClock.uptimeMillis();
    if (now < ignoreRapidoUntilMs) return true;
    // COOLDOWN window over — return to IDLE (latch must not stick forever)
    if (racePhase == RacePhase.COOLDOWN || acceptSuccessLatch) {
      acceptSuccessLatch = false;
      if (racePhase == RacePhase.COOLDOWN) {
        setRacePhase(RacePhase.IDLE, "cooldown-expired");
      }
    }
    // Opportunistic recover so FG hunts aren't stuck after OEM crash mid-strike
    recoverIfRaceStuck("blocked-check");
    return false;
  }

  private void setRacePhase(RacePhase next, String reason) {
    if (next == null) next = RacePhase.IDLE;
    RacePhase prev = racePhase;
    if (prev == next) return;
    racePhase = next;
    racePhaseEnteredAtMs = SystemClock.uptimeMillis();
    Log.i(TAG, "PHASE " + prev + "→" + next + " (" + reason + ")");
    // Do not auto-hunt on Captain FG after cooldown — wait for next ride alert
  }

  /**
   * Clear orphan STRIKING/VERIFYING / burst locks that permanently block rides 3+.
   * HyperOS/ColorOS/FunTouch often throw mid-strike; without recover, canStartRapidoBurst
   * stays false forever and only the first 1–3 offers ever get tapped.
   */
  private void recoverIfRaceStuck(String reason) {
    long now = SystemClock.uptimeMillis();
    long age = racePhaseEnteredAtMs > 0 ? (now - racePhaseEnteredAtMs) : 0;
    boolean orphanVerify = racePhase == RacePhase.VERIFYING && !verifyingAccept;
    boolean orphanStrike = racePhase == RacePhase.STRIKING && !rapidoBurstLock && age > 600;
    boolean stuckStrike = racePhase == RacePhase.STRIKING && age > STUCK_STRIKING_MS;
    boolean stuckVerify = racePhase == RacePhase.VERIFYING && age > STUCK_VERIFYING_MS;
    boolean staleLock = rapidoBurstLock
        && (racePhase == RacePhase.IDLE || racePhase == RacePhase.ARMED || racePhase == RacePhase.COOLDOWN)
        && age > 800;

    if (!(orphanVerify || orphanStrike || stuckStrike || stuckVerify || staleLock)) {
      return;
    }
    Log.w(TAG, "RACE_RECOVER reason=" + reason
        + " phase=" + racePhase
        + " ageMs=" + age
        + " verifying=" + verifyingAccept
        + " burstLock=" + rapidoBurstLock);
    try {
      finishRapidoMicroBurst("recover");
    } catch (Throwable ignored) {
    }
    try {
      cancelVerify("recover/" + reason);
    } catch (Throwable ignored) {
    }
    verifyingAccept = false;
    rapidoBurstLock = false;
    if (racePhase == RacePhase.STRIKING || racePhase == RacePhase.VERIFYING) {
      setRacePhase(RacePhase.IDLE, "recover/" + reason);
    }
    clearFailurePathOverlay("recover/" + reason);
  }

  /**
   * Miss / expire / recover must clear overlay flag — otherwise IDLE+rideOverlayActive
   * keeps shouldRunAcceptHuntPoll true forever → OEM a11y kill after 2–3 rides.
   */
  private void clearFailurePathOverlay(String reason) {
    setRideOverlayActive(false, reason);
    clearBlindSprayTargets(reason);
    overlayCardBoundsValid = false;
    overlayCardBoundsRect.setEmpty();
    overlayLiveUntilMs = 0;
    if (!isRaceArmed()
        && racePhase != RacePhase.ARMED
        && racePhase != RacePhase.STRIKING
        && racePhase != RacePhase.VERIFYING
        && !verifyingAccept) {
      stopAcceptHuntPoll(reason);
    }
  }

  /**
   * Full race teardown — connect / interrupt / destroy. Leaves disk Accept cache intact
   * unless reason contains destroy.
   */
  private void resetRaceEngine(String reason) {
    Log.w(TAG, "RACE_RESET " + reason);
    handler.removeCallbacks(acceptHuntPollRunnable);
    handler.removeCallbacks(rapidoMicroBurstRunnable);
    handler.removeCallbacks(rapidoRetryWindowEndRunnable);
    handler.removeCallbacks(nlsFollowRunnable);
    handler.removeCallbacks(raceArmExpireRunnable);
    handler.removeCallbacks(verifyAcceptRunnable);
    handler.removeCallbacks(softConfirmRunnable);
    handler.removeCallbacks(cooldownExpireRunnable);
    acceptHuntPollScheduled = false;
    nlsFollowActive = false;
    verifyingAccept = false;
    verifyFromPendingIntent = false;
    verifyUntilMs = 0;
    verifyRestrikeCount = 0;
    acceptSeenDuringVerify = false;
    softConfirmUntilMs = 0;
    try {
      finishRapidoMicroBurst("reset");
    } catch (Throwable ignored) {
    }
    rapidoBurstLock = false;
    olaBurstLock = false;
    acceptSuccessLatch = false;
    ignoreRapidoUntilMs = 0;
    raceArmedUntilMs = 0;
    raceArmedFromMs = 0;
    rapidoCooldownUntilMs = 0;
    rapidoRetryUntilMs = 0;
    clearFailurePathOverlay("reset/" + reason);
    clearStaleAcceptTapPoints("reset/" + reason);
    clearUiStrikeEvidence();
    pendingRideValid = false;
    pendingRideNotif = null;
    pendingRidePkg = null;
    pendingRideText = null;
    pendingRideSource = null;
    driverOnTripChrome = false;
    lastMissedScanHit = false;
    lastBubbleWindowValid = false;
    if (reason != null && reason.contains("destroy")) {
      clearCachedAcceptPoint("destroy");
    }
    racePhase = RacePhase.IDLE;
    racePhaseEnteredAtMs = SystemClock.uptimeMillis();
  }

  private void scheduleCooldownExpire() {
    handler.removeCallbacks(cooldownExpireRunnable);
    long delay = Math.max(30L, ignoreRapidoUntilMs - SystemClock.uptimeMillis() + 10L);
    handler.postDelayed(cooldownExpireRunnable, delay);
  }

  /** Queue a ride that arrived during COOLDOWN instead of dropping it. */
  private void queueRideSignal(
      String packageName,
      Notification notification,
      String notifText,
      long tReceive,
      String source
  ) {
    pendingRidePkg = packageName;
    pendingRideNotif = notification;
    pendingRideText = notifText;
    pendingRideT0 = tReceive;
    pendingRideSource = source != null ? source : "queued";
    pendingRideValid = true;
    scheduleCooldownExpire();
    Log.i(TAG, "RIDE_QUEUED_COOLDOWN src=" + source + " pkg=" + packageName);
  }

  private void flushPendingRideSignal() {
    if (!pendingRideValid) return;
    if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) {
      scheduleCooldownExpire();
      return;
    }
    String pkg = pendingRidePkg;
    Notification n = pendingRideNotif;
    String text = pendingRideText;
    long t0 = pendingRideT0 > 0 ? pendingRideT0 : SystemClock.uptimeMillis();
    String src = pendingRideSource != null ? pendingRideSource : "queued";
    pendingRideValid = false;
    pendingRideNotif = null;
    pendingRidePkg = null;
    pendingRideText = null;
    pendingRideSource = null;
    if (pkg == null) return;
    Log.i(TAG, "RIDE_FLUSH_QUEUED src=" + src + " pkg=" + pkg);
    onRideSignal(pkg, n, text, t0, src + "/after-cd");
  }

  /**
   * Every new ride ping must be allowed to arm/strike. Prior accept leftover state
   * (stuck VERIFY, burst lock, bubble sticky, missed-scan cache) caused
   * "first 2–3 OK then never again" on Redmi/Vivo/Oppo.
   */
  private void prepareForNewRideSignal(String source) {
    // Always clear stuck Ola/Uber lock first (even during short CD)
    if (olaBurstLock) {
      olaBurstLock = false;
    }
    recoverIfRaceStuck("new-ride/" + source);

    // NEW OFFER INTERRUPT — do not wait 1.5–3.5s in VERIFY (main miss vs Play Store clickers).
    // Cancel in-flight strike/verify so this NLS can hunt immediately.
    boolean interrupting = verifyingAccept
        || rapidoBurstLock
        || racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING;
    if (interrupting) {
      long age = racePhaseEnteredAtMs > 0
          ? SystemClock.uptimeMillis() - racePhaseEnteredAtMs : 0;
      // Allow a tiny settle so strike-0 of the SAME offer can finish (~50ms)
      if (age > 50L || source != null && (source.contains("NLS") || source.contains("A11y"))) {
        Log.i(TAG, "NEW_RIDE_INTERRUPT src=" + source + " ageMs=" + age + " phase=" + racePhase);
        try { cancelVerify("new-ride-interrupt"); } catch (Throwable ignored) {}
        try { finishRapidoMicroBurst("new-ride-interrupt"); } catch (Throwable ignored) {}
        verifyingAccept = false;
        rapidoBurstLock = false;
        if (racePhase == RacePhase.STRIKING || racePhase == RacePhase.VERIFYING) {
          setRacePhase(RacePhase.IDLE, "new-ride-interrupt");
        }
        clearFailurePathOverlay("new-ride-interrupt");
        clearUiStrikeEvidence();
      }
    }

    if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) return;
    acceptSuccessLatch = false;
    lastMissedScanHit = false;
    lastMissedScanAtMs = 0;
    lastBubbleWindowValid = false;
    lastBubbleSeenAtMs = 0;

    if (rapidoBurstLock
        && (racePhase == RacePhase.IDLE
            || racePhase == RacePhase.ARMED
            || racePhase == RacePhase.COOLDOWN)) {
      finishRapidoMicroBurst("new-ride-lock");
    }

    if (racePhase == RacePhase.VERIFYING && !verifyingAccept) {
      setRacePhase(RacePhase.IDLE, "new-ride-orphan-verify");
      clearFailurePathOverlay("new-ride-orphan-verify");
    }
    if (racePhase == RacePhase.STRIKING && !rapidoBurstLock) {
      setRacePhase(RacePhase.IDLE, "new-ride-orphan-strike");
      clearFailurePathOverlay("new-ride-orphan-strike");
    }
  }

  /**
   * True when every Rapido window on screen is float-icon sized (no ride card / sheet).
   * While Home/WhatsApp is FG and only the bubble is visible → do nothing.
   */
  private boolean onlyRapidoSurfaceIsBubble() {
    boolean sawRapido = false;
    boolean sawNonBubble = false;
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows == null) return false;
      for (AccessibilityWindowInfo w : windows) {
        if (w == null) continue;
        AccessibilityNodeInfo root = null;
        try {
          root = w.getRoot();
          if (root == null) continue;
          if (!allowAsRapidoWindow(packageOf(root), lastPkg)) continue;
          sawRapido = true;
          w.getBoundsInScreen(scratchRect);
          if (isFloatingBubbleBounds(scratchRect)) {
            noteBubbleWindow(scratchRect);
          } else {
            sawNonBubble = true;
            break;
          }
        } catch (Exception ignored) {
        } finally {
          if (root != null) {
            try { root.recycle(); } catch (Exception ignored) {}
          }
        }
      }
    } catch (Exception ignored) {
    }
    return sawRapido && !sawNonBubble;
  }

  /**
   * Active app is not Rapido and the only Rapido surface is the float icon →
   * refuse hunt / gesture / contentIntent (prefer miss over reopen).
   * While race-armed / verifying, never idle — overlay Accept often paints after NLS.
   */
  private boolean shouldIdleForBubbleOnly() {
    if (isRapidoInteractionBlocked()) return true;
    // Late ride card: NLS arms before overlay exists; keep hunting through race phases
    if (isRaceArmed() || verifyingAccept || hasLiveRapidoOverlayCard()
        || racePhase == RacePhase.ARMED
        || racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING) {
      return false;
    }
    String active = null;
    try {
      active = resolveActivePackage();
    } catch (Exception ignored) {
    }
    if (active != null && isRapidoPackageName(active)) return false;
    if (active != null && AutoClickerConfig.isOlaPackage(active)) return false;
    // Non-Rapido FG (or unknown): if only bubble → idle
    return onlyRapidoSurfaceIsBubble();
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    sInstance = this;
    AutoClickerConfig.init(this);
    AutoClickerConfig.forceSyncCriticalFromDisk();
    detectAndApplyLowEndProfile();
    hydrateCachedAcceptFromDisk();

    AccessibilityServiceInfo info = getServiceInfo();
    if (info != null) {
      // Narrow events — TYPES_ALL_MASK causes OEM "malfunctioning" kills
      info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
          | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
          | AccessibilityEvent.TYPE_WINDOWS_CHANGED
          | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
      info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
      info.notificationTimeout = (int) Math.max(40L, a11yNotificationTimeoutMs);
      info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
          | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
          | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
      info.packageNames = null;
      setServiceInfo(info);
    }
    syncEngineForeground();
    AccessibilityDeathAlert.onServiceConnected(this);
    // Full reset — never inherit burst/verify/overlay ghosts across a11y reconnect
    resetRaceEngine("connect");
    Log.i(TAG, "ENGINE_STARTED enabled=" + AutoClickerConfig.isEnabled()
        + " nuclear=" + AutoClickerConfig.isNuclearMode()
        + " lowEnd=" + lowEndDevice
        + " heavyOem=" + heavyOem
        + " stockOem=" + stockOem
        + " gestMs=" + rapidoGestureMs
        + " huntPoll=" + acceptHuntPollMs
        + " phase=IDLE");
  }

  @Override
  public void onDestroy() {
    // Accept was ON + a11y dying — alert even if JS was swipe-killed
    try {
      AccessibilityDeathAlert.onServiceDying(this, "destroy");
    } catch (Throwable t) {
      Log.e(TAG, "DEATH_ALERT_FAIL", t);
    }
    resetRaceEngine("destroy");
    stopEngineForeground();
    if (sInstance == this) sInstance = null;
    super.onDestroy();
  }

  @Override
  public void onInterrupt() {
    // Interrupt is not always full death — alert only if Accept ON and a11y already off
    try {
      AccessibilityDeathAlert.checkAcceptOnWithoutA11y(this, "interrupt");
    } catch (Throwable t) {
      Log.e(TAG, "DEATH_ALERT_FAIL", t);
    }
    try {
      resetRaceEngine("interrupt");
    } catch (Throwable t) {
      Log.e(TAG, "INTERRUPT_RESET_FAIL", t);
    }
  }

  /**
   * Hunt only while a ride alert has armed the race, or overlay Accept is live over another app.
   */
  private boolean shouldRunAcceptHuntPoll() {
    if (!AutoClickerConfig.isEnabled()) return false;
    if (isRapidoInteractionBlocked()) return false;
    if (racePhase == RacePhase.COOLDOWN) return false;
    if (shouldIdleForBubbleOnly()) return false;
    return isRaceActive() || overlayAcceptContext();
  }

  /** True while racing a ride alert / mid-strike / verify. */
  private boolean isRaceActive() {
    return isRaceArmed()
        || verifyingAccept
        || racePhase == RacePhase.ARMED
        || racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING;
  }

  /**
   * May start Accept find→click while racing, Captain FG, or Rapido overlay over another app.
   */
  private boolean mayHuntAccept() {
    return isRaceActive() || rapidoForeground || overlayAcceptContext();
  }

  /** Armed / live overlay Accept — never IDLE+rideOverlayActive alone (OEM hunt storms). */
  private boolean overlayAcceptContext() {
    if (!AutoClickerConfig.isEnabled()) return false;
    if (isRapidoInteractionBlocked()) return false;
    if (shouldIdleForBubbleOnly()) return false;
    if (isRaceArmed() || hasLiveRapidoOverlayCard()) return true;
    // Flag only counts while a race is actually active
    return rideOverlayActive && isRaceActive();
  }

  /** Accept node sits on a floating Rapido ride sheet (not Captain fullscreen). */
  private boolean isNodeOnRideOverlay(AccessibilityNodeInfo node) {
    if (node == null) return false;
    try {
      AccessibilityWindowInfo w = node.getWindow();
      return isLikelyOverlayWindow(w);
    } catch (Exception e) {
      return false;
    }
  }

  /** True during an armed/striking/verifying Accept race (from a ride alert). */
  private boolean isAcceptRaceHot() {
    return isRaceArmed()
        || verifyingAccept
        || racePhase == RacePhase.ARMED
        || racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING;
  }

  private void scheduleAcceptHuntPoll() {
    if (acceptHuntPollScheduled) return;
    if (!shouldRunAcceptHuntPoll()) return;
    acceptHuntPollScheduled = true;
    long delay = (rapidoForeground || isRaceActive())
        ? acceptHuntPollMs
        : armedBgHuntPollMs;
    // Race hot: front-of-queue so Accept→tap stays under 10ms of sighting
    if (isAcceptRaceHot() && delay <= 6) {
      handler.postAtFrontOfQueue(acceptHuntPollRunnable);
    } else {
      handler.postDelayed(acceptHuntPollRunnable, Math.max(2L, delay));
    }
  }

  /** Next hunt ASAP on the main queue (used right after NLS miss). */
  private void scheduleAcceptHuntPollImmediate() {
    if (!shouldRunAcceptHuntPoll()) return;
    handler.removeCallbacks(acceptHuntPollRunnable);
    acceptHuntPollScheduled = true;
    // Seamless: always front-of-queue so Accept strike starts immediately
    handler.postAtFrontOfQueue(acceptHuntPollRunnable);
  }

  private void stopAcceptHuntPoll(String reason) {
    handler.removeCallbacks(acceptHuntPollRunnable);
    acceptHuntPollScheduled = false;
  }

  /**
   * True when a new Rapido Accept micro-burst may start.
   * Blocked in COOLDOWN / STRIKING; VERIFYING (UI) owns restrikes.
   */
  private boolean canStartRapidoBurst() {
    recoverIfRaceStuck("can-start");
    if (isRapidoInteractionBlocked()) return false;
    if (racePhase == RacePhase.COOLDOWN) return false;
    if (rapidoBurstLock || racePhase == RacePhase.STRIKING) return false;
    // UI verify owns restrikes — don't start a parallel smartClick burst
    if (racePhase == RacePhase.VERIFYING && verifyingAccept && !verifyFromPendingIntent) {
      return false;
    }
    long now = SystemClock.uptimeMillis();
    if (now < rapidoCooldownUntilMs) return false;
    if (now < rapidoRetryUntilMs) {
      return now - lastRapidoAttemptMs >= RAPIDO_BURST_GAP_MS;
    }
    return true;
  }

  private boolean isRapidoPackageName(String pkg) {
    return AutoClickerConfig.isRapidoPackage(pkg);
  }

  /** Live overlay card bounds from a Rapido window — not stale armed/sticky alone. */
  private boolean hasOverlayCardBounds() {
    return overlayCardBoundsValid && !overlayCardBoundsRect.isEmpty();
  }

  private boolean hasLiveRapidoOverlayCard() {
    if (SystemClock.uptimeMillis() > overlayLiveUntilMs) return false;
    return hasOverlayCardBounds() || (overlayTapX > 0 && overlayTapY > 0);
  }

  /**
   * Rapido node check. Android 15 / HyperOS often leaves getPackageName() null —
   * allow when lastPkg / active window / live overlay establishes Rapido context.
   */
  private boolean nodeIsRapido(AccessibilityNodeInfo node) {
    if (node == null) return false;
    String p = packageOf(node);
    if (p != null) return isRapidoPackageName(p);
    // Null packageName is common on Android 15 / HyperOS Accept overlays
    if (isAcceptRaceHot()) return true;
    return allowAsRapidoWindow(null, lastPkg);
  }

  private boolean pointInsideOverlayCard(int x, int y) {
    return hasOverlayCardBounds() && overlayCardBoundsRect.contains(x, y);
  }

  /**
   * Absolute-coord tap gate: ONLY a freshly measured Accept LABEL center.
   * Never allow Captain-FG alone — that re-enabled mid-screen spam after Accept left.
   * Sticky overlay + exact overlay tap point required for every gesture.
   */
  private boolean canGestureAt(int x, int y) {
    if (x <= 0 || y <= 0) return false;
    if (isRapidoInteractionBlocked()) return false;
    if (shouldIdleForBubbleOnly()) return false;

    // Ola has its own burst path — do not apply Rapido Accept sticky gates
    if (olaBurstLock) return true;

    if (!isRaceActive() && !verifyingAccept) return false;

    // SUPER RIDEX FG: never gesture unless a live Rapido Accept overlay mark exists.
    // Do NOT allow bare race-armed / verifying with stale mid-screen coords on our UI.
    if (isSelfAppForeground()) {
      if (!hasLiveRapidoOverlayCard()) return false;
      if (overlayTapX > 0 && overlayTapY > 0) {
        if (Math.abs(x - overlayTapX) > 48 || Math.abs(y - overlayTapY) > 48) return false;
      } else {
        return false;
      }
    }

    // ONLY exact measured Accept centers — never random / mid-screen spray
    boolean exactAccept =
        (verifyCx > 0 && verifyCy > 0 && x == verifyCx && y == verifyCy)
        || (rapidoBurstX > 0 && rapidoBurstY > 0 && x == rapidoBurstX && y == rapidoBurstY)
        || (cachedAcceptValid && x == cachedAcceptX && y == cachedAcceptY);
    if (!exactAccept) return false;

    // Must match the last live Accept LABEL mark (tight)
    if (!hasLiveRapidoOverlayCard()) {
      // Strike-0 / VERIFY only — never the whole armed TTL (that caused mid-screen spam)
      if (racePhase != RacePhase.STRIKING
          && racePhase != RacePhase.VERIFYING
          && !verifyingAccept) {
        return false;
      }
      return true;
    }
    if (overlayTapX > 0 && overlayTapY > 0) {
      if (Math.abs(x - overlayTapX) > 48 || Math.abs(y - overlayTapY) > 48) return false;
    }
    if (hasOverlayCardBounds() && !overlayCardBoundsRect.contains(x, y)) return false;
    return true;
  }

  /** Drop stale gesture coords so mid-screen taps cannot continue after Accept leaves. */
  private void clearStaleAcceptTapPoints(String reason) {
    verifyCx = 0;
    verifyCy = 0;
    rapidoBurstX = 0;
    rapidoBurstY = 0;
    clearBlindSprayTargets(reason);
    overlayCardBoundsValid = false;
    overlayCardBoundsRect.setEmpty();
    overlayLiveUntilMs = 0;
  }

  private void cacheAcceptPoint(String pkg, int x, int y) {
    if (x <= 0 || y <= 0) return;
    String usePkg = pkg;
    if (usePkg == null || !isRapidoPackageName(usePkg)) {
      usePkg = lastPkg != null && isRapidoPackageName(lastPkg) ? lastPkg : "com.rapido.rider";
    }
    // Never cache bubble / float-icon centers — reopen loop on some OEMs
    if (pointInsideKnownBubble(x, y) || pointInsideRapidoBubbleWindow(x, y)) return;
    cachedAcceptX = x;
    cachedAcceptY = y;
    cachedAcceptPkg = usePkg;
    cachedAcceptValid = true;
    cachedAcceptAtMs = SystemClock.uptimeMillis();
    AutoClickerConfig.cacheTapPoint(usePkg, x, y);
  }

  /**
   * Drop in-memory Accept cache. CACHE_STRIKE also requires a fresh live Accept.
   */
  private void clearCachedAcceptPoint(String reason) {
    cachedAcceptValid = false;
    cachedAcceptX = 0;
    cachedAcceptY = 0;
    cachedAcceptPkg = null;
    cachedAcceptAtMs = 0;
    boolean poison = reason != null && (reason.contains("bubble") || "destroy".equals(reason));
    if ("destroy".equals(reason)) return;
    if (poison && lastPkg != null) {
      AutoClickerConfig.clearCachedTapPoints(lastPkg);
    }
  }

  private void markOverlayLive(Rect bounds, int tapX, int tapY) {
    long until = SystemClock.uptimeMillis() + OVERLAY_STICKY_MS;
    if (tapX > 0 && tapY > 0) {
      overlayTapX = tapX;
      overlayTapY = tapY;
      overlayLiveUntilMs = until;
      overlayStickyUntilMs = until;
    }
    if (bounds == null || bounds.isEmpty()) return;
    // Wide/flat Accept CTAs must mark sticky — bubble geometry was blocking gestures
    if (isFloatingBubbleBounds(bounds) && !isRideCardSizedBounds(bounds)) {
      // Tiny square chat-head only — still keep tap point from above
      if (isBubbleLikeClickTarget(bounds)) return;
    }
    overlayCardBoundsRect.set(bounds);
    overlayCardBoundsValid = true;
    if (tapX > 0 && tapY > 0) {
      overlayTapX = tapX;
      overlayTapY = tapY;
    }
    overlayLiveUntilMs = until;
    overlayStickyUntilMs = until;
  }

  private void clearBlindSprayTargets(String reason) {
    overlayTapX = 0;
    overlayTapY = 0;
    overlayLiveUntilMs = 0;
  }

  /**
   * After VERIFY confirms Accept gone: enter short COOLDOWN, then IDLE.
   * Fresh NLS after COOLDOWN can arm again even while still on-trip.
   */
  private void disarmAfterAcceptSuccess(String reason) {
    long now = SystemClock.uptimeMillis();
    cancelVerify("disarm/" + reason);
    acceptSuccessLatch = true;
    ignoreRapidoUntilMs = now + RACE_COOLDOWN_MS;
    raceArmedUntilMs = 0;
    raceArmedFromMs = 0;
    handler.removeCallbacks(raceArmExpireRunnable);
    handler.removeCallbacks(nlsFollowRunnable);
    nlsFollowActive = false;
    stopAcceptHuntPoll("accept-ok/" + reason);
    finishRapidoMicroBurst("accept-ok");
    clearCachedAcceptPoint("accept-ok/" + reason);
    clearBlindSprayTargets("accept-ok/" + reason);
    overlayCardBoundsValid = false;
    overlayCardBoundsRect.setEmpty();
    overlayLiveUntilMs = 0;
    setRideOverlayActive(false, "accept-ok/" + reason);
    rapidoRetryUntilMs = 0;
    rapidoCooldownUntilMs = now + RACE_COOLDOWN_MS;
    setRacePhase(RacePhase.COOLDOWN, reason);
    scheduleCooldownExpire();
    handler.removeCallbacks(softConfirmRunnable);
    clearUiStrikeEvidence();
  }

  /** Clear VERIFYING without latching success (miss / abort). Return to ARMED if still armed. */
  private void cancelVerify(String reason) {
    verifyingAccept = false;
    verifyFromPendingIntent = false;
    verifyUntilMs = 0;
    verifyRestrikeCount = 0;
    handler.removeCallbacks(verifyAcceptRunnable);
    // disarmAfterAcceptSuccess clears verify then sets COOLDOWN — don't bounce to ARMED
    if (reason != null && reason.startsWith("disarm/")) return;
    if (racePhase == RacePhase.VERIFYING || racePhase == RacePhase.STRIKING) {
      if (isRaceArmed() || rapidoForeground) {
        setRacePhase(RacePhase.ARMED, "cancel-verify/" + reason);
      } else {
        setRacePhase(RacePhase.IDLE, "cancel-verify/" + reason);
      }
    }
    // Miss / abort paths must drop overlay flag (success path uses disarm)
    if (reason == null || !reason.startsWith("disarm/")) {
      if (!isRaceArmed() && racePhase != RacePhase.ARMED) {
        clearFailurePathOverlay("cancel-verify/" + reason);
      }
    }
  }

  /**
   * Enter VERIFYING after a strike or PendingIntent — do not COOLDOWN yet.
   * Success requires Accept gone. PendingIntent never latches alone without window expiry.
   */
  private void beginVerify(
      String pkg, String tag, long findAt, int cx, int cy, boolean fromPendingIntent) {
    verifyingAccept = true;
    verifyFromPendingIntent = fromPendingIntent;
    verifyPkg = pkg != null ? pkg : lastPkg;
    verifyTag = tag;
    verifyFindAt = findAt > 0 ? findAt : SystemClock.uptimeMillis();
    verifyCx = cx;
    verifyCy = cy;
    verifyRestrikeCount = 0;
    // UI strike already measured Accept under the finger
    acceptSeenDuringVerify = !fromPendingIntent && (uiStrikeValid || (cx > 0 && cy > 0));
    verifyUntilMs = SystemClock.uptimeMillis()
        + (heavyOem ? Math.max(VERIFY_WINDOW_MS, 450L) : VERIFY_WINDOW_MS);
    setRacePhase(RacePhase.VERIFYING, fromPendingIntent ? "verify-action" : "verify-ui");
    refreshRaceArm("begin-verify");
    handler.removeCallbacks(verifyAcceptRunnable);
    handler.postDelayed(verifyAcceptRunnable, VERIFY_POLL_MS);
  }

  /** Clear the post-Accept latch when a fresh ride signal arrives — never during hard ignore. */
  private void clearAcceptSuccessLatch(String reason) {
    if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) return;
    acceptSuccessLatch = false;
  }

  /**
   * True if any Rapido window shows on-trip / nav chrome (End Ride, Navigate, etc.).
   * Context only — does NOT mean "stop hunting Accept".
   */
  private boolean rapidoLooksOnTripAnywhere() {
    try {
      AccessibilityNodeInfo active = getRootInActiveWindow();
      if (active != null) {
        try {
          if (allowAsRapidoWindow(packageOf(active), lastPkg) && rootHasOnTripChrome(active)) {
            return true;
          }
        } finally {
          active.recycle();
        }
      }
    } catch (Exception ignored) {
    }
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows == null) return false;
      for (AccessibilityWindowInfo w : windows) {
        if (w == null) continue;
        AccessibilityNodeInfo root = null;
        try {
          root = w.getRoot();
          if (root == null) continue;
          if (!allowAsRapidoWindow(packageOf(root), lastPkg)) continue;
          if (rootHasOnTripChrome(root)) return true;
        } finally {
          if (root != null) {
            try { root.recycle(); } catch (Exception ignored) {}
          }
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  /** Find Accept on active root or any Rapido window. Caller owns the node. */
  private AccessibilityNodeInfo findAcceptNodeAnywhere() {
    AccessibilityNodeInfo hit = null;
    try {
      AccessibilityNodeInfo active = getRootInActiveWindow();
      if (active != null) {
        try {
          if (allowAsRapidoWindow(packageOf(active), lastPkg)
              && !isFloatingBubbleNode(active)) {
            hit = findAcceptLabelInRoot(active);
          }
        } finally {
          active.recycle();
        }
      }
    } catch (Exception ignored) {
    }
    if (hit != null) return hit;
    boolean bgOverlay = !rapidoForeground && isAcceptRaceHot();
    HuntHit multi = findAcceptAcrossRapidoWindows(lastPkg, rapidoForeground, bgOverlay);
    return multi != null ? multi.node : null;
  }

  /**
   * Note on-trip/nav chrome on this tree. Never a reason to skip Accept hunt.
   * @return true if chrome labels present
   */
  private boolean noteOnTripChrome(AccessibilityNodeInfo root) {
    if (rootHasOnTripChrome(root)) {
      if (!driverOnTripChrome) {
        Log.i(TAG, "ON_TRIP_CHROME detected — still hunting Accept");
      }
      driverOnTripChrome = true;
      return true;
    }
    return false;
  }

  /** True if this tree shows nav / End Ride / On Trip chrome. */
  private boolean rootHasOnTripChrome(AccessibilityNodeInfo root) {
    if (root == null) return false;
    for (String label : ON_TRIP_CHROME_LABELS) {
      if (treeHasLabel(root, label, true)) return true;
    }
    return false;
  }

  /** @deprecated Use {@link #rootHasOnTripChrome} — name kept for call-site clarity during migrate. */
  @Deprecated
  private boolean rootLooksOnTrip(AccessibilityNodeInfo root) {
    return rootHasOnTripChrome(root);
  }

  private boolean treeHasLabel(AccessibilityNodeInfo root, String label, boolean onTripSemantics) {
    List<AccessibilityNodeInfo> nodes;
    try {
      nodes = root.findAccessibilityNodeInfosByText(label);
    } catch (Exception e) {
      return false;
    }
    if (nodes == null || nodes.isEmpty()) return false;
    boolean hit = false;
    for (AccessibilityNodeInfo n : nodes) {
      if (n == null) continue;
      CharSequence t = nodeTextCs(n);
      if (t != null) {
        if (onTripSemantics) {
          if (containsIgnoreCase(t, "trip started")
              || containsIgnoreCase(t, "end ride")
              || containsIgnoreCase(t, "complete ride")
              || containsIgnoreCase(t, "go to pickup")
              || containsIgnoreCase(t, "navigate to pickup")
              || containsIgnoreCase(t, "on trip")
              || equalsIgnoreCaseTrim(t, "navigate")) {
            hit = true;
          }
        } else if (containsIgnoreCase(t, "accepted")) {
          hit = true;
        }
      }
      try { n.recycle(); } catch (Exception ignored) {}
    }
    return hit;
  }

  private boolean dumpLooksOnTrip(String dump) {
    if (dump == null || dump.isEmpty()) return false;
    String lower = dump.toLowerCase(Locale.US);
    return lower.contains("trip started")
        || lower.contains("on trip")
        || lower.contains("end ride")
        || lower.contains("complete ride")
        || lower.contains("go to pickup")
        || lower.contains("navigate to");
  }

  /** True if a real Accept label is still on a non-bubble Rapido surface. */
  private boolean acceptStillVisibleAnywhere() {
    AccessibilityNodeInfo hit = findAcceptNodeAnywhere();
    if (hit == null) return false;
    try { hit.recycle(); } catch (Exception ignored) {}
    return true;
  }

  /** ACTION_CLICK on Rapido nodes (null packageName OK when Rapido context is set). */
  private boolean performRapidoClick(AccessibilityNodeInfo node) {
    if (node == null || !nodeIsRapido(node)) return false;
    if (isRapidoInteractionBlocked()) return false;
    // Before every ACTION_CLICK: node tap-target OR containing window = float icon → refuse
    if (isFloatingBubbleNode(node) && !isAcceptRaceHot() && !rapidoForeground) return false;
    try {
      node.getBoundsInScreen(scratchRect);
      if (isBubbleLikeClickTarget(scratchRect) && !isAcceptRaceHot() && !rapidoForeground) return false;
    } catch (Exception ignored) {
    }
    // While user is on WhatsApp/etc, only click real Accept (not float icon).
    // When armed/verifying, skip window-type gates — HyperOS sheets are often APPLICATION windows.
    if (!rapidoForeground) {
      boolean allowArmed = isAcceptRaceHot()
          || hasLiveRapidoOverlayCard()
          || (rideOverlayActive && isRaceActive());
      if (allowArmed) {
        try {
          return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception e) {
          return false;
        }
      }
      String active = null;
      try {
        active = resolveActivePackage();
      } catch (Exception ignored) {
      }
      if (active != null && !isRapidoPackageName(active)
          && !AutoClickerConfig.isOlaPackage(active)) {
        if (shouldIdleForBubbleOnly()) return false;
        try {
          AccessibilityWindowInfo w = node.getWindow();
          if (w != null) {
            w.getBoundsInScreen(scratchRect2);
            if (isFloatingBubbleBounds(scratchRect2)) return false;
            if (!isLikelyOverlayWindow(w)) return false;
          } else {
            return false;
          }
        } catch (Exception e) {
          return false;
        }
      }
    }
    try {
      return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isSelfAppForeground() {
    long now = SystemClock.uptimeMillis();
    if (now - selfFgCachedAtMs < SELF_FG_CACHE_MS) {
      return selfFgCached;
    }
    boolean fg = false;
    try {
      String p = resolveActivePackage();
      fg = p != null && p.equals(getPackageName());
    } catch (Exception ignored) {
    }
    selfFgCached = fg;
    selfFgCachedAtMs = now;
    return fg;
  }

  /** Cheap cache update from event package — avoids root fetch on every event. */
  private void noteEventPackage(String pkg) {
    if (pkg == null || pkg.isEmpty()) return;
    selfFgCached = pkg.equals(getPackageName());
    selfFgCachedAtMs = SystemClock.uptimeMillis();
  }

  private boolean isRaceArmed() {
    return SystemClock.uptimeMillis() <= raceArmedUntilMs;
  }

  /**
   * System / cover UIs that sit over Captain but are NOT a real app switch.
   * Call screen, lock/keyguard, shade, dialer — keep hunting when race-armed.
   */
  private static boolean isTransientChromePackage(String pkg) {
    if (pkg == null || pkg.isEmpty()) return true;
    String p = pkg.toLowerCase(Locale.US);
    if (p.contains("systemui")) return true;
    if (p.contains("keyguard") || p.contains("lockscreen") || p.contains("lock.screen")) {
      return true;
    }
    // In-call / dialer (AOSP, Google, Samsung, Oppo/Realme, Xiaomi, Vivo, …)
    if (p.contains("incallui") || p.contains("incall") || p.contains("in_call")) return true;
    if (p.contains("dialer") || p.contains("telecom")) return true;
    if (p.equals("com.android.phone") || p.startsWith("com.android.phone")) return true;
    if (p.contains("com.samsung.android.incallui")) return true;
    if (p.contains("com.google.android.dialer")) return true;
    if (p.contains("com.android.server.telecom")) return true;
    if ((p.contains("oplus") || p.contains("coloros") || p.contains("heytap")
        || p.contains("realme") || p.contains("oneplus"))
        && (p.contains("call") || p.contains("phone") || p.contains("dial"))) {
      return true;
    }
    if ((p.contains("miui") || p.contains("xiaomi") || p.contains("com.android.contacts"))
        && (p.contains("call") || p.contains("incall"))) {
      return true;
    }
    if (p.contains("permissioncontroller") || p.contains("packageinstaller")) return true;
    if (p.contains("screenshot") || p.contains("globalactions")) return true;
    if (p.contains("com.oplus.stdsp")) return true;
    return false;
  }

  /** Wake display briefly so lock/doze devices can show/tap Accept overlays. */
  private void wakeScreenForRace() {
    try {
      PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
      if (pm == null) return;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH && pm.isInteractive()) {
        return;
      }
      // Only when screen is actually off — waking on every ride feels like "app reopen"
      @SuppressWarnings("deprecation")
      PowerManager.WakeLock wl = pm.newWakeLock(
          PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
          "superridex:race");
      wl.acquire(1500L);
      handler.postDelayed(() -> {
        try {
          if (wl.isHeld()) wl.release();
        } catch (Exception ignored) {
        }
      }, 1600L);
    } catch (Exception ignored) {
    }
  }

  /**
   * Update Rapido-FG flag from the real active window — never claim FG just because
   * a Rapido overlay/bubble fired an a11y event while the user is on WhatsApp.
   */
  private void syncRapidoForegroundFromActive(String reason) {
    String active = null;
    try {
      active = resolveActivePackage();
    } catch (Exception ignored) {
    }
    if (active != null && isRapidoPackageName(active)) {
      setRapidoForeground(true, reason);
      return;
    }
    // Heads-up / notification shade: keep sticky FG + keep hunting
    if (active != null && isTransientChromePackage(active)) {
      if (rapidoForeground || isRaceArmed()
          || racePhase == RacePhase.ARMED
          || racePhase == RacePhase.VERIFYING
          || racePhase == RacePhase.STRIKING) {
        scheduleAcceptHuntPoll();
        Log.i(TAG, "FG_RAPIDO sticky (ignore-" + active + ") armed=" + isRaceArmed());
      }
      return;
    }
    if (active != null) {
      setRapidoForeground(false, reason + "/" + active);
    }
  }

  private void setRapidoForeground(boolean fg, String reason) {
    if (rapidoForeground == fg) {
      if (isRaceActive()) scheduleAcceptHuntPoll();
      else {
        stopAcceptHuntPoll(reason + "/already-bg");
        clearStaleAcceptTapPoints(reason + "/already-bg");
        clearCachedAcceptPoint(reason + "/already-bg");
      }
      return;
    }
    rapidoForeground = fg;
    if (fg) {
      Log.i(TAG, "FG_RAPIDO true (" + reason + ") onTripChrome=" + driverOnTripChrome);
      // Captain FG alone never starts hunt — only a ride-alert race does
      if (isRaceActive()) scheduleAcceptHuntPoll();
      else stopAcceptHuntPoll("fg-idle");
    } else {
      Log.i(TAG, "FG_RAPIDO false (" + reason + ") armed=" + isRaceArmed());
      if (isRaceActive()) {
        scheduleAcceptHuntPoll();
      } else {
        stopAcceptHuntPoll(reason);
        clearStaleAcceptTapPoints(reason);
        clearCachedAcceptPoint(reason);
      }
    }
  }

  private void setRideOverlayActive(boolean active, String reason) {
    if (rideOverlayActive == active) return;
    rideOverlayActive = active;
    if (!active && !rapidoForeground) {
      overlayLiveUntilMs = 0;
      overlayCardBoundsValid = false;
      overlayCardBoundsRect.setEmpty();
    }
  }

  private String resolveActivePackage() {
    try {
      AccessibilityNodeInfo root = getRootInActiveWindow();
      if (root != null) {
        try {
          CharSequence p = root.getPackageName();
          return p != null ? p.toString() : null;
        } finally {
          root.recycle();
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  /** Floating ride card / system overlay — not fullscreen Captain and not the bubble icon. */
  private boolean isLikelyOverlayWindow(AccessibilityWindowInfo w) {
    if (w == null) return false;
    int type = w.getType();
    if (type == AccessibilityWindowInfo.TYPE_SYSTEM) {
      // Still reject icon-sized system windows
      try {
        w.getBoundsInScreen(scratchRect);
        if (isFloatingBubbleBounds(scratchRect)) {
          noteBubbleWindow(scratchRect);
          return false;
        }
      } catch (Exception ignored) {
      }
      return true;
    }
    try {
      w.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty()) return false;
      if (isFloatingBubbleBounds(scratchRect)) {
        noteBubbleWindow(scratchRect);
        return false;
      }
      ensureScreenMetrics();
      if (screenArea <= 0) return false;
      float area = (float) scratchRect.width() * (float) scratchRect.height();
      if (area <= 0) return false;
      boolean notFullscreen = area < screenArea * 0.88f
          && scratchRect.height() < screenH * 0.94f;
      return notFullscreen;
    } catch (Exception e) {
      return false;
    }
  }

  private void arm(String reason) {
    // Do not re-arm during COOLDOWN
    if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) return;
    recoverIfRaceStuck("arm/" + reason);

    // NLS / new-ride must NEVER soft-keep VERIFY/STRIKING — that missed the next offer for 2–3.5s
    boolean fromNewOffer = reason != null && (reason.contains("NLS")
        || reason.contains("A11y")
        || reason.contains("action")
        || reason.contains("new-ride")
        || reason.startsWith("Nls"));
    if (!fromNewOffer) {
      boolean liveStrike = racePhase == RacePhase.STRIKING && rapidoBurstLock
          && (SystemClock.uptimeMillis() - racePhaseEnteredAtMs) < 200;
      boolean liveVerify = verifyingAccept && racePhase == RacePhase.VERIFYING
          && (SystemClock.uptimeMillis() - racePhaseEnteredAtMs) < 200;
      if (liveStrike || liveVerify) {
        refreshRaceArm("arm-keep-strike/" + reason);
        scheduleAcceptHuntPoll();
        return;
      }
    }
    // Clear STRIKING/VERIFYING so this offer gets a real hunt→strike
    if (racePhase == RacePhase.STRIKING || racePhase == RacePhase.VERIFYING || verifyingAccept) {
      cancelVerify("arm-yield/" + reason);
      finishRapidoMicroBurst("arm-yield");
    }
    if (rapidoBurstLock) {
      finishRapidoMicroBurst("re-arm");
    }
    acceptSuccessLatch = false;
    clearUiStrikeEvidence();
    long now = SystemClock.uptimeMillis();
    if (raceArmedFromMs == 0 || now > raceArmedUntilMs) {
      raceArmedFromMs = now;
    }
    raceArmedUntilMs = now + raceArmMs;
    setRacePhase(RacePhase.ARMED, reason);
    handler.removeCallbacks(raceArmExpireRunnable);
    handler.postDelayed(raceArmExpireRunnable, raceArmMs + 30);
    scheduleAcceptHuntPoll();
  }

  /**
   * Extend arm when Accept is sighted — late overlays stay hunt-able without
   * unbounded WhatsApp polling (capped from first signal).
   */
  private void refreshRaceArm(String reason) {
    if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) return;
    long now = SystemClock.uptimeMillis();
    if (raceArmedFromMs == 0) {
      raceArmedFromMs = now;
    }
    long maxUntil = raceArmedFromMs + RACE_ARM_MAX_FROM_SIGNAL_MS;
    long extended = Math.min(now + RACE_ARM_EXTEND_MS, maxUntil);
    if (extended <= raceArmedUntilMs) {
      // Still schedule poll if armed
      if (isRaceArmed()) scheduleAcceptHuntPoll();
      return;
    }
    raceArmedUntilMs = extended;
    handler.removeCallbacks(raceArmExpireRunnable);
    handler.postDelayed(raceArmExpireRunnable, Math.max(30, raceArmedUntilMs - now + 30));
    scheduleAcceptHuntPoll();
  }

  /**
   * NLS / a11y notification: Accept PendingIntent first (sync).
   * Never contentIntent-spam Captain open (bubble / Home reopen loop).
   * Then adaptive arm + hunts; PendingIntent enters VERIFY (no immediate disarm).
   */
  public static void onRideSignal(
      String packageName,
      Notification notification,
      String notifText,
      long tReceive,
      String source
  ) {
    final AutoClickerService svc = sInstance;
    if (!AutoClickerConfig.isEnabled()) return;
    if (packageName == null || !AutoClickerConfig.isPackageMonitored(packageName)) return;
    if (isSpamText(notifText)) return;
    // Hard gate: only real ride alerts arm the race (no status / earnings taps)
    if (!isRideAlert(notifText, notification)) return;

    // Hard latch during short COOLDOWN — still fire Accept action; queue hunt for after CD
    if (svc != null && svc.isRapidoInteractionBlocked()) {
      tryFireAcceptPendingIntent(notification, notifText, tReceive, source);
      svc.queueRideSignal(packageName, notification, notifText, tReceive, source);
      return;
    }

    // 1) Accept PendingIntent FIRST — sync, before any hunt / arm work
    boolean actionOk = tryFireAcceptPendingIntent(notification, notifText, tReceive, source);

    // 2) NEVER contentIntent after Accept action fired (yanks Captain open).
    //    Also never when only the float icon is visible / no ride card to show —
    //    contentIntent reopen loop is worse than missing a ride.
    if (!actionOk
        && notification != null
        && notification.contentIntent != null
        && svc != null
        && svc.shouldSendContentIntent()) {
      try {
        notification.contentIntent.send();
      } catch (Exception ignored) {
      }
    }

    if (svc == null) return;
    svc.lastPkg = packageName;

    // Always arm + hunt after NLS — even if Accept PendingIntent fired.
    // Soft-assuming action success was latching the engine and skipping overlay taps.
    Runnable race = () -> {
      if (SystemClock.uptimeMillis() < svc.ignoreRapidoUntilMs) return;
      // Critical: clear stuck STRIKING/VERIFY/burst from prior rides (Redmi/Vivo/Oppo)
      svc.prepareForNewRideSignal(source);
      svc.acceptSuccessLatch = false;
      // Arm → hunt → tap FIRST. Wake/log after — never block strike-0.
      svc.arm(source + (actionOk ? "/action" : ""));
      boolean hunted = false;
      if (AutoClickerConfig.isRapidoPackage(packageName)
          || (packageName != null && packageName.contains("rideandhra"))) {
        hunted = svc.huntAccept(packageName, tReceive, "NlsHunt+0", true);
        if (!hunted && !svc.verifyingAccept) {
          svc.fireCachedAcceptStrike(packageName, tReceive, source);
        }
      } else if (AutoClickerConfig.isOlaPackage(packageName)
          || AutoClickerConfig.isUberPackage(packageName)) {
        hunted = svc.huntSharedRideApp(packageName, tReceive, "NlsHunt+0");
      }
      if (actionOk && !svc.verifyingAccept && !svc.rapidoBurstLock
          && AutoClickerConfig.isRapidoPackage(packageName)) {
        svc.beginVerify(packageName, "AcceptAction/" + source, tReceive, 0, 0, true);
      }
      svc.scheduleNlsFollowups(packageName, tReceive);
      // If Accept not painted yet, re-hunt at front of queue (no 4–6ms wait)
      if (!hunted && !svc.verifyingAccept && !svc.rapidoBurstLock) {
        svc.scheduleAcceptHuntPollImmediate();
      } else {
        svc.scheduleAcceptHuntPoll();
      }
      // Doze/lock: wake AFTER first hunt so Accept isn't delayed on interactive phones
      svc.wakeScreenForRace();
      Log.i(TAG, "RIDE_SIGNAL " + source + " pkg=" + packageName
          + " action=" + actionOk + " hunted=" + hunted);
    };
    if (Looper.myLooper() == Looper.getMainLooper()) {
      race.run();
    } else {
      svc.handler.postAtFrontOfQueue(race);
    }
  }

  /**
   * contentIntent opens full Captain — never fire it.
   * Accept PendingIntent + overlay hunt cover real rides; contentIntent was the
   * Home→Captain reopen loop when only the float icon (or a status notif) was present.
   */
  private boolean shouldSendContentIntent() {
    return false;
  }

  public static void raceFromNotificationListener(String packageName, String notifText) {
    onRideSignal(packageName, null, notifText, SystemClock.uptimeMillis(), "NLS");
  }

  public static boolean tryFireAcceptPendingIntent(
      Notification n,
      String notifText,
      long tReceive,
      String source
  ) {
    if (n == null || !AutoClickerConfig.isEnabled()) return false;
    Notification.Action action = findAcceptAction(n);
    if (action == null || action.actionIntent == null) return false;
    try {
      action.actionIntent.send();
      // Emit deferred to VERIFY — performAction/send success is not proof the ride was taken
      return true;
    } catch (Exception e) {
      Log.e(TAG, "ACCEPT_ACTION_FAIL", e);
      return false;
    }
  }

  private static Notification.Action findAcceptAction(Notification n) {
    if (n.actions != null) {
      for (Notification.Action a : n.actions) {
        if (a != null && isAcceptActionTitle(a.title)) return a;
      }
    }
    try {
      List<Notification.Action> wear = new Notification.WearableExtender(n).getActions();
      if (wear != null) {
        for (Notification.Action a : wear) {
          if (a != null && isAcceptActionTitle(a.title)) return a;
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  static boolean isAcceptActionTitle(CharSequence title) {
    if (title == null) return false;
    int len = title.length();
    if (len == 0 || len > 32) return false;
    if (containsIgnoreCase(title, "accepted") || containsIgnoreCase(title, "reject")
        || containsIgnoreCase(title, "decline") || containsIgnoreCase(title, "cancel")
        || containsIgnoreCase(title, "dismiss")) {
      return false;
    }
    if (indexOfSeq(title, "स्वीकार") >= 0) return true;
    if (equalsIgnoreCaseTrim(title, "accept")) return true;
    if (startsWithIgnoreCase(title, "accept ")) return true;
    return containsIgnoreCase(title, "accept ride")
        || containsIgnoreCase(title, "accept order")
        || containsIgnoreCase(title, "accept booking")
        || containsIgnoreCase(title, "accept trip")
        || containsIgnoreCase(title, "take ride");
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    try {
      handleAccessibilityEvent(event);
    } catch (Throwable t) {
      // Critical: uncaught exceptions make Android disable the service
      // ("This accessibility service is malfunctioning") on MIUI/ColorOS/FunTouch/One UI.
      Log.e(TAG, "A11Y_EVENT_SAFE", t);
    }
  }

  private void handleAccessibilityEvent(AccessibilityEvent event) {
    final long t0 = SystemClock.uptimeMillis();
    if (event == null) return;

    if (!AutoClickerConfig.isEnabled()) {
      stopAcceptHuntPoll("master-off");
      return;
    }

    CharSequence pkgCsEarly = event.getPackageName();
    String pkgEarly = pkgCsEarly != null ? pkgCsEarly.toString() : "";
    noteEventPackage(pkgEarly);

    // SUPER RIDEX UI events: never thrash hunt/poll (feels like app "reopening" when swiped).
    // Overlay Accept lives in Rapido/Ola/Uber windows — only light-schedule if already racing.
    if (pkgEarly.equals(getPackageName())) {
      if (isRaceActive() && AutoClickerConfig.isEnabled() && !isRapidoInteractionBlocked()) {
        // Debounced — do not postAtFrontOfQueue on every self window event
        scheduleAcceptHuntPoll();
      } else {
        stopAcceptHuntPoll("self-event");
      }
      return;
    }

    CharSequence pkgCs = event.getPackageName();
    String pkg = pkgCs != null ? pkgCs.toString() : "";
    int type = event.getEventType();

    if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
      if (!AutoClickerConfig.isPackageMonitored(pkg)) return;
      String text = notifText(event);
      if (isSpamText(text)) return;
      Parcelable data = event.getParcelableData();
      Notification n = data instanceof Notification ? (Notification) data : null;
      // Same gate as NLS — ignore status / earnings / non-ride Captain pings
      if (!isRideAlert(text, n)) return;
      lastPkg = pkg;
      onRideSignal(pkg, n, text, t0, "A11yNotif");
      return;
    }

    if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
      return;
    }

    // Coalesce content-changed storms — protects OEM a11y watchdogs while racing
    if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
      long since = t0 - lastContentEventAtMs;
      if (since >= 0 && since < contentEventCoalesceMs) {
        if (isRaceActive()) scheduleAcceptHuntPoll();
        return;
      }
      lastContentEventAtMs = t0;
    }

    boolean isRapido = isRapidoPackageName(pkg);
    // Null/empty packageName: allow when Rapido context already set (Android 15)
    boolean rapidoContext = isRapido
        || ((pkg == null || pkg.isEmpty()) && allowAsRapidoWindow(null, lastPkg));
    boolean isOla = AutoClickerConfig.isOlaPackage(pkg);
    boolean isUber = AutoClickerConfig.isUberPackage(pkg);
    boolean windowsChanged = type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;

    // Accept race on Captain FG (home/map) OR while NLS-armed
    if ((isRapido || rapidoContext)
        && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
      if (isRapido) lastPkg = pkg;
      syncRapidoForegroundFromActive("hot-path");
      if (shouldIdleForBubbleOnly()) {
        if (isRaceActive()) scheduleAcceptHuntPoll();
        return;
      }
      boolean overlayRapidoEvent = isRapido && !rapidoForeground;
      if ((!mayHuntAccept() || !canStartRapidoBurst()) && !overlayRapidoEvent) {
        if (isRaceActive()) scheduleAcceptHuntPoll();
        // Fall through for FG bookkeeping / Ola
      } else if (!mayHuntAccept() && overlayRapidoEvent && canStartRapidoBurst()) {
        if (huntAccept(lastPkg, t0, "OverlayRapidoEvt", true)) {
          scheduleAcceptHuntPollImmediate();
        } else if (isRaceActive()) {
          scheduleAcceptHuntPoll();
        }
      } else {
      AccessibilityNodeInfo source = null;
      try {
        source = event.getSource();
        AccessibilityNodeInfo root = source != null ? source : safeRapidoOrOlaRoot(pkg);
        if (root != null) {
          boolean ownRoot = source == null;
          try {
            // Skip bubble-sized event sources before any findByText on this root
            boolean bubbleRoot = isFloatingBubbleNode(root);
            if (!bubbleRoot) {
              try {
                AccessibilityWindowInfo rw = root.getWindow();
                if (rw != null) {
                  rw.getBoundsInScreen(scratchRect2);
                  if (isFloatingBubbleBounds(scratchRect2)) {
                    noteBubbleWindow(scratchRect2);
                    bubbleRoot = true;
                  }
                }
              } catch (Exception ignored) {
              }
            } else {
              try {
                root.getBoundsInScreen(scratchRect2);
                noteBubbleWindow(scratchRect2);
              } catch (Exception ignored) {
              }
            }
            // Compact OEM Accept sheets are often mis-tagged as bubble windows —
            // still search when Captain FG, race is armed, or Rapido overlay over another app.
            if (!bubbleRoot || isAcceptRaceHot() || rapidoForeground || overlayRapidoEvent) {
              noteOnTripChrome(root);
              String rootPkg = resolveRapidoPkg(root, pkg);
              if (allowAsRapidoWindow(packageOf(root), pkg) || isAcceptRaceHot()) {
                AccessibilityNodeInfo accept = findAcceptFast(root);
                if (accept == null) {
                  accept = findAcceptLabelInRoot(root);
                }
                if (accept != null) {
                  logFoundAccept(accept, "HotPath", rootPkg);
                  smartClickAccept(accept, rootPkg, "Rapido-HotPath", t0);
                  if (isRaceActive()) scheduleAcceptHuntPoll();
                  return;
                }
              }
            }
          } finally {
            if (ownRoot) root.recycle();
          }
        }
      } finally {
        if (source != null) source.recycle();
      }
      // Captain FG (home OR nav/map): Accept often on sibling sheet — multi-window
      if (rapidoForeground || isRaceActive() || hasLiveRapidoOverlayCard()
          || overlayRapidoEvent) {
        if (huntAccept(lastPkg, t0, overlayRapidoEvent ? "OverlayMultiWin" : "FgMultiWin",
            overlayRapidoEvent)) {
          scheduleAcceptHuntPollImmediate();
          return;
        }
      }
      // Keep poll only while race is active (not idle Captain FG)
      if (isRaceActive()) {
        scheduleAcceptHuntPoll();
      }
      } // end mayHuntAccept
      // Fall through to routePackage below for Ola / standard dump path
    }

    // Strict package router: non-ride-app → return (except Rapido overlay hunt)
    if (!isRapido && !isOla && !isUber && !rapidoContext) {
      if (windowsChanged) {
        String active = resolveActivePackage();
        if (active != null && isTransientChromePackage(active)) {
          // Shade/heads-up — do not disarm FG hunt
          if (isRaceActive()) scheduleAcceptHuntPoll();
        } else if (active != null && !isRapidoPackageName(active)) {
          setRapidoForeground(false, "left/" + active);
        }
      }
      // Hunt Rapido overlay Accept while any other app is FG (Maps, WhatsApp, Instagram, …)
      if (!isRapidoInteractionBlocked() && !shouldIdleForBubbleOnly()) {
        boolean armedBg = isRaceActive() || isRaceArmed();
        if (AutoClickerConfig.isEnabled()) {
          if (windowsChanged || armedBg) {
            if (huntAccept(lastPkg, t0, "OverlayAnyFg", true)) {
              scheduleAcceptHuntPollImmediate();
              return;
            }
          }
          if (armedBg) {
            scheduleAcceptHuntPoll();
          }
        }
      } else if (isRaceActive()) {
        scheduleAcceptHuntPoll();
      }
      return;
    }

    if (isRapido) {
      lastPkg = pkg;
      syncRapidoForegroundFromActive("event-pkg");
    } else if (isOla) {
      lastPkg = pkg;
      setRapidoForeground(false, "ola-event");
    } else if (isUber) {
      lastPkg = pkg;
      setRapidoForeground(false, "uber-event");
    } else if (rapidoContext) {
      syncRapidoForegroundFromActive("event-null-pkg");
    }

    // Ola / fallback ladder
    AccessibilityNodeInfo source = null;
    try {
      source = event.getSource();
      AccessibilityNodeInfo root = source != null ? source : safeRapidoOrOlaRoot(pkg);
      if (root == null) return;
      String rawRoot = packageOf(root);
      String rootPkg = rawRoot != null ? rawRoot : pkg;
      boolean rideApp = (rootPkg != null && AutoClickerConfig.isRideAppPackage(rootPkg))
          || allowAsRapidoWindow(rawRoot, pkg);
      if (!rideApp) {
        if (source == null) root.recycle();
        return;
      }
      if (rawRoot == null && allowAsRapidoWindow(null, pkg)) {
        rootPkg = resolveRapidoPkg(root, pkg);
      }
      boolean ownRoot = source == null;
      try {
        routePackage(rootPkg, root, t0);
      } finally {
        if (ownRoot) root.recycle();
      }
    } finally {
      if (source != null) source.recycle();
    }

    if (isRaceActive()) scheduleAcceptHuntPoll();
  }

  /** Called from JS bridge when master/nuclear toggles change. */
  public static void onConfigChanged() {
    final AutoClickerService svc = sInstance;
    if (svc == null) return;
    svc.handler.post(() -> {
      svc.syncEngineForeground();
      if (svc.shouldRunAcceptHuntPoll()) svc.scheduleAcceptHuntPoll();
      else svc.stopAcceptHuntPoll("config");
    });
  }

  private void routePackage(String pkg, AccessibilityNodeInfo root, long t0) {
    if (AutoClickerConfig.isOlaPackage(pkg)) {
      handleOla(root, t0, pkg);
    } else if (AutoClickerConfig.isUberPackage(pkg)) {
      handleUber(root, t0, pkg);
    } else if (AutoClickerConfig.isRapidoPackage(pkg)
        || (pkg != null && pkg.contains("rideandhra"))) {
      handleRapido(root, t0, pkg);
    }
  }

  /**
   * NLS / armed hunt for Ola or Uber — active root then package windows.
   * Uses shared strike (not Rapido VERIFY race).
   */
  private boolean huntSharedRideApp(String packageName, long t0, String source) {
    if (!AutoClickerConfig.isEnabled() || packageName == null) return false;
    if (isRapidoInteractionBlocked()) return false;
    AccessibilityNodeInfo root = null;
    try {
      root = safeRapidoOrOlaRoot(packageName);
      if (root != null) {
        if (AutoClickerConfig.isOlaPackage(packageName)) {
          return handleOla(root, t0, packageName);
        }
        if (AutoClickerConfig.isUberPackage(packageName)) {
          return handleUber(root, t0, packageName);
        }
      }
    } catch (Throwable t) {
      Log.e(TAG, "HUNT_SHARED_SAFE " + source, t);
    } finally {
      if (root != null) {
        try { root.recycle(); } catch (Exception ignored) {}
      }
    }
    // Multi-window sweep for Ola/Uber sheets
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows != null) {
        for (AccessibilityWindowInfo w : windows) {
          if (w == null) continue;
          AccessibilityNodeInfo wr = null;
          try {
            wr = w.getRoot();
            if (wr == null) continue;
            String p = packageOf(wr);
            if (p == null || !p.equals(packageName)) {
              wr.recycle();
              wr = null;
              continue;
            }
            boolean ok = AutoClickerConfig.isOlaPackage(packageName)
                ? handleOla(wr, t0, packageName)
                : handleUber(wr, t0, packageName);
            if (ok) return true;
          } catch (Throwable ignored) {
          } finally {
            if (wr != null) {
              try { wr.recycle(); } catch (Exception ignored) {}
            }
          }
        }
      }
    } catch (Throwable ignored) {
    }
    return false;
  }

  /** Find Accept by text (active root first, then all Rapido windows) → smartClick. */
  private boolean huntAccept(String hintPkg, long t0, String source) {
    return huntAccept(hintPkg, t0, source, false);
  }

  /**
   * @param forceOverlayScan when true, scan all Rapido windows even if another app is FG
   *        (overlay sheet hunt). Skips {@link #mayHuntAccept()} — caller must gate spam.
   */
  private boolean huntAccept(String hintPkg, long t0, String source, boolean forceOverlayScan) {
    if (!forceOverlayScan && !mayHuntAccept()) return false;
    if (forceOverlayScan && !AutoClickerConfig.isEnabled()) return false;
    if (!AutoClickerConfig.isEnabled()) return false;
    if (isRapidoInteractionBlocked()) {
      logBlocked("hunt/" + source, "cooldown");
      return false;
    }
    if (olaBurstLock) return false;
    if (!canStartRapidoBurst()) {
      // Avoid log spam while VERIFYING/STRIKING owns the race
      if (racePhase != RacePhase.VERIFYING && racePhase != RacePhase.STRIKING
          && racePhase != RacePhase.COOLDOWN) {
        logBlocked("hunt/" + source, "phase=" + racePhase);
      }
      return false;
    }
    // Non-Rapido FG + only float icon → do nothing (prefer miss over reopen)
    // When armed / FG, shouldIdleForBubbleOnly is false so late overlays / map still hunt.
    if (shouldIdleForBubbleOnly()) {
      logBlocked("hunt/" + source, "bubble-only");
      return false;
    }
    long now = SystemClock.uptimeMillis();
    // While race is hot, never coalesce — every empty hunt may be 1–2ms before Accept paints
    if (!isAcceptRaceHot()
        && lastEmptyHuntAtMs > 0
        && now - lastEmptyHuntAtMs < EMPTY_HUNT_COALESCE_MS) {
      return false;
    }

    AccessibilityNodeInfo hit = null;
    String hitPkg = hintPkg;
    boolean fromOverlay = false;
    boolean rapidoFg = rapidoForeground;
    if (!rapidoFg) {
      String activePkg = resolveActivePackage();
      rapidoFg = activePkg != null && AutoClickerConfig.isRapidoPackage(activePkg);
      if (rapidoFg) setRapidoForeground(true, "hunt-active");
    }

    // 1) ACTIVE ROOT — only when Rapido (or our app) is the focused window.
    // WhatsApp/Instagram FG: skip — getRootInActiveWindow is the wrong tree; go multi-window.
    if (rapidoFg && (isRaceArmed() || racePhase == RacePhase.ARMED
        || racePhase == RacePhase.VERIFYING)) {
      AccessibilityNodeInfo active = null;
      try {
        active = getRootInActiveWindow();
        if (active != null) {
          String rawActive = packageOf(active);
          if (rawActive != null && rawActive.equals(getPackageName())) {
            // Active window is our app — skip; multi-window hunt may still find overlay
            try { active.recycle(); } catch (Exception ignored) {}
            active = null;
          }
        }
        if (active != null) {
          // On-trip chrome must NOT abort hunt — Accept cards still show on nav/map
          if (allowAsRapidoWindow(packageOf(active), hintPkg)) {
            noteOnTripChrome(active);
          }
          String raw = packageOf(active);
          if (allowAsRapidoWindow(raw, hintPkg)) {
            boolean bubbleRoot = isFloatingBubbleNode(active);
            if (!bubbleRoot) {
              try {
                active.getBoundsInScreen(scratchRect2);
                bubbleRoot = isFloatingBubbleBounds(scratchRect2);
              } catch (Exception ignored) {
              }
            }
            if (!bubbleRoot || isAcceptRaceHot()) {
              hit = findAcceptFast(active);
              if (hit == null) hit = findAcceptLabelInRoot(active);
              if (hit != null) hitPkg = resolveRapidoPkg(active, hintPkg);
            } else {
              try {
                active.getBoundsInScreen(scratchRect2);
                noteBubbleWindow(scratchRect2);
              } catch (Exception ignored) {
              }
            }
          }
        }
      } catch (Exception ignored) {
      } finally {
        if (active != null) active.recycle();
      }
    }

    // 2) Multi-window — always while armed; also any-FG overlay sweep
    if (hit == null && (rapidoFg || isAcceptRaceHot() || hasLiveRapidoOverlayCard()
        || rideOverlayActive || forceOverlayScan)) {
      HuntHit multi = findAcceptAcrossRapidoWindows(hintPkg, rapidoFg, forceOverlayScan);
      if (multi != null) {
        hit = multi.node;
        hitPkg = multi.pkg;
        fromOverlay = multi.fromOverlay;
      }
    }

    // 3) Fallback — dedicated Rapido root walk (some OEMs hide windows until explicitly fetched)
    if (hit == null && (isAcceptRaceHot() || forceOverlayScan)) {
      AccessibilityNodeInfo rapidoRoot = findRapidoRootForHunt(forceOverlayScan);
      if (rapidoRoot != null) {
        try {
          hit = findAcceptFast(rapidoRoot);
          if (hit == null) hit = findAcceptLabelInRoot(rapidoRoot);
          if (hit != null) {
            hitPkg = resolveRapidoPkg(rapidoRoot, hintPkg);
            fromOverlay = !rapidoFg;
          }
        } finally {
          if (hit == null) {
            try { rapidoRoot.recycle(); } catch (Exception ignored) {}
          } else {
            try { rapidoRoot.recycle(); } catch (Exception ignored) {}
          }
        }
      }
    }

    if (hit == null) {
      lastEmptyHuntAtMs = SystemClock.uptimeMillis();
      return false;
    }

    // Missed-order deferred to VERIFY (throttled) — don't delay FOUND→CLICK

    logFoundAccept(hit, source, hitPkg);
    return fireHuntHit(hit, hitPkg, fromOverlay, t0, source);
  }

  /** Result of a multi-window Accept hunt. Owns {@code node}. */
  private static final class HuntHit {
    final AccessibilityNodeInfo node;
    final String pkg;
    final boolean fromOverlay;

    HuntHit(AccessibilityNodeInfo node, String pkg, boolean fromOverlay) {
      this.node = node;
      this.pkg = pkg;
      this.fromOverlay = fromOverlay;
    }
  }

  /**
   * Scan every Rapido window for Accept text. Skips bubble-sized windows before findByText.
   * Prefers overlay / sheet windows when not FG; when FG accepts any non-bubble hit.
   * Wide sheets (≥50% / ≥280dp) kept only when they contain Accept.
   */
  private HuntHit findAcceptAcrossRapidoWindows(String hintPkg, boolean rapidoFg) {
    return findAcceptAcrossRapidoWindows(hintPkg, rapidoFg, false);
  }

  private HuntHit findAcceptAcrossRapidoWindows(
      String hintPkg, boolean rapidoFg, boolean overlayScan) {
    AccessibilityNodeInfo hit = null;
    String hitPkg = hintPkg;
    boolean fromOverlay = false;
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows == null) return null;
      for (AccessibilityWindowInfo w : windows) {
        if (w == null) continue;
        AccessibilityNodeInfo root = null;
        try {
          try {
            w.getBoundsInScreen(scratchRect2);
            // Idle: skip float-icon windows. Armed race: still search — many OEMs
            // paint the ride Accept sheet at "bubble" size and we must find Accept.
            if (isFloatingBubbleBounds(scratchRect2)) {
              noteBubbleWindow(scratchRect2);
              if (!isAcceptRaceHot() && !overlayScan) continue;
            }
          } catch (Exception ignored) {
          }
          root = w.getRoot();
          if (root == null) continue;
          String raw = packageOf(root);
          if (raw != null && raw.equals(getPackageName())) continue;
          boolean rapidoWin = allowAsRapidoWindow(raw, hintPkg)
              || (overlayScan && (raw == null || isRapidoPackageName(raw)));
          if (!rapidoWin) continue;
          String p = resolveRapidoPkg(root, hintPkg);
          boolean prefer = isLikelyOverlayWindow(w) || isRideCardSizedBounds(scratchRect2);

          AccessibilityNodeInfo candidate = findAcceptFast(root);
          if (candidate == null) {
            candidate = findAcceptLabelInRoot(root);
          }
          if (candidate == null) continue;

          // Idle: drop bubble-window hits. Armed: keep real Accept labels.
          try {
            AccessibilityWindowInfo nw = candidate.getWindow();
            if (nw != null) {
              nw.getBoundsInScreen(scratchRect);
              if (isFloatingBubbleBounds(scratchRect) && !isAcceptRaceHot() && !overlayScan) {
                noteBubbleWindow(scratchRect);
                candidate.recycle();
                continue;
              }
            }
          } catch (Exception ignored) {
          }
          if (isFloatingBubbleNode(candidate) && !isAcceptRaceHot() && !overlayScan) {
            candidate.recycle();
            continue;
          }

          if (hit != null) hit.recycle();
          hit = candidate;
          hitPkg = p;
          fromOverlay = prefer || !rapidoFg;
          // Prefer overlay/sheet; when FG take first Accept (sheet or main)
          if (prefer || rapidoFg) break;
        } finally {
          if (root != null) {
            try { root.recycle(); } catch (Exception ignored) {}
          }
        }
      }
    } catch (Exception ignored) {
    }
    if (hit == null) return null;
    return new HuntHit(hit, hitPkg, fromOverlay);
  }

  /** Shared Accept-hit → dual-strike → VERIFY. Owns {@code hit}. */
  private boolean fireHuntHit(
      AccessibilityNodeInfo hit,
      String hitPkg,
      boolean fromOverlay,
      long t0,
      String source
  ) {
    lastEmptyHuntAtMs = 0;
    lastHuntAtMs = SystemClock.uptimeMillis();
    String pkg = hitPkg != null ? hitPkg : lastPkg;
    lastPkg = pkg;
    refreshRaceArm("accept-sighted");
    // Strike 0 — min gate + find→click clock inside smartClick; VERIFY before disarm
    boolean started = smartClickAccept(hit, pkg,
        fromOverlay ? "OverlayHunt/" + source : "Hunt/" + source, t0);
    if (acceptSuccessLatch) {
      return true;
    }
    if (!started && !rapidoBurstLock && !verifyingAccept) {
      lastEmptyHuntAtMs = SystemClock.uptimeMillis();
      return false;
    }
    int cx = rapidoBurstX > 0 ? rapidoBurstX : verifyCx;
    int cy = rapidoBurstY > 0 ? rapidoBurstY : verifyCy;
    if (fromOverlay) {
      if (hasOverlayCardBounds()) {
        scratchRect2.set(overlayCardBoundsRect);
      } else {
        scratchRect2.set(cx - 48, cy - 120, cx + 48, cy + 24);
      }
      if (cx > 0 && cy > 0 && !scratchRect2.contains(cx, cy)) {
        scratchRect2.union(cx - 24, cy - 24, cx + 24, cy + 24);
      }
      if (!isFloatingBubbleBounds(scratchRect2) && !pointInsideKnownBubble(cx, cy)) {
        markOverlayLive(scratchRect2, cx, cy);
        AutoClickerConfig.cacheOverlayTapPoint(pkg, overlayTapX, overlayTapY);
        setRideOverlayActive(true, "hunt-hit");
      }
    } else if (cx > 0 && cy > 0 && !pointInsideKnownBubble(cx, cy)) {
      cacheAcceptPoint(pkg, cx, cy);
    }
    return true;
  }

  private static String packageOf(AccessibilityNodeInfo node) {
    if (node == null) return null;
    CharSequence p = node.getPackageName();
    return p != null ? p.toString() : null;
  }

  /**
   * Fail-open for null packageName (Android 15): allow when hint/last/active is Rapido
   * or race is armed / overlay live. Non-null non-Rapido packages are always refused.
   */
  private boolean allowAsRapidoWindow(String pkgFromNode, String hint) {
    // Never treat SUPER RIDEX windows as Captain
    if (pkgFromNode != null && pkgFromNode.equals(getPackageName())) return false;
    if (pkgFromNode != null) return isRapidoPackageName(pkgFromNode);
    if (hint != null && isRapidoPackageName(hint)) return true;
    if (lastPkg != null && isRapidoPackageName(lastPkg)) return true;
    if (rapidoForeground || isRaceArmed() || hasLiveRapidoOverlayCard()
        || (rideOverlayActive && isRaceActive())) {
      return true;
    }
    try {
      String active = resolveActivePackage();
      if (active != null && isRapidoPackageName(active)) return true;
    } catch (Exception ignored) {
    }
    return false;
  }

  /** Best Rapido package string for a node/window with possibly-null packageName. */
  private String resolveRapidoPkg(AccessibilityNodeInfo node, String hint) {
    String p = packageOf(node);
    if (p != null && isRapidoPackageName(p)) return p;
    if (hint != null && isRapidoPackageName(hint)) return hint;
    if (lastPkg != null && isRapidoPackageName(lastPkg)) return lastPkg;
    try {
      String active = resolveActivePackage();
      if (active != null && isRapidoPackageName(active)) return active;
    } catch (Exception ignored) {
    }
    return p != null ? p : "com.rapido.rider";
  }

  /**
   * Light Accept find — Accept / ACCEPT / Accept Ride / Hindi via findByText.
   * Preferred on every Nuclear / low-end hot path. Never matches "Accepted".
   */
  private AccessibilityNodeInfo findAcceptFast(AccessibilityNodeInfo root) {
    if (root == null) return null;
    for (String search : ACCEPT_LABELS_FAST) {
      AccessibilityNodeInfo found = firstAcceptByText(root, search);
      if (found != null) return found;
    }
    return null;
  }

  /**
   * Reject only extreme top-chrome false Accepts (status/heads-up).
   * Do NOT use ~0.38 floor — real mid-card Accepts sit around 25–40%.
   * Also reject huge nodes whose center would be mid-screen spam.
   */
  private boolean isOversizedAcceptBounds(Rect r) {
    if (r == null || r.isEmpty()) return true;
    ensureScreenMetrics();
    long area = (long) r.width() * (long) r.height();
    // Only reject near-fullscreen / full ride-sheet containers.
    // Real Accept CTAs are often wide (>55% width) — never treat those as oversized.
    return area > (long) (screenArea * 0.42f)
        || (r.width() >= screenW * 0.92f && r.height() >= screenH * 0.32f);
  }

  private boolean isExtremeTopChromeAccept(Rect r) {
    if (r == null || r.isEmpty()) return true;
    ensureScreenMetrics();
    if (screenH <= 0) return false;
    // Only extreme status strip — keep mid/upper overlay Accepts
    return r.centerY() < screenH * 0.06f;
  }

  private AccessibilityNodeInfo firstAcceptByText(AccessibilityNodeInfo root, String search) {
    List<AccessibilityNodeInfo> nodes;
    try {
      nodes = root.findAccessibilityNodeInfosByText(search);
    } catch (Exception e) {
      return null;
    }
    if (nodes == null) return null;
    AccessibilityNodeInfo found = null;
    for (AccessibilityNodeInfo node : nodes) {
      if (node == null) continue;
      if (found != null) {
        try { node.recycle(); } catch (Exception ignored) {}
        continue;
      }
      if (!isRealAcceptLabel(nodeTextCs(node))) {
        node.recycle();
        continue;
      }
      node.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty() || scratchRect.width() < 24 || scratchRect.height() < 16) {
        node.recycle();
        continue;
      }
      // Idle other apps: skip float-icon nodes. Captain FG / armed: keep Accept CTAs.
      if ((isBubbleLikeClickTarget(scratchRect) || isFloatingBubbleNode(node))
          && !isAcceptRaceHot() && !rapidoForeground) {
        node.recycle();
        continue;
      }
      // Extreme top chrome only — keep mid-card Accepts
      if (isExtremeTopChromeAccept(scratchRect)) {
        node.recycle();
        continue;
      }
      // Huge card/sheet nodes → center tap is mid-screen spam
      if (isOversizedAcceptBounds(scratchRect)) {
        node.recycle();
        continue;
      }
      found = node;
    }
    return found;
  }

  /** Find real Accept label anywhere in this tree (any screen position). */
  private AccessibilityNodeInfo findAcceptLabelInRoot(AccessibilityNodeInfo root) {
    if (root == null) return null;

    AccessibilityNodeInfo fast = findAcceptFast(root);
    if (fast != null) return fast;

    // Full label set on ALL devices (low-end previously skipped exotic labels)
    for (String search : ACCEPT_LABELS) {
      // Fast path already tried ACCEPT_LABELS_FAST — skip duplicates
      if ("Accept".equals(search) || "ACCEPT".equals(search)
          || "Accept Ride".equals(search) || "स्वीकार".equals(search)) continue;
      AccessibilityNodeInfo found = firstAcceptByText(root, search);
      if (found != null) return found;
    }

    // Fallback BFS on contentDescription (some buttons have no getText)
    ArrayDeque<AccessibilityNodeInfo> q = scratchQueue;
    q.clear();
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    while (!q.isEmpty() && walked < treeWalkCap) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      if (isRealAcceptLabel(nodeTextCs(n))) {
        n.getBoundsInScreen(scratchRect);
        if (!scratchRect.isEmpty() && scratchRect.width() >= 24
            && !isExtremeTopChromeAccept(scratchRect)
            && !isOversizedAcceptBounds(scratchRect)
            && (isAcceptRaceHot()
                || (!isBubbleLikeClickTarget(scratchRect) && !isFloatingBubbleNode(n)))) {
          while (!q.isEmpty()) q.removeFirst().recycle();
          return n;
        }
      }
      for (int i = 0; i < n.getChildCount(); i++) {
        AccessibilityNodeInfo c = n.getChild(i);
        if (c != null) q.add(c);
      }
      n.recycle();
    }
    while (!q.isEmpty()) q.removeFirst().recycle();
    return null;
  }

  private boolean isRealAcceptLabel(CharSequence text) {
    if (text == null) return false;
    int len = text.length();
    if (len == 0 || len > 48) return false;
    if (containsIgnoreCase(text, "accepted") || containsIgnoreCase(text, "reject")
        || containsIgnoreCase(text, "decline") || containsIgnoreCase(text, "cancel")
        || containsIgnoreCase(text, "not accept")) {
      return false;
    }
    if (indexOfSeq(text, "स्वीकार") >= 0) return true;
    if (containsIgnoreCase(text, "accept")) return true;
    return equalsIgnoreCaseTrim(text, "take ride")
        || equalsIgnoreCaseTrim(text, "take order");
  }

  /** Prefer CharSequence over String alloc for Accept match. */
  private static CharSequence nodeTextCs(AccessibilityNodeInfo node) {
    if (node == null) return null;
    CharSequence t = node.getText();
    if (t != null && t.length() > 0) return t;
    CharSequence d = node.getContentDescription();
    if (d != null && d.length() > 0) return d;
    return null;
  }

  // ── CharSequence match helpers (no toLowerCase String alloc) ──────────────

  private static boolean containsIgnoreCase(CharSequence hay, String needle) {
    return indexOfIgnoreCase(hay, needle) >= 0;
  }

  private static int indexOfSeq(CharSequence hay, String needle) {
    if (hay == null || needle == null) return -1;
    int nLen = needle.length();
    int hLen = hay.length();
    if (nLen == 0 || nLen > hLen) return nLen == 0 ? 0 : -1;
    outer:
    for (int i = 0; i <= hLen - nLen; i++) {
      for (int j = 0; j < nLen; j++) {
        if (hay.charAt(i + j) != needle.charAt(j)) continue outer;
      }
      return i;
    }
    return -1;
  }

  private static int indexOfIgnoreCase(CharSequence hay, String needle) {
    if (hay == null || needle == null) return -1;
    int nLen = needle.length();
    int hLen = hay.length();
    if (nLen == 0) return 0;
    if (nLen > hLen) return -1;
    char n0 = Character.toLowerCase(needle.charAt(0));
    outer:
    for (int i = 0; i <= hLen - nLen; i++) {
      if (Character.toLowerCase(hay.charAt(i)) != n0) continue;
      for (int j = 1; j < nLen; j++) {
        if (Character.toLowerCase(hay.charAt(i + j))
            != Character.toLowerCase(needle.charAt(j))) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static boolean startsWithIgnoreCase(CharSequence hay, String prefix) {
    if (hay == null || prefix == null) return false;
    int nLen = prefix.length();
    if (hay.length() < nLen) return false;
    for (int i = 0; i < nLen; i++) {
      if (Character.toLowerCase(hay.charAt(i)) != Character.toLowerCase(prefix.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean equalsIgnoreCaseTrim(CharSequence hay, String expect) {
    if (hay == null || expect == null) return false;
    int start = 0;
    int end = hay.length();
    while (start < end && Character.isWhitespace(hay.charAt(start))) start++;
    while (end > start && Character.isWhitespace(hay.charAt(end - 1))) end--;
    int len = end - start;
    if (len != expect.length()) return false;
    for (int i = 0; i < len; i++) {
      if (Character.toLowerCase(hay.charAt(start + i))
          != Character.toLowerCase(expect.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  // ─── Ola (MeClicker i) ────────────────────────────────────────────────────

  private boolean handleOla(AccessibilityNodeInfo root, long t0, String pkg) {
    long now = SystemClock.uptimeMillis();
    if (now - lastOlaAttemptMs < Math.min(COOLDOWN_MS, 400L)) return false;
    if (olaBurstLock) {
      // Stuck Ola lock (Bug A) — clear after short age
      if (now - lastOlaAttemptMs > 2000L) {
        olaBurstLock = false;
      } else {
        return false;
      }
    }

    AccessibilityNodeInfo acceptBtn = findOlaAcceptButton(root);
    if (acceptBtn == null) {
      acceptBtn = findAcceptFast(root);
    }
    if (acceptBtn == null) {
      acceptBtn = findAcceptLabelInRoot(root);
    }
    if (acceptBtn == null) return false;

    lastOlaAttemptMs = now;
    lastPkg = pkg != null ? pkg : lastPkg;
    return fireSharedAcceptStrike(acceptBtn, pkg, "Ola", t0 > 0 ? t0 : now);
  }

  /** Locate Ola Accept via view-id (primary) then near price/pickup nodes. */
  private AccessibilityNodeInfo findOlaAcceptButton(AccessibilityNodeInfo root) {
    if (root == null) return null;
    List<AccessibilityNodeInfo> direct = null;
    try {
      direct = root.findAccessibilityNodeInfosByViewId(OLA_ACCEPT_ID);
      if (direct == null || direct.isEmpty()) {
        direct = root.findAccessibilityNodeInfosByViewId("com.olacabs.driver:id/btn_accept");
      }
      if (direct != null && !direct.isEmpty()) {
        AccessibilityNodeInfo hit = direct.get(0);
        for (int i = 1; i < direct.size(); i++) direct.get(i).recycle();
        return hit;
      }
    } catch (Exception ignored) {
    }

    // Near best price node
    List<AccessibilityNodeInfo> prices = null;
    try {
      prices = root.findAccessibilityNodeInfosByViewId(OLA_PRICE_ID);
      if (prices == null || prices.isEmpty()) {
        prices = root.findAccessibilityNodeInfosByViewId("com.olacabs.driver:id/tv_compact_price");
      }
    } catch (Exception ignored) {
    }
    AccessibilityNodeInfo best = null;
    double bestPrice = -1;
    if (prices != null) {
      for (AccessibilityNodeInfo n : prices) {
        String text = nodeText(n);
        if (text == null || text.contains("/")) {
          n.recycle();
          continue;
        }
        double p = parseLooseDouble(text);
        if (p > bestPrice) {
          if (best != null) best.recycle();
          best = n;
          bestPrice = p;
        } else {
          n.recycle();
        }
      }
    }
    if (best != null) {
      AccessibilityNodeInfo near = findOlaAcceptNear(best);
      best.recycle();
      if (near != null) return near;
    }
    return null;
  }

  /** Uber: find Accept by text (ids change often) → same universal strike. */
  private boolean handleUber(AccessibilityNodeInfo root, long t0, String pkg) {
    long now = SystemClock.uptimeMillis();
    if (now - lastOlaAttemptMs < Math.min(COOLDOWN_MS, 400L) && olaBurstLock) {
      if (now - lastOlaAttemptMs > 2000L) {
        olaBurstLock = false;
      } else {
        return false;
      }
    }
    if (root == null) return false;

    AccessibilityNodeInfo acceptBtn = findAcceptFast(root);
    if (acceptBtn == null) {
      acceptBtn = findAcceptLabelInRoot(root);
    }
    if (acceptBtn == null) {
      // Common Uber driver labels
      acceptBtn = firstAcceptByText(root, "Accept");
      if (acceptBtn == null) acceptBtn = firstAcceptByText(root, "Slide to Accept");
      if (acceptBtn == null) acceptBtn = firstAcceptByText(root, "ACCEPT");
    }
    if (acceptBtn == null) return false;

    lastOlaAttemptMs = now;
    lastPkg = pkg != null ? pkg : lastPkg;
    return fireSharedAcceptStrike(acceptBtn, pkg, "Uber", t0 > 0 ? t0 : now);
  }

  private AccessibilityNodeInfo findOlaAcceptNear(AccessibilityNodeInfo from) {
    AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(from);
    for (int d = 0; d < PARENT_CLIMB_OLA && cur != null; d++) {
      List<AccessibilityNodeInfo> btns = cur.findAccessibilityNodeInfosByViewId(OLA_ACCEPT_ID);
      if (btns == null || btns.isEmpty()) {
        btns = cur.findAccessibilityNodeInfosByViewId("com.olacabs.driver:id/btn_accept");
      }
      if (btns != null && !btns.isEmpty()) {
        AccessibilityNodeInfo hit = btns.get(0);
        for (int i = 1; i < btns.size(); i++) btns.get(i).recycle();
        cur.recycle();
        return hit;
      }
      AccessibilityNodeInfo parent = cur.getParent();
      cur.recycle();
      cur = parent;
    }
    if (cur != null) cur.recycle();
    return null;
  }

  // ─── Rapido (micro-burst race) ────────────────────────────────────────────

  private void handleRapido(AccessibilityNodeInfo root, long t0, String pkg) {
    // Captain FG (home) or NLS-armed — never on idle non-Captain screens
    if (!mayHuntAccept()) return;
    if (!canStartRapidoBurst()) return;
    if (isRapidoInteractionBlocked()) return;
    if (shouldIdleForBubbleOnly()) return;
    if (root != null && isFloatingBubbleNode(root)) return;
    try {
      if (root != null) {
        AccessibilityWindowInfo w = root.getWindow();
        if (w != null) {
          w.getBoundsInScreen(scratchRect2);
          if (isFloatingBubbleBounds(scratchRect2)) {
            noteBubbleWindow(scratchRect2);
            return;
          }
        }
      }
    } catch (Exception ignored) {
    }
    // On-trip/nav chrome: note it, but KEEP hunting Accept (along-route offers)
    noteOnTripChrome(root);

    boolean nuclear = AutoClickerConfig.isNuclearMode();

    // Nuclear: Accept-first — full labels / BFS on all devices
    if (nuclear) {
      AccessibilityNodeInfo accept = findAcceptFast(root);
      if (accept == null) {
        accept = findAcceptLabelInRoot(root);
      }
      if (accept == null) {
        accept = bfsFindAccept(root, true);
      }
      if (accept == null) {
        accept = bfsFindAccept(root, false);
      }
      if (accept != null) {
        if (driverOnTripChrome) {
          Log.i(TAG, "NUCLEAR_ACCEPT while on-trip chrome");
        }
        smartClickAccept(accept, pkg, "Rapido-Nuclear", t0);
      }
      return;
    }

    // Standard: dump text + filters + smartClick — do NOT abort on on-trip chrome
    String dump = dumpText(root);
    if (dump.isEmpty()) return;

    if (dumpLooksOnTrip(dump)) {
      driverOnTripChrome = true;
    }
    String lower = dump.toLowerCase(Locale.US);
    if (lower.contains("completed") || lower.contains("cancelled")) {
      // Still allow if Accept is present (new offer during wrap-up)
      if (!lower.contains("accept") && !dump.contains("स्वीकार")) return;
    }
    if (!lower.contains("accept") && !dump.contains("स्वीकार")) return;

    boolean moneyOrKm = dump.contains("₹") || lower.contains("rs") || lower.contains("cash")
        || lower.contains("km") || lower.contains("fare");
    if (!moneyOrKm) return;

    if (AutoClickerConfig.getFilterMode() != AutoClickerConfig.MODE_PRICE) {
      List<Float> kms = parseAllKm(dump);
      if (kms.size() < 2) return;
      if (kms.get(0) > AutoClickerConfig.getMaxPickup()) return;
      if (AutoClickerConfig.getMaxDrop() != 0f && kms.get(1) < AutoClickerConfig.getMaxDrop()) return;
    }

    AccessibilityNodeInfo accept = findAcceptFast(root);
    if (accept == null) {
      accept = bfsFindAccept(root, true);
    }
    if (accept == null) {
      accept = bfsFindAccept(root, false);
    }
    if (accept == null) return;
    smartClickAccept(accept, pkg, "Rapido", t0);
  }

  private String dumpText(AccessibilityNodeInfo root) {
    StringBuilder sb = scratchSb;
    sb.setLength(0);
    ArrayDeque<AccessibilityNodeInfo> q = scratchQueue;
    q.clear();
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    while (!q.isEmpty() && walked < treeWalkCap) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      CharSequence t = n.getText();
      if (t != null && t.length() > 0) sb.append(t).append(' ');
      CharSequence d = n.getContentDescription();
      if (d != null && d.length() > 0) sb.append(d).append(' ');
      for (int i = 0; i < n.getChildCount(); i++) {
        AccessibilityNodeInfo c = n.getChild(i);
        if (c != null) q.add(c);
      }
      n.recycle();
    }
    while (!q.isEmpty()) q.removeFirst().recycle();
    return sb.toString();
  }

  private AccessibilityNodeInfo bfsFindAccept(AccessibilityNodeInfo root, boolean clickableOnly) {
    ArrayDeque<AccessibilityNodeInfo> q = scratchQueue;
    q.clear();
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    while (!q.isEmpty() && walked < treeWalkCap) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      CharSequence text = nodeTextCs(n);
      boolean isAccept = isRealAcceptLabel(text);
      if (isAccept && (!clickableOnly || n.isClickable())) {
        n.getBoundsInScreen(scratchRect);
        if (isExtremeTopChromeAccept(scratchRect)) {
          n.recycle();
          continue;
        }
        if ((isBubbleLikeClickTarget(scratchRect) || isFloatingBubbleNode(n))
            && !isAcceptRaceHot() && !rapidoForeground) {
          n.recycle();
          continue;
        }
        // Don't recycle n — caller owns it
        while (!q.isEmpty()) q.removeFirst().recycle();
        return n;
      }
      for (int i = 0; i < n.getChildCount(); i++) {
        AccessibilityNodeInfo c = n.getChild(i);
        if (c != null) q.add(c);
      }
      n.recycle();
    }
    while (!q.isEmpty()) q.removeFirst().recycle();
    return null;
  }

  /**
   * Accept → dual-strike → VERIFY (restrike only).
   * Owns {@code node}.
   */
  private boolean smartClickAccept(AccessibilityNodeInfo node, String pkg, String tag, long t0) {
    // Clock starts the moment Accept is handed to us (found) — before fare/climb/click
    final long findAt = SystemClock.uptimeMillis();
    if (node == null) return false;
    final boolean onOverlay = isNodeOnRideOverlay(node);
    if (!mayHuntAccept() && !onOverlay) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    if (isRapidoInteractionBlocked() || !canStartRapidoBurst()) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    // Armed race: skip bubble-only scan (already false when armed) — keep cheap checks only
    if (!isRaceActive() && shouldIdleForBubbleOnly()) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    if (!nodeIsRapido(node)) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    if (isFloatingBubbleNode(node) && !isAcceptRaceHot() && !rapidoForeground && !onOverlay) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    // Not armed yet: Captain FG or floating overlay + live offer cues may arm once.
    if (!isRaceActive()) {
      if (rapidoForeground || onOverlay) {
        if (!acceptLooksLikeLiveOffer(node)) {
          try { node.recycle(); } catch (Exception ignored) {}
          return false;
        }
      } else {
        try { node.recycle(); } catch (Exception ignored) {}
        return false;
      }
      arm("ui-sighted/" + tag);
    }
    // Missed-order: throttled — never block the first armed strike with a full dump
    // (VERIFY still catches dead cards). Skip when just armed from ride alert.
    // No min-fare gate — Accept immediately; fare enrich is post-strike only.

    // Gesture ALWAYS at Accept LABEL center — never card mid-point spray
    node.getBoundsInScreen(scratchRect);
    if (scratchRect.isEmpty()
        || isExtremeTopChromeAccept(scratchRect)
        || isOversizedAcceptBounds(scratchRect)) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    // Idle only: reject chat-head geometry. Armed / overlay: Accept CTA may be compact.
    if (isBubbleLikeClickTarget(scratchRect) && !isAcceptRaceHot() && !rapidoForeground
        && !onOverlay) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    int cx = scratchRect.centerX();
    int cy = scratchRect.centerY();
    if (cx <= 0 || cy <= 0) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    if (pointInsideRapidoBubbleWindow(cx, cy) && !isAcceptRaceHot() && !rapidoForeground
        && !onOverlay) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }

    // ── STRIKE-0 FIRST (fastest path: 1ms gesture + ACTION_CLICK) ──────────
    // Mark overlay + lock BEFORE gesture so canGestureAt passes; defer climb/cache.
    scratchRect2.set(scratchRect);
    markOverlayLive(scratchRect2, cx, cy);
    if (!rapidoForeground) {
      setRideOverlayActive(true, "smart-" + tag);
    }
    verifyCx = cx;
    verifyCy = cy;
    rapidoBurstX = cx;
    rapidoBurstY = cy;
    setRacePhase(RacePhase.STRIKING, tag);
    long now = SystemClock.uptimeMillis();
    lastRapidoAttemptMs = now;
    rapidoBurstLock = true;
    handler.removeCallbacks(rapidoMicroBurstRunnable);

    // Fastest dual-fire: ACTION_CLICK then 1ms gesture (dispatch returns immediately)
    boolean clicked = false;
    if (node.isClickable()) {
      clicked = performRapidoClick(node);
    }
    boolean gestOk = gestureTap(cx, cy, RAPIDO_GESTURE_MS_STRIKE0);

    // Resolve clickable parent AFTER strike-0 (for micro-burst / ACTION_CLICK retry)
    AccessibilityNodeInfo clickTarget = resolveAcceptClickTarget(node);
    if (clickTarget == null) {
      clickTarget = AccessibilityNodeInfo.obtain(node);
    }
    if (!clicked) {
      clicked = actionClickAccept(clickTarget);
    }
    try { node.recycle(); } catch (Exception ignored) {}

    if (rapidoBurstNode != null && rapidoBurstNode != clickTarget) {
      try { rapidoBurstNode.recycle(); } catch (Exception ignored) {}
    }
    rapidoBurstNode = clickTarget;
    rapidoBurstPkg = pkg;
    rapidoBurstTag = tag;
    rapidoBurstT0 = findAt;
    rapidoBurstIndex = 0;

    // Backup for ALL OEMs — Realme/ColorOS/HyperOS swallow 1ms alone (Bug C)
    scheduleBackupAcceptGesture(cx, cy);

    cacheAcceptPoint(pkg, cx, cy);
    refreshRaceArm("smart-accept");

    rapidoBurstFirstOk = clicked || gestOk;
    Log.i(TAG, "STRIKE0 pkg=" + pkg + " tag=" + tag
        + " findMs=" + (SystemClock.uptimeMillis() - findAt)
        + " clickOk=" + clicked + " gestOk=" + gestOk
        + " @" + cx + "," + cy + " heavyOem=" + heavyOem);
    if (rapidoBurstFirstOk) {
      markUiStrike();
    } else {
      // Still mark + verify — backup may register; do not silent-drop (Bug C)
      markUiStrike();
    }
    beginVerify(pkg, tag, findAt, cx, cy, false);

    if (now >= rapidoRetryUntilMs) {
      rapidoRetryUntilMs = now + RAPIDO_RETRY_WINDOW_MS;
      handler.removeCallbacks(rapidoRetryWindowEndRunnable);
      handler.postDelayed(rapidoRetryWindowEndRunnable, RAPIDO_RETRY_WINDOW_MS);
    }
    if (rapidoMicroExtraStrikes > 0) {
      handler.postDelayed(rapidoMicroBurstRunnable, rapidoMicroIntervalMs);
    } else {
      finishRapidoMicroBurst("strike0-only");
    }
    return true;
  }

  /**
   * Sync ACTION_CLICK only. Skip refresh() on first attempt — it was a major latency source.
   * Refresh+retry only if the first click missed.
   */
  /** When armed, skip expensive refresh — restrike path re-finds live Accept anyway. */
  private boolean actionClickAccept(AccessibilityNodeInfo clickTarget) {
    if (clickTarget == null) return false;
    if (isRapidoInteractionBlocked()) return false;
    final boolean raceHot = isAcceptRaceHot();
    try {
      if (!nodeIsRapido(clickTarget)) return false;
      if (isFloatingBubbleNode(clickTarget) && !raceHot && !rapidoForeground) return false;
      try {
        clickTarget.getBoundsInScreen(scratchRect);
        if (isBubbleLikeClickTarget(scratchRect) && !raceHot && !rapidoForeground) return false;
      } catch (Exception ignored) {
      }
      if (clickTarget.isClickable()) {
        boolean clicked = performRapidoClick(clickTarget);
        if (clicked) return true;
      }
      if (!raceHot) {
        try { clickTarget.refresh(); } catch (Exception ignored) {}
        if (isFloatingBubbleNode(clickTarget) && !rapidoForeground) return false;
        if (clickTarget.isClickable()) {
          return performRapidoClick(clickTarget);
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  /**
   * ACTION_CLICK first (Rapido node only), then short gesture (no callback wait).
   * Used by micro-burst strikes 1+ (strike 0 uses actionClickAccept + posted gesture).
   */
  private boolean dualStrikeAccept(
      AccessibilityNodeInfo clickTarget, int cx, int cy, boolean allowGesture) {
    boolean gestOk = false;
    if (allowGesture && cx > 0 && cy > 0 && canGestureAt(cx, cy)) {
      gestOk = gestureTap(cx, cy, rapidoGestureMs);
    }
    boolean clicked = actionClickAccept(clickTarget);
    return clicked || gestOk;
  }

  private void finishRapidoMicroBurst(String reason) {
    handler.removeCallbacks(rapidoMicroBurstRunnable);
    long now = SystemClock.uptimeMillis();
    rapidoBurstLock = false;
    rapidoBurstX = 0;
    rapidoBurstY = 0;
    if (rapidoBurstNode != null) {
      try { rapidoBurstNode.recycle(); } catch (Exception ignored) {}
      rapidoBurstNode = null;
    }
    // Long CD only after verified Accept (accept-ok) or destroy — never on verify-miss
    if ("destroy".equals(reason) || "accept-ok".equals(reason)) {
      rapidoCooldownUntilMs = now + RAPIDO_POST_HIT_COOLDOWN_MS;
    }
    rapidoBurstPkg = null;
    rapidoBurstTag = null;
    rapidoBurstT0 = 0;
  }

  /**
   * Pick the node whose bounds center is the Accept tap point (ACTION_CLICK + gesture).
   * <ol>
   *   <li>Accept text itself if {@code isClickable()}</li>
   *   <li>Else nearest clickable ancestor that still contains Accept and is
   *       ≤ {@link #MAX_CLICK_AREA_RATIO}× Accept-text area (button-sized)</li>
   *   <li>Else {@code null} — caller taps Accept text center via gesture</li>
   * </ol>
   */
  private AccessibilityNodeInfo resolveAcceptClickTarget(AccessibilityNodeInfo accept) {
    if (accept == null) return null;
    accept.getBoundsInScreen(scratchRect);
    if (scratchRect.isEmpty()) return null;
    final int acceptArea = Math.max(1, scratchRect.width() * scratchRect.height());
    final int acceptCx = scratchRect.centerX();
    final int acceptCy = scratchRect.centerY();

    if (accept.isClickable()) {
      return AccessibilityNodeInfo.obtain(accept);
    }

    AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(accept);
    for (int d = 0; d < PARENT_CLIMB_CLICK && cur != null; d++) {
      AccessibilityNodeInfo parent = cur.getParent();
      cur.recycle();
      cur = parent;
      if (cur == null) break;
      if (!cur.isClickable()) continue;

      cur.getBoundsInScreen(scratchRect2);
      if (scratchRect2.isEmpty() || !scratchRect2.contains(acceptCx, acceptCy)) {
        continue;
      }
      long parentArea = (long) scratchRect2.width() * (long) scratchRect2.height();
      if (parentArea > (long) (MAX_CLICK_AREA_RATIO * acceptArea)) {
        // Full-card / sheet parent — center would miss Accept; use text + gesture
        cur.recycle();
        return null;
      }
      return cur;
    }
    if (cur != null) cur.recycle();
    return null;
  }

  // ─── Parsing ──────────────────────────────────────────────────────────────

  /** Collect fare-ish text near Accept for live-offer cues only. */
  private String collectPriceNearAccept(AccessibilityNodeInfo accept) {
    StringBuilder sb = scratchSb;
    sb.setLength(0);
    if (accept == null) return "";
    AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(accept);
    try {
      for (int depth = 0; depth < 5 && cur != null && sb.length() < 280; depth++) {
        appendNodeChars(sb, cur);
        if (sb.indexOf("₹") >= 0 || containsIgnoreCase(sb, "rs") || containsIgnoreCase(sb, "inr")) {
          break;
        }
        AccessibilityNodeInfo parent = cur.getParent();
        if (parent == null) break;
        int n = parent.getChildCount();
        int max = Math.min(n, 8);
        for (int i = 0; i < max && sb.length() < 280; i++) {
          AccessibilityNodeInfo child = parent.getChild(i);
          if (child == null) continue;
          try {
            appendNodeChars(sb, child);
            int gcMax = Math.min(child.getChildCount(), 4);
            for (int j = 0; j < gcMax && sb.length() < 280; j++) {
              AccessibilityNodeInfo gc = child.getChild(j);
              if (gc == null) continue;
              try {
                appendNodeChars(sb, gc);
              } finally {
                gc.recycle();
              }
            }
          } finally {
            child.recycle();
          }
        }
        if (sb.indexOf("₹") >= 0 || containsIgnoreCase(sb, "rs") || containsIgnoreCase(sb, "inr")) {
          cur.recycle();
          cur = parent;
          break;
        }
        cur.recycle();
        cur = parent;
      }
    } finally {
      if (cur != null) {
        try { cur.recycle(); } catch (Exception ignored) {}
      }
    }
    return sb.toString();
  }

  private static void appendNodeChars(StringBuilder sb, AccessibilityNodeInfo n) {
    if (n == null) return;
    CharSequence t = n.getText();
    if (t != null && t.length() > 0) sb.append(t).append(' ');
    CharSequence d = n.getContentDescription();
    if (d != null && d.length() > 0) sb.append(d).append(' ');
  }

  private static double parseRapidoPrice(String dump) {
    if (dump == null || dump.isEmpty()) return 0;
    Matcher combo = PRICE_COMBO.matcher(dump);
    if (combo.find()) {
      return parseLooseDouble(combo.group(1)) + parseLooseDouble(combo.group(2));
    }
    Matcher single = PRICE_SINGLE.matcher(dump);
    if (single.find()) {
      return parseLooseDouble(single.group(1));
    }
    Matcher any = PRICE_ANY.matcher(dump);
    if (any.find()) return parseLooseDouble(any.group(1));
    Matcher rev = PRICE_REVERSED.matcher(dump);
    if (rev.find()) return parseLooseDouble(rev.group(1));
    return 0;
  }

  private static List<Float> parseAllKm(String dump) {
    List<Float> out = new ArrayList<>();
    Matcher m = KM_PATTERN.matcher(dump);
    while (m.find()) {
      out.add((float) parseLooseDouble(m.group(1)));
    }
    return out;
  }

  private static float parseFirstNumber(String text) {
    if (text == null) return 0f;
    Matcher m = FIRST_NUMBER.matcher(text);
    if (m.find()) return (float) parseLooseDouble(m.group(1));
    return 0f;
  }

  private static double parseLooseDouble(String text) {
    if (text == null) return 0;
    String cleaned = text.replaceAll("[^0-9.]", "");
    if (cleaned.isEmpty() || cleaned.equals(".")) return 0;
    try {
      return Double.parseDouble(cleaned);
    } catch (Exception e) {
      return 0;
    }
  }

  // ─── Gesture / helpers ────────────────────────────────────────────────────

  /**
   * Hard-gated absolute tap. Never taps the float-icon bubble.
   * Rapido FG → any non-bubble point OK.
   * Else only if (x,y) is inside a *live* Rapido overlay card.
   * Ola FG allowed for Ola burst path.
   */
  private boolean gestureTap(int x, int y, long durationMs) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false;
    if (x <= 0 || y <= 0) return false;
    if (isRapidoInteractionBlocked()) return false;
    if (shouldIdleForBubbleOnly()) return false;
    // SUPER RIDEX FG: allow gesture only on a measured Rapido Accept point
    if (isSelfAppForeground() && !hasLiveRapidoOverlayCard() && !isAcceptRaceHot()) return false;
    // canGestureAt enforces exact Accept center only (no random spray)
    if (!canGestureAt(x, y)) return false;
    // Path reused; Builder/StrokeDescription must be new per API contract
    scratchPath.rewind();
    scratchPath.moveTo(x, y);
    return dispatchGesture(
        new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(
                scratchPath, 0, Math.max(1, durationMs)))
            .build(),
        null,
        null
    );
  }

  /** Prefer a Rapido window root for armed BG overlay hunts (includes compact sheets). */
  private AccessibilityNodeInfo findRapidoRootForHunt(boolean overlayScan) {
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows != null) {
        AccessibilityNodeInfo bestOverlay = null;
        AccessibilityNodeInfo bestAny = null;
        for (AccessibilityWindowInfo w : windows) {
          if (w == null) continue;
          try {
            w.getBoundsInScreen(scratchRect2);
            if (isFloatingBubbleBounds(scratchRect2) && !isAcceptRaceHot() && !overlayScan) {
              noteBubbleWindow(scratchRect2);
              continue;
            }
          } catch (Exception ignored) {
          }
          AccessibilityNodeInfo r = w.getRoot();
          if (r == null) continue;
          String p = packageOf(r);
          if (p != null && p.equals(getPackageName())) {
            r.recycle();
            continue;
          }
          if (!allowAsRapidoWindow(p, lastPkg)
              && !(overlayScan && (p == null || isRapidoPackageName(p)))) {
            r.recycle();
            continue;
          }
          if (isLikelyOverlayWindow(w) || isRideCardSizedBounds(scratchRect2) || overlayScan) {
            if (bestOverlay != null) bestOverlay.recycle();
            bestOverlay = r;
            AccessibilityNodeInfo accept = findAcceptLabelInRoot(r);
            if (accept != null) {
              accept.recycle();
              if (bestAny != null) bestAny.recycle();
              return bestOverlay;
            }
          } else if (bestAny == null) {
            bestAny = r;
          } else {
            r.recycle();
          }
        }
        if (bestOverlay != null) {
          if (bestAny != null) bestAny.recycle();
          return bestOverlay;
        }
        if (bestAny != null) return bestAny;
      }
    } catch (Exception ignored) {
    }
    return findRapidoRoot();
  }

  /** Prefer a Rapido window root — never the Settings/Chrome active window. */
  private AccessibilityNodeInfo findRapidoRoot() {
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows != null) {
        AccessibilityNodeInfo bestOverlay = null;
        AccessibilityNodeInfo bestAny = null;
        for (AccessibilityWindowInfo w : windows) {
          if (w == null) continue;
          try {
            w.getBoundsInScreen(scratchRect2);
            if (isFloatingBubbleBounds(scratchRect2)) {
              noteBubbleWindow(scratchRect2);
              continue;
            }
          } catch (Exception ignored) {
          }
          AccessibilityNodeInfo r = w.getRoot();
          if (r == null) continue;
          String p = packageOf(r);
          if (!allowAsRapidoWindow(p, lastPkg)) {
            r.recycle();
            continue;
          }
          if (isLikelyOverlayWindow(w)) {
            if (bestOverlay != null) bestOverlay.recycle();
            bestOverlay = r;
            // Prefer Accept-bearing overlay
            AccessibilityNodeInfo accept = findAcceptLabelInRoot(r);
            if (accept != null) {
              accept.recycle();
              if (bestAny != null) bestAny.recycle();
              return bestOverlay;
            }
          } else if (bestAny == null) {
            bestAny = r;
          } else {
            r.recycle();
          }
        }
        if (bestOverlay != null) {
          if (bestAny != null) bestAny.recycle();
          return bestOverlay;
        }
        return bestAny;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private AccessibilityNodeInfo safeRapidoOrOlaRoot(String hintPkg) {
    if (hintPkg != null && isRapidoPackageName(hintPkg)) {
      AccessibilityNodeInfo r = findRapidoRoot();
      if (r != null) return r;
    }
    if (hintPkg != null && (AutoClickerConfig.isOlaPackage(hintPkg)
        || AutoClickerConfig.isUberPackage(hintPkg))) {
      try {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
          String p = packageOf(active);
          if (p != null && (AutoClickerConfig.isOlaPackage(p)
              || AutoClickerConfig.isUberPackage(p)
              || p.equals(hintPkg))) {
            return active;
          }
          active.recycle();
        }
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private AccessibilityNodeInfo safeRoot() {
    AccessibilityNodeInfo rapido = findRapidoRoot();
    if (rapido != null) return rapido;
    try {
      AccessibilityNodeInfo active = getRootInActiveWindow();
      if (active != null) {
        String p = packageOf(active);
        if (p != null && AutoClickerConfig.isRideAppPackage(p)) {
          return active;
        }
        active.recycle();
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
    if (nodes == null) return;
    for (AccessibilityNodeInfo n : nodes) {
      if (n != null) n.recycle();
    }
  }

  private String nodeText(AccessibilityNodeInfo node) {
    if (node == null) return null;
    CharSequence t = node.getText();
    if (t != null && t.length() > 0) return t.toString();
    CharSequence d = node.getContentDescription();
    if (d != null && d.length() > 0) return d.toString();
    return null;
  }

  private String notifText(AccessibilityEvent event) {
    StringBuilder sb = scratchSb;
    sb.setLength(0);
    Parcelable data = event.getParcelableData();
    if (data instanceof Notification) {
      Notification n = (Notification) data;
      if (n.tickerText != null) sb.append(n.tickerText).append(' ');
      if (n.extras != null) {
        append(sb, n.extras.getCharSequence(Notification.EXTRA_TITLE));
        append(sb, n.extras.getCharSequence(Notification.EXTRA_TEXT));
        append(sb, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
      }
    }
    if (event.getText() != null) {
      for (CharSequence cs : event.getText()) append(sb, cs);
    }
    return sb.toString().toLowerCase(Locale.US);
  }

  private static void append(StringBuilder sb, CharSequence v) {
    if (v != null) sb.append(v).append(' ');
  }

  static boolean isSpamText(String hay) {
    if (hay == null || hay.isEmpty()) return false;
    // Never spam-filter clear ride-offer signals
    if (hay.contains("accept") || hay.contains("स्वीकार") || hay.contains("₹")
        || hay.contains("new order") || hay.contains("new ride") || hay.contains("ride request")
        || hay.contains("pickup")) {
      return false;
    }
    return hay.contains("completed order") || hay.contains("total earning")
        || hay.contains("accepted orders") || hay.contains("login") || hay.contains("otp")
        || hay.contains("password") || hay.contains("wallet") || hay.contains("cashout")
        || hay.contains("payout") || hay.contains("rating") || hay.contains("rate your")
        || hay.contains("update available") || hay.contains("battery")
        || hay.contains("document") || hay.contains("training")
        || hay.contains("earning") || hay.contains("incentive") || hay.contains("bonus")
        || hay.contains("challenge") || hay.contains("go online") || hay.contains("you're online")
        || hay.contains("you are online") || hay.contains("offline") || hay.contains("duty")
        || hay.contains("kyc") || hay.contains("tip received") || hay.contains("payment received")
        || hay.contains("weekly") || hay.contains("leaderboard") || hay.contains("referral")
        || hay.contains("promotion") || hay.contains("offer ends") || hay.contains("recharge");
  }

  /**
   * True for ride-offer notifications — not earnings/status pings.
   * Nuclear: empty/unknown text still arms (overlay often paints before notif text).
   */
  static boolean isRideAlert(String notifText, Notification notification) {
    if (notification != null && findAcceptAction(notification) != null) {
      return true;
    }
    return looksLikeRideOffer(notifText);
  }

  static boolean looksLikeRideOffer(String text) {
    if (text == null || text.trim().isEmpty()) {
      // Nuclear: empty heads-up still arms hunt (overlay often paints before notif text).
      return AutoClickerConfig.isNuclearMode();
    }
    String h = text.toLowerCase(Locale.US).trim();
    if (isSpamText(h)) return false;
    if (h.contains("accept") || h.contains("स्वीकार")) return true;
    if (h.contains("new order") || h.contains("new ride") || h.contains("ride request")) {
      return true;
    }
    if (h.contains("incoming") || h.contains("order request") || h.contains("ride offer")) {
      return true;
    }
    // Ola / Uber heads-up copy
    if (h.contains("trip request") || h.contains("new trip") || h.contains("trip offer")) {
      return true;
    }
    if (h.contains("booking request") || h.contains("new booking") || h.contains("fare estimate")) {
      return true;
    }
    if (h.contains("uber") && (h.contains("trip") || h.contains("ride") || h.contains("request"))) {
      return true;
    }
    if (h.contains("ola") && (h.contains("trip") || h.contains("ride") || h.contains("booking"))) {
      return true;
    }
    if (h.contains("pickup") || h.contains("pick up") || h.contains("drop")) return true;
    if (h.contains("booking") && (h.contains("₹") || h.contains("km") || h.contains("rs"))) {
      return true;
    }
    // Fare or distance alone is a typical Captain ride heads-up
    if ((h.contains("₹") || h.contains("rs.") || h.contains("rs ") || h.contains("inr"))
        && h.matches(".*\\d.*")) {
      return true;
    }
    if (h.contains("km") && h.matches(".*\\d.*")) return true;
    return false;
  }


  /**
   * Live ride card near Accept (₹ / km / pickup) — used on Home when not NLS-armed.
   * Missed-order UI is rejected separately.
   */
  private boolean acceptLooksLikeLiveOffer(AccessibilityNodeInfo accept) {
    if (accept == null) return false;
    try {
      accept.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty() || isExtremeTopChromeAccept(scratchRect)) return false;
      if (isBubbleLikeClickTarget(scratchRect)) return false;
      if (isOversizedAcceptBounds(scratchRect)) return false;
    } catch (Exception e) {
      return false;
    }
    String near = collectPriceNearAccept(accept);
    if (near == null) near = "";
    String h = near.toLowerCase(Locale.US);
    if (h.contains("₹") || h.contains("rs") || h.contains("inr")) return true;
    if (h.contains("km") || h.contains("pickup") || h.contains("drop")) return true;
    if (parseRapidoPrice(near) > 0) return true;
    // Nuclear + Captain FG or floating overlay: mid-screen Accept CTA may arm
    // even when fare text is slow to paint. Idle poll stays race-only.
    if (AutoClickerConfig.isNuclearMode()
        && (rapidoForeground || isNodeOnRideOverlay(accept))) {
      ensureScreenMetrics();
      int cy = scratchRect.centerY();
      int bw = scratchRect.width();
      int bh = scratchRect.height();
      if (screenH > 0 && cy > screenH * 0.12f && cy < screenH * 0.92f
          && bw >= dp(72) && bh >= dp(28) && bh <= dp(120)) {
        return true;
      }
    }
    return false;
  }

  /** Clear UI-strike evidence used by VERIFY / soft-confirm (no JS). */
  private void clearUiStrikeEvidence() {
    uiStrikeValid = false;
    acceptSeenDuringVerify = false;
    softConfirmUntilMs = 0;
    handler.removeCallbacks(softConfirmRunnable);
  }

  /** Mark that strike-0 hit a live Accept — VERIFY may latch success. */
  private void markUiStrike() {
    uiStrikeValid = true;
  }

  /**
   * Immediate ~40ms backup press at Accept center — all OEMs.
   * Realme UI / ColorOS / HyperOS often swallow the 1ms strike-0 alone.
   */
  private void scheduleBackupAcceptGesture(final int cx, final int cy) {
    if (cx <= 0 || cy <= 0) return;
    handler.postAtFrontOfQueue(() -> {
      try {
        if (isRapidoInteractionBlocked()) return;
        if (!(racePhase == RacePhase.STRIKING || verifyingAccept || olaBurstLock)) return;
        gestureTap(cx, cy, RAPIDO_GESTURE_MS_BACKUP);
      } catch (Throwable ignored) {
      }
    });
  }

  /**
   * Shared Accept fire for Ola / Uber (and any non-Rapido race).
   * ACTION_CLICK + 1ms gesture + 40ms backup + short micro-burst. Owns {@code node}.
   */
  private boolean fireSharedAcceptStrike(
      AccessibilityNodeInfo node, String pkg, String tag, long findAt) {
    if (node == null) return false;
    try {
      node.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty()
          || isExtremeTopChromeAccept(scratchRect)
          || isOversizedAcceptBounds(scratchRect)) {
        try { node.recycle(); } catch (Exception ignored) {}
        return false;
      }
      final int cx = scratchRect.centerX();
      final int cy = scratchRect.centerY();
      if (cx <= 0 || cy <= 0) {
        try { node.recycle(); } catch (Exception ignored) {}
        return false;
      }

      scratchRect2.set(scratchRect);
      markOverlayLive(scratchRect2, cx, cy);
      verifyCx = cx;
      verifyCy = cy;
      rapidoBurstX = cx;
      rapidoBurstY = cy;
      AutoClickerConfig.cacheTapPoint(pkg, cx, cy);

      boolean clicked = false;
      try {
        if (node.isClickable()) {
          clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
      } catch (Exception ignored) {
      }
      // Allow canGestureAt via olaBurstLock / race — set lock before gesture
      olaBurstLock = true;
      boolean gestOk = gestureTap(cx, cy, RAPIDO_GESTURE_MS_STRIKE0);
      if (!clicked) {
        try {
          if (node.isClickable()) {
            clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
          }
        } catch (Exception ignored) {
        }
      }
      scheduleBackupAcceptGesture(cx, cy);
      Log.i(TAG, "STRIKE0_SHARED pkg=" + pkg + " tag=" + tag
          + " findMs=" + Math.max(0, SystemClock.uptimeMillis() - findAt)
          + " clickOk=" + clicked + " gestOk=" + gestOk
          + " @" + cx + "," + cy);

      // Short micro-burst at same center (not legacy 16-tap Ola spray)
      final AccessibilityNodeInfo burstNode = AccessibilityNodeInfo.obtain(node);
      for (int i = 1; i <= SHARED_MICRO_EXTRA; i++) {
        final int step = i;
        handler.postDelayed(() -> {
          try {
            if (!AutoClickerConfig.isEnabled() || isRapidoInteractionBlocked()) return;
            if (burstNode != null && burstNode.refresh() && burstNode.isClickable()) {
              burstNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            gestureTap(cx, cy, RAPIDO_GESTURE_MS_BACKUP);
          } catch (Throwable ignored) {
          } finally {
            if (step >= SHARED_MICRO_EXTRA) {
              try { if (burstNode != null) burstNode.recycle(); } catch (Exception ignored) {}
              olaBurstLock = false;
            }
          }
        }, SHARED_MICRO_INTERVAL_MS * i);
      }
      try { node.recycle(); } catch (Exception ignored) {}
      // Safety unlock if micro-burst posts were skipped
      handler.postDelayed(() -> { olaBurstLock = false; }, SHARED_MICRO_INTERVAL_MS * (SHARED_MICRO_EXTRA + 2));
      return clicked || gestOk;
    } catch (Throwable t) {
      Log.e(TAG, "SHARED_STRIKE_SAFE", t);
      try { node.recycle(); } catch (Exception ignored) {}
      olaBurstLock = false;
      return false;
    }
  }

  private void logBlocked(String where, String why) {
    if (!RACE_DEBUG_LOG) return;
    Log.i(TAG, "BLOCKED " + where + " reason=" + why
        + " phase=" + racePhase
        + " fg=" + rapidoForeground
        + " armed=" + isRaceArmed());
  }

  private void logFoundAccept(AccessibilityNodeInfo node, String where, String pkg) {
    if (!RACE_DEBUG_LOG || node == null) return;
    try {
      node.getBoundsInScreen(scratchRect);
      Log.i(TAG, "FOUND_ACCEPT " + where
          + " @" + scratchRect.centerX() + "," + scratchRect.centerY()
          + " pkg=" + pkg);
    } catch (Exception ignored) {
    }
  }
}
