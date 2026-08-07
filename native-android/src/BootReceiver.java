package com.rapido.tap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives BOOT_COMPLETED. Accessibility must be enabled manually by the user once;
 * this receiver is a hook for future auto-resume logic.
 */
public class BootReceiver extends BroadcastReceiver {

  private static final String TAG = "BootReceiver";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || intent.getAction() == null) return;
    if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
      AutoClickerConfig.init(context);
      AccessibilityDeathAlert.ensureMonitoring(context);
      AccessibilityDeathAlert.checkAcceptOnWithoutA11yAsync(context, "boot");
      Log.w(TAG, "BOOT_COMPLETED enabled=" + AutoClickerConfig.isEnabled()
          + " nuclear=" + AutoClickerConfig.isNuclearMode());
    }
  }
}
