package dev.gpayready

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Revive the automation if it's enabled but not running (killed by reinstall / low memory).
        if (GpayFix.autoEnabled(this) && GpayFix.hasUsageAccess(this)) {
            runCatching { GpayWatchService.start(this) }
        }
        setContent { AppTheme { Root() } }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var onboarding by remember { mutableStateOf(!GpayFix.onboarded(ctx)) }
    if (onboarding) {
        Onboarding(onDone = { GpayFix.setOnboarded(ctx, true); onboarding = false })
    } else {
        Screen(onHowItWorks = { onboarding = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(onHowItWorks: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var autoOn by remember { mutableStateOf(GpayFix.autoEnabled(ctx)) }
    var showPicker by remember { mutableStateOf(false) }
    var homeDialogPkg by remember { mutableStateOf<String?>(null) }
    var tileDialogPkg by remember { mutableStateOf<String?>(null) }
    var pendingImagePkg by remember { mutableStateOf<String?>(null) }
    var showOverlay by remember { mutableStateOf(false) }
    val overlaySuspects = remember { OverlayApps.suspects(ctx) }
    var showAudit by remember { mutableStateOf(false) }

    val granted = remember(tick) { GpayFix.isWriteGranted(ctx) }
    val pay = remember(tick) { GpayFix.payState(ctx) }
    val targets = remember(tick) { GpayFix.targetPackages(ctx) }
    var debugOn by remember { mutableStateOf(GpayFix.debugMode(ctx)) }

    LaunchedEffect(tick) { GpayFix.rememberIfOn(ctx) }
    // Keep the status live (it can change from the automation / tile / another app).
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500); tick++
            if (GpayFix.debugMode(ctx)) runCatching { DebugStatus.post(ctx) }
        }
    }

    val requestNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }
    fun needsNotifPerm() = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    // Debug mode defaults on → ask for notification permission once so the status can actually show.
    LaunchedEffect(Unit) { if (GpayFix.debugMode(ctx) && needsNotifPerm()) requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val pickIcon = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val pkg = pendingImagePkg
        if (uri != null && pkg != null) {
            val bmp = loadBitmap(ctx, uri)
            pinToHome(ctx, pkg, bmp)
        }
        pendingImagePkg = null
    }

    if (showPicker) {
        AppPickerDialog(
            selected = GpayFix.targetPackages(ctx),
            onDone = { pkgs -> GpayFix.setTargetPackages(ctx, pkgs); showPicker = false; tick++ },
            onDismiss = { showPicker = false },
        )
    }
    homeDialogPkg?.let { pkg ->
        HomeIconDialog(
            pkg = pkg,
            onPickImage = { pendingImagePkg = pkg; homeDialogPkg = null
                pickIcon.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onChosen = { bmp -> pinToHome(ctx, pkg, bmp); homeDialogPkg = null },
            onDismiss = { homeDialogPkg = null },
        )
    }
    tileDialogPkg?.let { pkg ->
        TileIconDialog(
            pkg = pkg,
            onConfirm = { spec -> addTileForApp(ctx, pkg, spec); tileDialogPkg = null },
            onDismiss = { tileDialogPkg = null },
        )
    }
    if (showOverlay) OverlayAppsDialog(overlaySuspects, onDismiss = { showOverlay = false })
    if (showAudit) A11yAuditDialog(onChanged = { tick++ }, onDismiss = { showAudit = false })

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("GPay Ready") },
            actions = { TextButton(onClick = onHowItWorks) { Text("How it works") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            // Pay-ready status (debug mode)
            PayStatusCard(granted = granted, pay = pay, debugOn = debugOn,
                onToggleDebug = { on ->
                    GpayFix.setDebugMode(ctx, on); debugOn = on
                    if (on && needsNotifPerm()) requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    tick++
                })

            // BUTTON 1 — Select apps
            ActionCard(Icons.Filled.Star, Color(0xFF6366F1), "Select apps",
                if (targets.isEmpty()) "Choose the apps to fix"
                else "${targets.size} selected · tap to change") { showPicker = true }

            // Per-app cards (each has: Fix & open, Add to Home, Add QS tile, Restore-on-close)
            targets.forEach { pkg ->
                AppCard(
                    pkg = pkg, tick = tick,
                    onRun = { GpayFix.runFixFor(ctx, pkg); tick++ },
                    onAddHome = { homeDialogPkg = pkg },
                    onAddTile = { tileDialogPkg = pkg },
                    onToggleRestore = { on -> GpayFix.setRestoreOnClose(ctx, pkg, on); tick++ },
                    onRemove = { GpayFix.removeTarget(ctx, pkg); tick++ },
                )
            }

            // Auto automation
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Icons.Filled.Notifications, Color(0xFF6366F1))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Auto (Usage Access)", fontWeight = FontWeight.Bold)
                        Text("Off while a selected app is open; restores on close (per-app, above)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoOn, onCheckedChange = { on ->
                        if (on && !GpayFix.hasUsageAccess(ctx)) {
                            toast(ctx, "Grant Usage Access to GPay Ready, then toggle again")
                            ctx.startActivity(GpayFix.usageAccessSettings()); return@Switch
                        }
                        GpayFix.setAutoEnabled(ctx, on); autoOn = on
                        if (on) GpayWatchService.start(ctx) else GpayWatchService.stop(ctx)
                    })
                }
            }

            // Accessibility audit — classify which services GPay flags (can control/capture) vs safe ones,
            // and turn off only the risky ones (keep the harmless ones on permanently).
            val controlCount = remember(tick) { A11yAudit.controllingCount(ctx) }
            ActionCard(Icons.Filled.Warning,
                if (controlCount > 0) Color(0xFFB26A00) else Color(0xFF1B7F3B),
                "Accessibility audit" + if (controlCount > 0) " ($controlCount trip GPay)" else " (all clear)",
                "See which services GPay blocks — turn off only those, keep the rest") { showAudit = true }

            // Overlay apps — "Display over other apps" trips GPay's KNOWN_OVERLAYS check (and breaks
            // browser popups). We can't toggle another app's overlay no-root, but we can jump you to each.
            if (overlaySuspects.isNotEmpty()) {
                ActionCard(Icons.Filled.Warning, Color(0xFFB26A00),
                    "Apps that can draw over GPay (${overlaySuspects.size})",
                    "Overlays can block payments — tap to turn each one off") { showOverlay = true }
            }

            // Stuck/latched block — on GPay 356 only a reboot clears it (force-stop was tested: it doesn't).
            ActionCard(Icons.Filled.Refresh, Color(0xFFB3261E), "Still blocked? Restart the phone",
                "GPay 356: only a reboot clears the latch — a force-stop won't. (Tap for Play Services force-stop, for older versions.)") {
                runCatching { ctx.startActivity(GpayFix.appInfoIntent(GpayFix.GMS)) }
                toast(ctx, "GPay 356 needs a phone restart. (Force-stop only helps older versions.)")
            }

            // Restore / share
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    val n = GpayFix.restoreAfterUse(ctx)
                    toast(ctx, if (n > 0) "Restored $n service(s)" else "Nothing saved to restore yet")
                    tick++
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Restore")
                }
                OutlinedButton(onClick = { shareApk(ctx) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share")
                }
            }

            // Setup section
            Spacer(Modifier.height(6.dp))
            Text("One-time setup (no root)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Connect your phone to a PC / Mac once and run this (you never need the PC / Mac again):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SetupCommand()
            Spacer(Modifier.height(24.dp))
        }
    }
}

