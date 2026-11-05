package dev.gpayready

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * One screen: status, the one-time adb setup command, and the actions. No Compose, no extra deps.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title("GPay Ready"))
        root.addView(body(
            "Google Pay India blocks payments when an accessibility service that looks like an " +
                "automation tool (MacroDroid, launchers, etc.) is enabled. Those are legitimate apps. " +
                "This turns accessibility OFF while you pay and back ON after — the state Google Pay " +
                "wants — with no root, no Shizuku, no debugging left on.",
        ))

        status = body("").also { it.setPadding(0, dp(12), 0, dp(12)) }
        root.addView(status)

        root.addView(primary("Fix & open Google Pay") {
            GpayFix.runFix(this); refresh()
        })
        root.addView(secondary("Restore accessibility") {
            GpayFix.restoreAccessibility(this); toast("Accessibility restored"); refresh()
        })

        // Auto automation (Usage Access): off while Google Pay is open, restored on close.
        val auto = Switch(this).apply {
            text = "Auto: off in Google Pay, restored on close"
            textSize = 15f
            setPadding(0, dp(16), 0, dp(8))
            isChecked = GpayFix.autoEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                if (on && !GpayFix.hasUsageAccess(this@MainActivity)) {
                    toast("Grant Usage Access to App Actions’ GPay Ready, then toggle again")
                    startActivity(GpayFix.usageAccessSettings())
                    isChecked = false
                    return@setOnCheckedChangeListener
                }
                GpayFix.setAutoEnabled(this@MainActivity, on)
                if (on) GpayWatchService.start(this@MainActivity) else GpayWatchService.stop(this@MainActivity)
                refresh()
            }
        }
        root.addView(auto)

        root.addView(secondary("Add Quick Settings tile") { addTile() })
        root.addView(secondary("Open Accessibility settings") { startActivity(GpayFix.accessibilitySettings()) })

        // One-time setup command.
        root.addView(subtitle("One-time setup (no root)"))
        root.addView(body("Connect the phone to a computer with adb and run this once:"))
        val cmd = "adb shell pm grant dev.gpayready android.permission.WRITE_SECURE_SETTINGS"
        root.addView(mono(cmd))
        root.addView(secondary("Copy adb command") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("cmd", cmd))
            toast("Copied")
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val granted = GpayFix.isWriteGranted(this)
        val a11y = GpayFix.accessibilityOn(this)
        status.text = buildString {
            append(if (granted) "✓ Permission granted\n" else "✗ Run the one-time adb command below\n")
            append(if (a11y) "Accessibility: ON" else "Accessibility: OFF (ready to pay)")
            if (GpayFix.autoEnabled(this@MainActivity)) append("\nAuto: ON")
        }
        status.setTextColor(if (granted) Color.parseColor("#1B7F3B") else Color.parseColor("#B3261E"))
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast("Add the tile from the Quick Settings edit screen"); return
        }
        runCatching {
            val sb = getSystemService(android.app.StatusBarManager::class.java)
            sb?.requestAddTileService(
                android.content.ComponentName(this, FixTile::class.java),
                "GPay Ready", android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher_fg),
                {}, {},
            )
        }.onFailure { toast("Add the tile from the Quick Settings edit screen") }
    }

    // --- tiny view helpers ---
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun title(s: String) = TextView(this).apply { text = s; textSize = 28f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
    private fun subtitle(s: String) = TextView(this).apply { text = s; textSize = 18f; setPadding(0, dp(20), 0, dp(6)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
    private fun body(s: String) = TextView(this).apply { text = s; textSize = 15f; setLineSpacing(0f, 1.15f) }
    private fun mono(s: String) = TextView(this).apply {
        text = s; typeface = android.graphics.Typeface.MONOSPACE; textSize = 13f
        setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(Color.parseColor("#11000000"))
        setTextIsSelectable(true)
    }
    private fun primary(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; setPadding(0, dp(8), 0, dp(8)); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
        setOnClickListener { onClick() }
    }
    private fun secondary(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
}
