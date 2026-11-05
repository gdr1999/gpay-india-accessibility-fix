package dev.gpayready

import android.app.Activity
import android.os.Bundle

/**
 * Invisible trampoline that runs the fix and opens Google Pay. Target of the Quick Settings tile, the
 * MacroDroid / launcher "shortcut", and direct launch. Because it's an Activity, its launch of Google
 * Pay is allowed (not a background-activity-launch).
 */
class DispatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GpayFix.runFix(this)
        finish()
    }
}
