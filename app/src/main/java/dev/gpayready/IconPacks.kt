package dev.gpayready

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import org.xmlpull.v1.XmlPullParser

/**
 * Minimal icon-pack reader: lists installed icon packs and resolves one app's icon from a pack.
 *
 * Icon packs are ordinary apps that respond to the de-facto launcher-theme intents and ship an
 * `appfilter.xml` mapping a launcher component to a drawable name:
 *   <item component="ComponentInfo{pkg/activity}" drawable="my_icon"/>
 * We read that mapping from the pack's own resources (XML resource first, then assets) and load the
 * named drawable as a bitmap. No root, no special permission — just the pack's public resources.
 */
object IconPacks {

    data class Pack(val pkg: String, val label: String)

    private val THEME_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.gau.go.launcherex.theme",
        "com.novalauncher.THEME",
        "com.anddoes.launcher.THEME",
        "app.lawnchair.icons.THEMED_ICON",
    )

    /** Installed icon packs (apps responding to a launcher-theme intent), A→Z. */
    fun installed(ctx: Context): List<Pack> = runCatching {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, Pack>()
        for (action in THEME_ACTIONS) {
            pm.queryIntentActivities(Intent(action), 0).forEach {
                val p = it.activityInfo.packageName
                if (p !in seen) seen[p] = Pack(p, it.loadLabel(pm).toString())
            }
        }
        seen.values.sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    /** Resolve [componentPkg]'s launcher icon from the pack [packPkg], or null if it isn't themed. */
    fun iconFor(ctx: Context, packPkg: String, componentPkg: String): Bitmap? = runCatching {
        val pm = ctx.packageManager
        val component = pm.getLaunchIntentForPackage(componentPkg)?.component ?: return null
        val flat = "ComponentInfo{${component.packageName}/${component.className}}"
        val res = pm.getResourcesForApplication(packPkg)

        var drawableName: String? = null
        // appfilter can live as an XML resource or as an asset.
        val xmlId = res.getIdentifier("appfilter", "xml", packPkg)
        val parser: XmlPullParser? = when {
            xmlId != 0 -> res.getXml(xmlId)
            else -> runCatching {
                android.util.Xml.newPullParser().apply {
                    setInput(res.assets.open("appfilter.xml"), "UTF-8")
                }
            }.getOrNull()
        }
        if (parser != null) {
            var ev = parser.eventType
            loop@ while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                    val comp = parser.getAttributeValue(null, "component")
                    val dr = parser.getAttributeValue(null, "drawable")
                    if (comp == flat && !dr.isNullOrBlank()) { drawableName = dr; break@loop }
                }
                ev = parser.next()
            }
        }
        val name = drawableName ?: return null
        val drawId = res.getIdentifier(name, "drawable", packPkg)
        if (drawId == 0) return null
        val d = res.getDrawable(drawId, null) ?: return null
        val px = 192
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        bmp
    }.getOrNull()
}
