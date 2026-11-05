package dev.gpayready

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the Usage-Access watcher after a reboot if the automation is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && GpayFix.autoEnabled(context)) {
            runCatching { GpayWatchService.start(context) }
        }
    }
}