// --- per-app card ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppCard(
    pkg: String, tick: Int,
    onRun: () -> Unit, onAddHome: () -> Unit, onAddTile: () -> Unit, onToggleRestore: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    val label = remember(pkg) { GpayFix.labelFor(ctx, pkg) }
    val restoreOn = remember(pkg, tick) { GpayFix.restoreOnClose(ctx, pkg) }
    Surface(shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pkg, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove $label",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onRun, modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Test", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAddHome, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Home")
                }
                FilledTonalButton(onClick = onAddTile, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Filled.Star, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("QS tile")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Restore accessibility on close", style = MaterialTheme.typography.bodyMedium)
                    Text(if (restoreOn) "On — turns it back on when you leave"
                    else "Off — stays off (for apps that deep-link into Google Pay)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = restoreOn, onCheckedChange = onToggleRestore)
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String, size: Int = 44) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) { TileIcons.appIconBitmap(ctx, pkg, 128) }
    if (bmp != null) {
        Image(bmp.asImageBitmap(), null,
            Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)))
    } else {
        IconChip(Icons.Filled.Star, Color(0xFF6366F1), size)
    }
}

// --- Add-to-Home icon-source dialog (app icon / icon pack / image) ---

@Composable
private fun HomeIconDialog(pkg: String, onPickImage: () -> Unit, onChosen: (Bitmap?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val packs = remember { IconPacks.installed(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Home-screen icon") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { onChosen(TileIcons.appIconBitmap(ctx, pkg, 192)) }
                        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pkg, 36); Spacer(Modifier.width(12.dp)); Text("App's own icon")
                    }
                    Row(Modifier.fillMaxWidth().clickable { onPickImage() }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconChip(Icons.Filled.Add, Color(0xFF8B5CF6), 36); Spacer(Modifier.width(12.dp))
                        Text("Choose an image…")
                    }
                    if (packs.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("Icon packs", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                items(packs) { pack ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val icon = IconPacks.iconFor(ctx, pack.pkg, pkg)
                        if (icon == null) toast(ctx, "This pack has no icon for the app — using its own icon")
                        onChosen(icon ?: TileIcons.appIconBitmap(ctx, pkg, 192))
                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pack.pkg, 36); Spacer(Modifier.width(12.dp))
                        Text(pack.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (packs.isEmpty()) item {
                    Text("No icon packs installed.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
    )
}

// --- QS-tile icon dialog (app logo / emoji glyph) ---

// Built-in monochrome glyphs that Quick Settings tints white, exactly like the system tiles.
private val TILE_ICONS = listOf(
    "ic_tile_payment", "ic_tile_bank", "ic_tile_wallet", "ic_tile_bolt", "ic_tile_lock",
    "ic_tile_shield", "ic_tile_star", "ic_tile_scan", "ic_tile_phone",
)

@Composable
private fun TileIconDialog(pkg: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val label = remember(pkg) { GpayFix.labelFor(ctx, pkg) }
    // spec: "d:<drawable>" (white glyph) | "" (app logo) | "e:<emoji>"
    var spec by remember { mutableStateOf("d:ic_tile_payment") }
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(spec) }) { Text("Add tile") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Tile icon for $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("White icons — match your Quick Settings tiles",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TILE_ICONS.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { name ->
                            val sel = spec == "d:$name"
                            val id = remember(name) { ctx.resources.getIdentifier(name, "drawable", ctx.packageName) }
                            Surface(
                                onClick = { spec = "d:$name"; custom = "" },
                                shape = RoundedCornerShape(12.dp),
                                color = if (sel) MaterialTheme.colorScheme.primary else Color(0xFF3A3A3C),
                                modifier = Modifier.size(46.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (id != 0) Icon(painterResource(id), null, tint = Color.White, modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
                // App-logo option
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { spec = ""; custom = "" }
                    .background(if (spec.isBlank()) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, 30); Spacer(Modifier.width(10.dp)); Text("App logo", Modifier.weight(1f))
                    if (spec.isBlank()) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
                // Custom emoji / symbol
                OutlinedTextField(
                    value = custom,
                    onValueChange = { v -> custom = v.take(4); spec = if (v.isBlank()) "d:ic_tile_payment" else "e:${v.take(4)}" },
                    singleLine = true, label = { Text("Or type an emoji / symbol") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Quick Settings tints tile icons to one colour; the white icons above read best.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun SetupCommand() {
    val ctx = LocalContext.current
    val cmd = "adb shell pm grant dev.gpayready android.permission.WRITE_SECURE_SETTINGS"
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Text(cmd, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
    TextButton(onClick = {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("cmd", cmd))
        toast(ctx, "Copied")
    }) { Text("Copy adb command") }
}

// --- onboarding ---

private data class Page(val icon: ImageVector, val color: Color, val title: String, val body: String, val showCmd: Boolean = false)

@Composable
private fun Onboarding(onDone: () -> Unit) {
    val pages = listOf(
        Page(Icons.Filled.PlayArrow, Color(0xFF6366F1), "Pay without the hassle",
            "Some apps (Google Pay, banks) block or misbehave when an accessibility app — MacroDroid, a custom launcher, a gesture app — is enabled. GPay Ready turns accessibility OFF while you use the app and back ON after. No root, no Shizuku."),
        Page(Icons.Filled.Warning, Color(0xFFF59E0B), "One-time setup (needs a PC / Mac once)",
            "Grant one permission over adb, once. You never need the PC / Mac again. Copy the command below.", showCmd = true),
        Page(Icons.Filled.Add, Color(0xFF8B5CF6), "Add it to your Home screen",
            "Add any app's card to your Home screen (your own image or an icon pack), or as a Quick Settings tile. It then acts like the app itself: you tap it, it quietly turns accessibility + Developer Options OFF in the background, then opens the app normally. (The Test button in each card does the same, for trying it.)"),
        Page(Icons.Filled.CheckCircle, Color(0xFF10B981), "Turn on Auto to restore",
            "Flip the Auto toggle on (it asks for Usage Access once). Accessibility and Developer Options then switch back ON automatically the moment you close the app — so your other apps keep working. Per app, you can keep it OFF on close, for apps that deep-link into Google Pay to pay."),
    )
    val state = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = state.currentPage == pages.lastIndex

    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text("Skip") }
            }
            HorizontalPager(state = state, modifier = Modifier.weight(1f)) { i ->
                val p = pages[i]
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconChip(p.icon, p.color, size = 72)
                    Spacer(Modifier.height(24.dp))
                    Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(p.body, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.showCmd) { Spacer(Modifier.height(16.dp)); SetupCommand() }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center) {
                repeat(pages.size) { i ->
                    val sel = i == state.currentPage
                    Box(Modifier.padding(4.dp).size(if (sel) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)))
                }
            }
            Button(
                onClick = { if (last) onDone() else scope.launch { state.animateScrollToPage(state.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
            ) { Text(if (last) "Get started" else "Next") }
        }
    }
}

// --- app picker dialog ---

@Composable
private fun AppPickerDialog(selected: List<String>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val all = remember { GpayFix.launchableApps(ctx) }
    val picked = remember { mutableStateListOf<String>().apply { addAll(selected) } }
    var q by remember { mutableStateOf("") }
    val shown = remember(q) {
        if (q.isBlank()) all else all.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDone(picked.toList()) }) { Text("Done (${picked.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose apps") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true,
                    placeholder = { Text("Search apps") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown) { app ->
                        val on = app.pkg in picked
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (on) picked.remove(app.pkg) else picked.add(app.pkg)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = {
                                if (it) { if (app.pkg !in picked) picked.add(app.pkg) } else picked.remove(app.pkg)
                            })
                            Spacer(Modifier.width(4.dp))
                            AppIcon(app.pkg, 32)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.pkg, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
    )
}

// --- overlay ("Display over other apps") manager ---

@Composable
private fun OverlayAppsDialog(apps: List<GpayFix.AppItem>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Apps drawing over the screen") },
        text = {
            Column {
                Text("Any of these with “Display over other apps” on can make Google Pay block you (and break browser popups). Android won't let this app turn them off for you — tap one to open its toggle and turn it OFF yourself. Tip: when your browser popups work again, the overlay is gone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(apps) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                runCatching { ctx.startActivity(OverlayApps.settingsIntent(app.pkg)) }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.pkg, 34)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.pkg, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("Turn off ›", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
    )
}

// --- accessibility audit (classify + selectively disable) ---

@Composable
private fun A11yAuditDialog(onChanged: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val svcs = remember(version) { A11yAudit.services(ctx) }
    val controlOn = svcs.count { it.enabled && it.tier == A11yAudit.Tier.CONTROL }
    val red = Color(0xFFB3261E); val amber = Color(0xFFB26A00); val green = Color(0xFF1B7F3B)
    fun tierColor(t: A11yAudit.Tier) = when (t) {
        A11yAudit.Tier.CONTROL -> red; A11yAudit.Tier.READ -> amber; A11yAudit.Tier.MINIMAL -> green
    }
    fun tierLabel(t: A11yAudit.Tier) = when (t) {
        A11yAudit.Tier.CONTROL -> "Controls device"; A11yAudit.Tier.READ -> "Reads screen"; A11yAudit.Tier.MINIMAL -> "Safe"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Which apps trip Google Pay") },
        text = {
            Column {
                Text("Google Pay blocks accessibility services that can control or capture the screen — not all of them. Turn off the red ones (keep the rest), then reboot to clear the block.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(red to "Blocks GPay", amber to "Maybe", green to "Safe").forEach { (c, t) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                            Text(t, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (controlOn > 0) {
                    Button(onClick = { A11yAudit.disableControlling(ctx); version++; onChanged() },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = red)) {
                        Text("Turn off all $controlOn controlling service(s)")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(svcs) { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // stoplight
                            Box(Modifier.size(14.dp).clip(CircleShape).background(tierColor(s.tier)))
                            Spacer(Modifier.width(10.dp))
                            AppIcon(s.pkg, 34)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(color = tierColor(s.tier).copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                        Text(tierLabel(s.tier), Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall, color = tierColor(s.tier))
                                    }
                                    Text(s.powers(), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Switch(checked = s.enabled, onCheckedChange = { on ->
                                A11yAudit.setEnabled(ctx, s.component, on); version++; onChanged()
                            })
                        }
                    }
                }
                Text("Heuristic from each service's capabilities — confirm by rebooting and paying. “Reads screen”/“Safe” can usually stay on.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

// --- shared bits ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionCard(icon: ImageVector, color: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconChip(icon, color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PayStatusCard(
    granted: Boolean, pay: GpayFix.PayState, debugOn: Boolean,
    onToggleDebug: (Boolean) -> Unit,
) {
    val green = Color(0xFF1B7F3B); val amber = Color(0xFFB26A00); val red = Color(0xFFB3261E)
    val ready = granted && pay.ready
    val color = when { !granted -> red; ready -> green; else -> amber }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (ready) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = color)
                Column(Modifier.weight(1f)) {
                    Text(when {
                        !granted -> "Needs one-time adb setup (below)"
                        ready -> "Ready to pay"
                        else -> "Not ready — something is still on"
                    }, fontWeight = FontWeight.Bold, color = color)
                    Text("Debug mode — notifies you when all are off",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = debugOn, onCheckedChange = onToggleDebug)
            }
            if (debugOn && granted) {
                StatusRow("Accessibility", pay.a11yOn)
                StatusRow("Developer Options", pay.devOn)
                StatusRow("USB debugging", pay.usbOn)
                Text("A different block — “device doesn't meet security standards” — is a Play Integrity check (beta OS, root, or an uncertified build), not these toggles, and can't be fixed by this app.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, on: Boolean) {
    val green = Color(0xFF1B7F3B); val amber = Color(0xFFB26A00)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (!on) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
            tint = if (!on) green else amber, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(if (on) "ON" else "OFF", fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium, color = if (!on) green else amber)
    }
}

@Composable
private fun IconChip(icon: ImageVector, color: Color, size: Int = 40) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(if (size >= 64) 20.dp else 12.dp),
        modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size((size * 0.5).dp)) }
    }
}

// --- actions ---

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

private fun loadBitmap(ctx: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri))
    } else {
        @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
    }.copy(Bitmap.Config.ARGB_8888, false)
}.getOrNull()

private fun addTileForApp(ctx: Context, pkg: String, spec: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        toast(ctx, "Add the tile from the Quick Settings edit screen"); return
    }
    val slot = GpayFix.assignTileSlot(ctx, pkg)
    GpayFix.setTileIcon(ctx, slot, spec)
    val label = GpayFix.labelFor(ctx, pkg)
    runCatching {
        ctx.getSystemService(android.app.StatusBarManager::class.java)?.requestAddTileService(
            SlotTileService.componentFor(ctx, slot), label, TileIcons.forSlot(ctx, slot), {}, {},
        )
    }.onFailure { toast(ctx, "Add the tile from the Quick Settings edit screen") }
}

private fun shareApk(ctx: Context) {
    runCatching {
        val apk = File(ctx.applicationInfo.sourceDir)
        val dest = File(ctx.cacheDir, "GPayReady.apk").also { apk.copyTo(it, overwrite = true) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dest)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "GPay Ready — pay without turning off your accessibility apps by hand. https://github.com/gdr1999/gpay-india-accessibility-fix")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share GPay Ready").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(ctx, "Couldn't share: ${it.message}") }
}

private fun pinToHome(ctx: Context, pkg: String, bmp: Bitmap?) {
    val sm = ctx.getSystemService(ShortcutManager::class.java)
    if (sm == null || !sm.isRequestPinShortcutSupported) { toast(ctx, "Your launcher doesn't support pinning"); return }
    val icon = if (bmp != null) Icon.createWithBitmap(bmp) else Icon.createWithResource(ctx, R.drawable.ic_launcher_fg)
    val target = Intent(ctx, DispatchActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(DispatchActivity.EXTRA_PKG, pkg)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val info = ShortcutInfo.Builder(ctx, "pin_$pkg")
        .setShortLabel(GpayFix.labelFor(ctx, pkg))
        .setIcon(icon).setIntent(target).build()
    runCatching { sm.requestPinShortcut(info, null); toast(ctx, "Added to Home screen") }
        .onFailure { toast(ctx, "Couldn't pin: ${it.message}") }
}
