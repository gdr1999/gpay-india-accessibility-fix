package dev.gpayready

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Exported receiver so Tasker / `am broadcast` can trigger the fix:
 *   adb shell am broadcast -n dev.gpayready/.RunReceiver -a dev.gpayready.RUN
 * The headless parts (accessibility off + kills) always run; opening Google Pay from a background
 * broadcast may be limited by the OS, so the MacroDroid "Launch Shortcut" path is preferred for the
 * full open-and-pay flow.
 */
class RunReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_RESTORE -> GpayFix.restoreAccessibility(context)
            else -> GpayFix.runFix(context)
        }
    }

    companion object {
        const val ACTION_RUN = "dev.gpayready.RUN"
        const val ACTION_RESTORE = "dev.gpayready.RESTORE"
    }
}
