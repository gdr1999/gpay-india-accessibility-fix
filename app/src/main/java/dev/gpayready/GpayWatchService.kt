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
    private var inGpay = false
    private var lastGpayForegroundAt = 0L
    private var pendingRestore = false

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
        var fg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) fg = e.packageName
        }

        if (fg == GpayFix.GPAY) {
            // In Google Pay: make sure accessibility is off, and cancel any pending restore (so a brief
            // background dip during payment — OTP app, bank redirect — doesn't restore mid-payment).
            lastGpayForegroundAt = now
            pendingRestore = false
            if (!inGpay) {
                inGpay = true
                GpayFix.disableAccessibility(applicationContext)
            }
            return
        }
        // A different app is foreground: mark that we've left Google Pay, then restore only after it's
        // stayed away for the debounce window (so flipping to GPay and back doesn't re-block payment).
        if (fg != null && inGpay) {
            inGpay = false
            pendingRestore = true
        }
        if (pendingRestore && now - lastGpayForegroundAt >= RESTORE_DELAY_MS) {
            pendingRestore = false
            GpayFix.restoreAccessibility(applicationContext)
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
