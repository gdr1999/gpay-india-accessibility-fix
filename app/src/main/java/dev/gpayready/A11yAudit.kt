package dev.gpayready

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Classifies accessibility services by **capability**, so you can turn off only the ones Google Pay
 * actually objects to and keep the harmless ones on permanently.
 *
 * GPay's flag is `ACCESSIBILITY_SERVICE_WITH_POTENTIAL_AUTOMATION_USE` — it doesn't block *all*
 * accessibility apps, only ones that can **control or capture** the screen. We read each service's
 * capability bitmask (`AccessibilityServiceInfo.getCapabilities()`):
 *  - CONTROL  = can perform gestures / filter key events / take a screenshot / fingerprint gestures
 *               → these are what GPay treats as automation and blocks.
 *  - READ     = can only retrieve window content → may be treated as "capturing"; borderline.
 *  - MINIMAL  = neither → safe to leave on.
 *
 * Writing a subset of `enabled_accessibility_services` needs WRITE_SECURE_SETTINGS (the app already
 * has it), so turning one off is headless and sticks until you (or the app) turn it back on.
 */
object A11yAudit {

    // AccessibilityServiceInfo capability bits
    private const val RETRIEVE_CONTENT = 0x0001
    private const val FILTER_KEYS = 0x0008
    private const val PERFORM_GESTURES = 0x0020
    private const val FINGERPRINT = 0x0040
    private const val TAKE_SCREENSHOT = 0x0080
    private const val CONTROL_MASK = FILTER_KEYS or PERFORM_GESTURES or FINGERPRINT or TAKE_SCREENSHOT

    private const val KEY = "enabled_accessibility_services"
    private const val ENABLED = "accessibility_enabled"

    enum class Tier { CONTROL, READ, MINIMAL }

    data class Svc(val component: String, val label: String, val pkg: String, val caps: Int, val enabled: Boolean) {
        val tier: Tier get() = when {
            caps and CONTROL_MASK != 0 -> Tier.CONTROL
            caps and RETRIEVE_CONTENT != 0 -> Tier.READ
            else -> Tier.MINIMAL
        }
        /** Short human list of what it can do, for the UI. */
        fun powers(): String = buildList {
            if (caps and PERFORM_GESTURES != 0) add("perform gestures")
            if (caps and TAKE_SCREENSHOT != 0) add("take screenshots")
            if (caps and FILTER_KEYS != 0) add("filter key events")
            if (caps and FINGERPRINT != 0) add("fingerprint gestures")
            if (caps and RETRIEVE_CONTENT != 0) add("read screen")
            if (isEmpty()) add("events only")
        }.joinToString(" · ")
    }

    private fun enabledSet(ctx: Context): List<String> =
        Settings.Secure.getString(ctx.contentResolver, KEY).orEmpty()
            .split(":").filter { it.isNotBlank() }

    /** Every installed accessibility service (enabled + available), classified, enabled ones first. */
    fun services(ctx: Context): List<Svc> = runCatching {
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return emptyList()
        val on = enabledSet(ctx).map { it.lowercase() }.toSet()
        am.installedAccessibilityServiceList.mapNotNull { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
            val comp = ComponentName(si.packageName, si.name).flattenToString()
            val label = runCatching { info.resolveInfo.loadLabel(ctx.packageManager).toString() }
                .getOrDefault(si.packageName)
            Svc(comp, label, si.packageName, info.capabilities, comp.lowercase() in on)
        }.sortedWith(compareByDescending<Svc> { it.enabled }.thenBy { it.tier.ordinal }.thenBy { it.label.lowercase() })
    }.getOrDefault(emptyList())

    /** Turn one service on/off by rewriting the enabled-services subset (needs WRITE_SECURE_SETTINGS). */
    fun setEnabled(ctx: Context, component: String, on: Boolean): Boolean = runCatching {
        val set = enabledSet(ctx).toMutableList()
        val present = set.any { it.equals(component, true) }
        if (on && !present) set.add(component)
        if (!on) set.removeAll { it.equals(component, true) }
        Settings.Secure.putString(ctx.contentResolver, KEY, set.joinToString(":"))
        Settings.Secure.putInt(ctx.contentResolver, ENABLED, if (set.isEmpty()) 0 else 1)
        true
    }.getOrDefault(false)

    /** Turn OFF every currently-enabled CONTROL-tier service; returns how many were turned off. */
    fun disableControlling(ctx: Context): Int {
        val control = services(ctx).filter { it.enabled && it.tier == Tier.CONTROL }
        control.forEach { setEnabled(ctx, it.component, false) }
        return control.size
    }

    fun controllingCount(ctx: Context): Int = services(ctx).count { it.enabled && it.tier == Tier.CONTROL }
}
