package dev.gpayready

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Debug / "pay-ready" status: a glanceable notification that shows whether the three things banking
 * apps dislike — Accessibility, Developer Options, USB debugging — are each OFF, with a clear
 * READY / NOT-READY verdict. Lets you confirm the safe state in the shade before you pay.
 */
object DebugStatus {
    private const val CH = "pay_ready"
    private const val NID = 4242

    fun post(ctx: Context, ongoing: Boolean = true) {
        val s = GpayFix.payState(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "Pay-ready status", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        fun tick(off: Boolean) = if (off) "✅" else "❌" // ✅ / ❌
        val short = "Accessibility ${tick(!s.a11yOn)}   Dev Options ${tick(!s.devOn)}   USB debug ${tick(!s.usbOn)}"
        val title = if (s.ready) "✅ Ready to pay" else "⚠️ Not ready — something is still ON"
        val detail = buildString {
            appendLine("${tick(!s.a11yOn)} Accessibility: ${if (s.a11yOn) "ON" else "OFF"}")
            appendLine("${tick(!s.devOn)} Developer Options: ${if (s.devOn) "ON" else "OFF"}")
            appendLine("${tick(!s.usbOn)} USB debugging: ${if (s.usbOn) "ON" else "OFF"}")
            append(if (s.ready) "\nAll off — safe to pay." else "\nTurn the ❌ item(s) off before paying.")
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(title)
            .setContentText(short)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .build()
        runCatching { nm.notify(NID, n) }
    }

    fun clear(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NID) }
    }
}
