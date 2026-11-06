package dev.gpayready

import android.app.Activity
import android.os.Bundle

/**
 * Invisible trampoline that runs the fix and opens the target app. Target of the Quick Settings
 * tiles, the MacroDroid / launcher "shortcut", and direct launch. Because it's an Activity, its
 * launch of the target app is allowed (not a background-activity-launch).
 *
 * A per-app tile / home icon passes [EXTRA_PKG] so the fix targets (and opens) that one app;
 * without it, the fix runs for the primary selected app.
 */
class DispatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra(EXTRA_PKG)
        if (!pkg.isNullOrBlank()) GpayFix.runFixFor(this, pkg) else GpayFix.runFix(this)
        finish()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}
