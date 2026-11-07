package dev.gpayready

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * The automation (Usage Access, NOT accessibility): a lightweight foreground service that watches the
 * foreground app via [UsageStatsManager] and, when Google Pay comes to the front, turns accessibility
 * OFF — then turns it back ON when you leave Google Pay. So your accessibility apps (MacroDroid,
 * launchers) are only off while you're actually in Google Pay, and restored the moment you close it.
 *
 * Uses Usage Access, which does NOT need accessibility, ADB or root — just the one-time Usage Access
 * grant in Settings. Polls ~every 1.5 s; self-stops if the automation is turned off.
 */
class GpayWatchService : Service() {

    private var thread: HandlerThread? = null
    private lateinit var handler: Handler
    private var lastQuery = 0L
    /** The last-known foreground package — STICKY: it is only updated when a new foreground event
     *  arrives, so "no new event this tick" (sitting in an app, or idle on home) doesn't look like
     *  "left the app". null = we haven't observed any foreground yet (no Usage Access signal). */
    private var currentFg: String? = null
    private var lastTargetAt = 0L
    /** Only restore after we've actually SEEN a target app in the foreground and then left it. Without
     *  this latch, tapping the manual fix from a non-target screen (the app, a tile) would be undone
     *  instantly — the watcher would see "accessibility off, not in a target" and restore right away. */
    private var armed = false
    /** The target app we were most recently inside — decides whether leaving restores (some apps,
     *  e.g. food-delivery / shopping apps that deep-link into Google Pay to pay, are set NOT to
     *  restore on close, so leaving them keeps accessibility off on the way to Google Pay). */
    private var lastTarget: String? = null

    private val poll = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()
        if (thread == null) {
            thread = HandlerThread("gpaywatch").apply { start() }
            handler = Handler(thread!!.looper)
            lastQuery = System.currentTimeMillis()
            lastTargetAt = System.currentTimeMillis()
            handler.post(poll)
        }
        return START_STICKY
    }

    private fun tick() {
        if (!GpayFix.autoEnabled(this)) { stopSelf(); return }
        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery - 2000, now)
        lastQuery = now
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) currentFg = e.packageName
        }

        // No foreground observed yet (e.g. Usage Access not granted) → no signal, do nothing.
        val fg = currentFg ?: return

        if (GpayFix.isTarget(this, fg)) {
            // In a target app: keep accessibility (and dev options) OFF, and ARM restore for when we
            // leave. lastTargetAt keeps advancing while we're here, so the debounce never fires mid-use.
            lastTargetAt = now
            lastTarget = fg
            armed = true
            if (GpayFix.accessibilityOn(applicationContext)) GpayFix.disableForPay(applicationContext)
            return
        }

        // A non-target app is foreground. Only restore if we were armed (i.e. we were just inside a
        // target app) and have now stayed away long enough — so the manual fix, launched from a
        // non-target screen, is NOT instantly undone, but closing a target app DOES restore. Skip the
        // restore if the app we just left is set NOT to restore on close (an affiliate that heads into
        // Google Pay) — accessibility must stay off so the payment isn't blocked.
        if (armed && now - lastTargetAt >= RESTORE_DELAY_MS) {
            armed = false
            if (GpayFix.restoreOnClose(applicationContext, lastTarget)) {
                // Kill the app we just left (+ Play Services) BEFORE restoring accessibility, then wait a
                // beat so their live AccessibilityStateChange listeners are gone first. Restoring while
                // Google Pay's process is still alive re-latches the block on the next open.
                val app = applicationContext
                lastTarget?.let { GpayFix.killBackground(app, it) }
                GpayFix.killBackground(app, GpayFix.GMS)
                handler.postDelayed({ GpayFix.restoreAll(app) }, 1200)
            }
        }
    }

    private fun startForegroundSafe() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CH, "GPay auto", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val n = Notification.Builder(this, CH)
            .setContentTitle("GPay Ready — auto")
            .setContentText("Accessibility off only while Google Pay is open")
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { if (::handler.isInitialized) handler.removeCallbacks(poll) }
        thread?.quitSafely()
        thread = null
    }

    companion object {
        private const val CH = "gpay_auto"
        /** Wait this long after Google Pay leaves the foreground before restoring accessibility, so a
         *  brief mid-payment background (OTP app, bank redirect) doesn't restore early and re-block. */
        private const val RESTORE_DELAY_MS = 4000L
        fun start(ctx: Context) {
            val i = Intent(ctx, GpayWatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, GpayWatchService::class.java)) }
        }
    }
}
