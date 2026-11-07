package dev.gpayready

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * "Display over other apps" (overlay) helper.
 *
 * Google Pay's app-access-risk check flags apps that **draw over the screen** (its `KNOWN_OVERLAYS`
 * verdict), and an active overlay also makes browsers suppress website popups (FLAG_WINDOW_IS_OBSCURED).
 *
 * An app **cannot** turn another app's overlay permission off without root / Shizuku — that needs
 * `MANAGE_APP_OPS_MODES`, which (unlike `WRITE_SECURE_SETTINGS`) is NOT adb-grantable. So this just
 * **finds** the likely culprits and gives a one-tap jump to each one's own overlay toggle.
 */
object OverlayApps {

    /** User-installed, launchable apps that declare "Display over other apps" — the likely overlay drawers. */
    fun suspects(ctx: Context): List<GpayFix.AppItem> = runCatching {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .mapNotNull { pkg ->
                runCatching {
                    val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                    val ai = pi.applicationInfo ?: return@runCatching null
                    val declares = pi.requestedPermissions?.contains(Manifest.permission.SYSTEM_ALERT_WINDOW) == true
                    val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (declares && !system) GpayFix.AppItem(pkg, pm.getApplicationLabel(ai).toString()) else null
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    /** The "Display over other apps" toggle screen for one app (one tap to turn it off). */
    fun settingsIntent(pkg: String): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
