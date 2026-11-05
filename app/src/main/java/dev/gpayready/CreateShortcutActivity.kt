package dev.gpayready

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Answers the legacy CREATE_SHORTCUT picker — the hook MacroDroid ("Launch Shortcut"), Tasker and
 * classic launchers use. Returns a shortcut that launches [DispatchActivity], so any of those apps
 * can trigger "GPay Ready" as an action.
 */
class CreateShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, DispatchActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        val result = Intent().apply {
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)
            putExtra(Intent.EXTRA_SHORTCUT_NAME, "GPay Ready")
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
