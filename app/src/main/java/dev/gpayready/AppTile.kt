package dev.gpayready

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService

/**
 * A fixed POOL of per-app Quick Settings tiles. Android creates QS tiles statically from the
 * manifest (there is no runtime-dynamic tile API), so to give several chosen apps their OWN tile we
 * declare a handful of concrete [TileService]s up front and bind each to one app via a saved slot.
 *
 * Each tile:
 *  - shows the target app's own logo as its icon by default (e.g. the Google Pay logo), or a
 *    user-chosen emoji / Unicode glyph instead (see [TileIcons]); QS tints tile icons to a single
 *    theme colour, so a logo/emoji shows as a tinted silhouette — still a distinct, recognisable shape.
 *  - on tap, collapses the shade and runs the fix for ITS app via [DispatchActivity].
 */
abstract class SlotTileService(private val slot: Int) : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val t = qsTile ?: return
        val pkg = GpayFix.tileSlotApp(this, slot)
        t.label = if (pkg.isNullOrBlank()) "GPay Ready" else GpayFix.labelFor(this, pkg)
        t.icon = TileIcons.forSlot(this, slot)
        t.contentDescription = t.label
        runCatching { t.updateTile() }
    }

    override fun onClick() {
        super.onClick()
        val pkg = GpayFix.tileSlotApp(this, slot)
        val i = Intent(this, DispatchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!pkg.isNullOrBlank()) i.putExtra(DispatchActivity.EXTRA_PKG, pkg)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 100 + slot, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }

    companion object {
        /** The concrete tile classes, one per slot — kept in step with the manifest declarations. */
        val CLASSES: List<Class<out SlotTileService>> =
            listOf(AppTile0::class.java, AppTile1::class.java, AppTile2::class.java,
                AppTile3::class.java, AppTile4::class.java)

        fun componentFor(ctx: Context, slot: Int): ComponentName =
            ComponentName(ctx, CLASSES[slot])
    }
}

class AppTile0 : SlotTileService(0)
class AppTile1 : SlotTileService(1)
class AppTile2 : SlotTileService(2)
class AppTile3 : SlotTileService(3)
class AppTile4 : SlotTileService(4)

/** Builds QS-tile / shortcut icons: an emoji glyph drawn into a bitmap, or an app's own launcher icon. */
object TileIcons {

    /** The icon for a tile slot, honouring the user's per-slot choice:
     *  "d:<drawable>" → a built-in monochrome glyph that QS tints white like a system tile;
     *  "e:<emoji>"    → a custom emoji / Unicode symbol; "" → the target app's own logo. */
    fun forSlot(ctx: Context, slot: Int): Icon {
        val spec = GpayFix.tileIcon(ctx, slot)
        when {
            spec.startsWith("d:") -> {
                val id = ctx.resources.getIdentifier(spec.substring(2), "drawable", ctx.packageName)
                if (id != 0) return Icon.createWithResource(ctx, id)
            }
            spec.startsWith("e:") -> return Icon.createWithBitmap(emojiBitmap(spec.substring(2)))
        }
        val pkg = GpayFix.tileSlotApp(ctx, slot)
        if (!pkg.isNullOrBlank()) appIconBitmap(ctx, pkg)?.let { return Icon.createWithBitmap(it) }
        return Icon.createWithResource(ctx, R.drawable.ic_launcher_fg)
    }

    /** Draw an emoji / Unicode glyph centered on a transparent square bitmap. */
    fun emojiBitmap(glyph: String, px: Int = 108): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = px * 0.66f
        }
        val fm = p.fontMetrics
        val y = px / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(glyph, px / 2f, y, p)
        return bmp
    }

    /** The app's launcher icon as a square bitmap (null if the app/icon can't be loaded). */
    fun appIconBitmap(ctx: Context, pkg: String, px: Int = 108): Bitmap? = runCatching {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, px, px)
        d.draw(c)
        bmp
    }.getOrNull()
}
