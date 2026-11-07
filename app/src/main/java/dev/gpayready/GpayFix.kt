package dev.gpayready

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

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
    private const val TAG = "GPAYREADY"

    private const val PREFS = "gpayready"
    private const val KEY_SAVED = "saved_a11y"
    private const val KEY_AUTO = "auto_enabled"
    private const val KEY_TARGET = "target_pkg"      // legacy single (migrated)
    private const val KEY_TARGETS = "target_pkgs"    // newline-joined, ordered; first = primary

    data class AppItem(val pkg: String, val label: String)

    /** The apps the fix targets + the automation watches (default [Google Pay]; user can pick several). */
    fun targetPackages(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_TARGETS, null)
        if (!raw.isNullOrBlank()) return raw.split("\n").filter { it.isNotBlank() }
        // migrate the old single-app setting
        return listOf(prefs(ctx).getString(KEY_TARGET, GPAY).orEmpty().ifBlank { GPAY })
    }

    fun setTargetPackages(ctx: Context, pkgs: List<String>) {
        val clean = pkgs.distinct().filter { it.isNotBlank() }.ifEmpty { listOf(GPAY) }
        prefs(ctx).edit().putString(KEY_TARGETS, clean.joinToString("\n")).apply()
    }

    /** Remove one app from the selection (from its card's ×), freeing its QS-tile slot and clearing
     *  its per-app restore override. If it was the last one, the selection falls back to Google Pay. */
    fun removeTarget(ctx: Context, pkg: String) {
        setTargetPackages(ctx, targetPackages(ctx).filterNot { it == pkg })
        for (i in 0 until TILE_SLOTS) if (tileSlotApp(ctx, i) == pkg) { setTileSlotApp(ctx, i, null); setTileIcon(ctx, i, "") }
        setRestoreOnClose(ctx, pkg, true)
    }

    /** The app the one-tap fix opens (the first selected). */
    fun primaryTarget(ctx: Context): String = targetPackages(ctx).firstOrNull() ?: GPAY
    fun isTarget(ctx: Context, pkg: String?): Boolean = pkg != null && pkg in targetPackages(ctx)

    private fun labelOf(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun primaryLabel(ctx: Context): String = labelOf(ctx, primaryTarget(ctx))

    /** "Google Pay" for one, or "Google Pay +2" for several. */
    fun targetsLabel(ctx: Context): String {
        val pkgs = targetPackages(ctx)
        val first = labelOf(ctx, pkgs.firstOrNull() ?: GPAY)
        return if (pkgs.size <= 1) first else "$first +${pkgs.size - 1}"
    }

    /** Installed apps that have a launcher entry, for the picker (A→Z). */
    fun launchableApps(ctx: Context): List<AppItem> = runCatching {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0)
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    fun labelFor(ctx: Context, pkg: String): String = labelOf(ctx, pkg)

    // --- per-app "restore accessibility when this app closes" (default ON) ---
    // Apps in this set do NOT restore when you leave them — for affiliated apps (food delivery,
    // shopping) that DEEP-LINK into Google Pay to pay: leaving them means heading to GPay, so
    // accessibility must stay off, not snap back on.
    private const val KEY_NO_RESTORE = "no_restore_pkgs"
    fun noRestoreSet(ctx: Context): Set<String> =
        prefs(ctx).getString(KEY_NO_RESTORE, "").orEmpty().split("\n").filter { it.isNotBlank() }.toSet()
    fun restoreOnClose(ctx: Context, pkg: String?): Boolean = pkg != null && pkg !in noRestoreSet(ctx)
    fun setRestoreOnClose(ctx: Context, pkg: String, on: Boolean) {
        val s = noRestoreSet(ctx).toMutableSet()
        if (on) s.remove(pkg) else s.add(pkg)
        prefs(ctx).edit().putString(KEY_NO_RESTORE, s.joinToString("\n")).apply()
    }

    // --- per-app Quick Settings tile slots (a fixed pool of manifest-declared tiles) ---
    const val TILE_SLOTS = 5
    fun tileSlotApp(ctx: Context, slot: Int): String? = prefs(ctx).getString("qs_slot_$slot", null)
    fun setTileSlotApp(ctx: Context, slot: Int, pkg: String?) =
        prefs(ctx).edit().putString("qs_slot_$slot", pkg).apply()
    /** Return the slot already assigned to [pkg], else the first free slot, else slot 0. */
    fun assignTileSlot(ctx: Context, pkg: String): Int {
        for (i in 0 until TILE_SLOTS) if (tileSlotApp(ctx, i) == pkg) return i
        for (i in 0 until TILE_SLOTS) if (tileSlotApp(ctx, i).isNullOrBlank()) { setTileSlotApp(ctx, i, pkg); return i }
        setTileSlotApp(ctx, 0, pkg); return 0
    }

    /** Per-tile icon spec: "" = the target app's own logo; "d:<drawable>" = a built-in white glyph that
     *  tints like the system Quick Settings icons; "e:<emoji>" = a custom emoji / Unicode symbol. */
    fun tileIcon(ctx: Context, slot: Int): String = prefs(ctx).getString("qs_icon_$slot", "").orEmpty()
    fun setTileIcon(ctx: Context, slot: Int, spec: String) =
        prefs(ctx).edit().putString("qs_icon_$slot", spec.trim()).apply()

    // These secure-setting keys are stable string constants.
    private const val ENABLED_A11Y = "enabled_accessibility_services"
    private const val A11Y_ENABLED = "accessibility_enabled"

    // Developer-options / debugging keys (Settings.Global) — writable with WRITE_SECURE_SETTINGS.
    private const val DEV = "development_settings_enabled"
    private const val ADB = "adb_enabled"
    private const val ADB_WIFI = "adb_wifi_enabled"
    private const val KEY_SAVED_DEV = "saved_dev"
    private const val KEY_SAVED_ADB = "saved_adb"
    private const val KEY_SAVED_ADB_WIFI = "saved_adb_wifi"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isWriteGranted(ctx: Context): Boolean =
        ctx.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun accessibilityOn(ctx: Context): Boolean =
        !Settings.Secure.getString(ctx.contentResolver, ENABLED_A11Y).isNullOrBlank()

    fun devOptionsOn(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, DEV, 0) == 1 }.getOrDefault(false)
    fun usbDebugOn(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, ADB, 0) == 1 }.getOrDefault(false)

    /** The three things banking apps dislike; [ready] = all three OFF. */
    data class PayState(val a11yOn: Boolean, val devOn: Boolean, val usbOn: Boolean) {
        val ready: Boolean get() = !a11yOn && !devOn && !usbOn
    }
    fun payState(ctx: Context) = PayState(accessibilityOn(ctx), devOptionsOn(ctx), usbDebugOn(ctx))

    /** App-Info screen for a package — used for a REAL "Force stop" (flushes the cached block that the
     *  no-root [killBackground] cannot), e.g. Play Services when Google Pay is stuck in a blocked state. */
    fun appInfoIntent(pkg: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Save the currently-enabled accessibility services, then turn them all OFF. */
    fun disableAccessibility(ctx: Context): Boolean = runCatching {
        val cur = Settings.Secure.getString(ctx.contentResolver, ENABLED_A11Y).orEmpty()
        if (cur.isNotBlank()) prefs(ctx).edit().putString(KEY_SAVED, cur).apply()
        Settings.Secure.putString(ctx.contentResolver, ENABLED_A11Y, "")
        Settings.Secure.putInt(ctx.contentResolver, A11Y_ENABLED, 0)
        Log.i(TAG, "accessibility OFF @ ${System.currentTimeMillis()}")
        true
    }.getOrDefault(false)

    /** Snapshot the currently-enabled services whenever they're ON, so restore always has a fresh,
     *  correct set — even if accessibility happened to be off the last time the fix ran. */
    fun rememberIfOn(ctx: Context) {
        val cur = Settings.Secure.getString(ctx.contentResolver, ENABLED_A11Y).orEmpty()
        if (cur.isNotBlank()) prefs(ctx).edit().putString(KEY_SAVED, cur).apply()
    }

    fun savedCount(ctx: Context): Int =
        prefs(ctx).getString(KEY_SAVED, "").orEmpty().split(":").count { it.isNotBlank() }

    /** Restore the accessibility services saved by [disableAccessibility] / [rememberIfOn].
     *  Returns how many services were restored (0 = nothing was saved to restore). */
    fun restoreAccessibility(ctx: Context): Int {
        val saved = prefs(ctx).getString(KEY_SAVED, "").orEmpty()
        if (saved.isBlank()) return 0
        return runCatching {
            Settings.Secure.putString(ctx.contentResolver, ENABLED_A11Y, saved)
            Settings.Secure.putInt(ctx.contentResolver, A11Y_ENABLED, 1)
            val n = saved.split(":").count { it.isNotBlank() }
            Log.i(TAG, "accessibility RESTORED ($n) @ ${System.currentTimeMillis()}")
            n
        }.getOrDefault(0)
    }

    /** Turn OFF Developer Options + USB debugging + Wireless debugging, remembering their prior state so
     *  [restoreDevOptions] can put back exactly what was on. Banking apps read these as a risk signal. */
    fun disableDevOptions(ctx: Context): Boolean = runCatching {
        val cr = ctx.contentResolver
        fun g(k: String) = runCatching { Settings.Global.getInt(cr, k, 0) }.getOrDefault(0)
        prefs(ctx).edit()
            .putInt(KEY_SAVED_DEV, g(DEV)).putInt(KEY_SAVED_ADB, g(ADB)).putInt(KEY_SAVED_ADB_WIFI, g(ADB_WIFI))
            .apply()
        runCatching { Settings.Global.putInt(cr, ADB, 0) }
        runCatching { Settings.Global.putInt(cr, ADB_WIFI, 0) }
        runCatching { Settings.Global.putInt(cr, DEV, 0) }
        true
    }.getOrDefault(false)

    /** Put Developer Options / debugging back to whatever was on before [disableDevOptions]. */
    fun restoreDevOptions(ctx: Context) = runCatching {
        val cr = ctx.contentResolver
        val p = prefs(ctx)
        if (p.getInt(KEY_SAVED_DEV, 0) == 1) runCatching { Settings.Global.putInt(cr, DEV, 1) }
        if (p.getInt(KEY_SAVED_ADB, 0) == 1) runCatching { Settings.Global.putInt(cr, ADB, 1) }
        if (p.getInt(KEY_SAVED_ADB_WIFI, 0) == 1) runCatching { Settings.Global.putInt(cr, ADB_WIFI, 1) }
    }

    /** The full "get ready to pay" state: accessibility OFF + Developer Options / debugging OFF. */
    fun disableForPay(ctx: Context) {
        disableAccessibility(ctx)
        disableDevOptions(ctx)
    }

    /** Restore everything the fix turned off (Developer Options + accessibility). Returns a11y count. */
    fun restoreAll(ctx: Context): Int {
        restoreDevOptions(ctx)
        val n = restoreAccessibility(ctx)
        if (debugMode(ctx)) runCatching { DebugStatus.post(ctx) }
        return n
    }

    /** Restore AFTER using a payment app, safely: kill the target apps' + Play Services' cached
     *  processes FIRST so their LIVE AccessibilityStateChange listeners are gone before accessibility
     *  is turned back on. Otherwise Google Pay (which watches that event while its process lingers in
     *  the background) re-latches the block — and the NEXT time you open it, you're already flagged.
     *  This is the fix for "accessibility was off but GPay still blocked me". */
    fun restoreAfterUse(ctx: Context): Int {
        targetPackages(ctx).forEach { killBackground(ctx, it) }
        killBackground(ctx, GMS)
        Log.i(TAG, "killed targets before restore @ ${System.currentTimeMillis()}")
        return restoreAll(ctx)
    }

    /** Kill an app's cached/background process (headless; needs only KILL_BACKGROUND_PROCESSES). */
    fun killBackground(ctx: Context, pkg: String) = runCatching {
        ctx.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(pkg)
    }

    fun openApp(ctx: Context, pkg: String) = runCatching {
        ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { ctx.startActivity(it) }
    }

    fun openTarget(ctx: Context) = openApp(ctx, primaryTarget(ctx))

    /** The full fix for ONE app: accessibility OFF + Developer Options / USB-debugging OFF → stop that
     *  app + Play Services → open it. Used by the per-app home icon and Quick Settings tile. */
    fun runFixFor(ctx: Context, pkg: String, open: Boolean = true) {
        disableForPay(ctx)
        killBackground(ctx, pkg)
        killBackground(ctx, GMS)
        if (debugMode(ctx)) runCatching { DebugStatus.post(ctx) }
        if (open) openApp(ctx, pkg)
    }

    /** The full fix for the primary app (the main action / generic tile). */
    fun runFix(ctx: Context, open: Boolean = true) = runFixFor(ctx, primaryTarget(ctx), open)

    // --- the Usage-Access automation flag ---
    fun autoEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO, false)
    fun setAutoEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply()

    // --- debug / "pay-ready" status notification flag (on by default) ---
    fun debugMode(ctx: Context): Boolean = prefs(ctx).getBoolean("debug_mode", true)
    fun setDebugMode(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("debug_mode", on).apply()
        if (on) DebugStatus.post(ctx) else DebugStatus.clear(ctx)
    }

    // --- first-run onboarding flag ---
    fun onboarded(ctx: Context): Boolean = prefs(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("onboarded", v).apply()

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
