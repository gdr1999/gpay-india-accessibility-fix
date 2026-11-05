package dev.gpayready

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The whole fix, in one object. Google Pay India started blocking payments when any accessibility
 * service that looks like an automation tool (MacroDroid, launchers, etc.) is enabled. Those are
 * legitimate apps, so the fix is simply to turn accessibility OFF while you pay and turn it back ON
 * after — exactly the state Google Pay's check wants — then nudge the cached state by killing the
 * Google Pay / Play Services background processes so they re-check on next open.
 *
 * Everything here is a documented, no-root mechanism:
 *  - Accessibility on/off: writing the `enabled_accessibility_services` secure setting, which needs
 *    WRITE_SECURE_SETTINGS. That permission is granted ONCE over adb (see the README) — no root.
 *  - Killing background processes: ActivityManager.killBackgroundProcesses, which only needs the
 *    normal KILL_BACKGROUND_PROCESSES permission (auto-granted). It is NOT a full `am force-stop`
 *    (it won't stop a running foreground service) but it drops the cached process.
 *
 * No Shizuku, no root, no wireless/USB debugging left on (banking apps read that state).
 */
object GpayFix {
    const val GPAY = "com.google.android.apps.nbu.paisa.user" // Google Pay India (Tez)
    const val GMS = "com.google.android.gms"                   // Google Play services

    private const val PREFS = "gpayready"
    private const val KEY_SAVED = "saved_a11y"
    private const val KEY_AUTO = "auto_enabled"

    // These secure-setting keys are stable string constants.
    private const val ENABLED_A11Y = "enabled_accessibility_services"
    private const val A11Y_ENABLED = "accessibility_enabled"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isWriteGranted(ctx: Context): Boolean =
        ctx.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun accessibilityOn(ctx: Context): Boolean =
        !Settings.Secure.getString(ctx.contentResolver, ENABLED_A11Y).isNullOrBlank()

    /** Save the currently-enabled accessibility services, then turn them all OFF. */
    fun disableAccessibility(ctx: Context): Boolean = runCatching {
        val cur = Settings.Secure.getString(ctx.contentResolver, ENABLED_A11Y).orEmpty()
        if (cur.isNotBlank()) prefs(ctx).edit().putString(KEY_SAVED, cur).apply()
        Settings.Secure.putString(ctx.contentResolver, ENABLED_A11Y, "")
        Settings.Secure.putInt(ctx.contentResolver, A11Y_ENABLED, 0)
        true
    }.getOrDefault(false)

    /** Restore the accessibility services saved by [disableAccessibility]. */
    fun restoreAccessibility(ctx: Context): Boolean = runCatching {
        val saved = prefs(ctx).getString(KEY_SAVED, "").orEmpty()
        if (saved.isNotBlank()) {
            Settings.Secure.putString(ctx.contentResolver, ENABLED_A11Y, saved)
            Settings.Secure.putInt(ctx.contentResolver, A11Y_ENABLED, 1)
        }
        true
    }.getOrDefault(false)

    /** Kill an app's cached/background process (headless; needs only KILL_BACKGROUND_PROCESSES). */
    fun killBackground(ctx: Context, pkg: String) = runCatching {
        ctx.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(pkg)
    }

    fun openGpay(ctx: Context) = runCatching {
        ctx.packageManager.getLaunchIntentForPackage(GPAY)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { ctx.startActivity(it) }
    }

    /** The full fix: accessibility OFF → stop Google Pay → stop Play Services → open Google Pay. */
    fun runFix(ctx: Context, open: Boolean = true) {
        disableAccessibility(ctx)
        killBackground(ctx, GPAY)
        killBackground(ctx, GMS)
        if (open) openGpay(ctx)
    }

    // --- the Usage-Access automation flag ---
    fun autoEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO, false)
    fun setAutoEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply()

    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), ctx.packageName,
        )
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun usageAccessSettings(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
