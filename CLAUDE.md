# GPay Ready — agent handoff & build notes

A tiny **no-root** Android app that helps you pay with Google Pay (and other UPI/banking apps) when you
run legitimate **accessibility automation apps** (MacroDroid, Tasker, gesture/sidebar apps). It turns off
the accessibility services Google Pay flags (plus Developer Options / USB debugging), then restores them,
and classifies which of your apps actually trip GPay. For the user's **own personal use**; also published
open-source.

- Package: `dev.gpayready`. Repo: https://github.com/gdr1999/gpay-india-accessibility-fix (MIT, owner `gdr1999`).
- Jetpack **Compose Material 3** (dynamic color), AGP 9.4.1 + built-in Kotlin (`com.android.application` +
  `kotlin.compose` only), Kotlin 2.4.21, minSdk 28 / targetSdk 36 / compileSdk 37.2. versionName **1.1**.
- **Latest GitHub release is v1.1**, but `main` has unreleased work (stoplight accessibility audit, honest
  "Restart the phone" relabel, overlay manager). Cut **v1.2** when the audit flow is confirmed on device.

> **READ THE BLOCKER SECTION BELOW BEFORE PROPOSING A "FIX."** The core problem is largely **unsolvable
> no-root** on current Google Pay. Many obvious ideas were tested on-device and failed — don't repeat them.

## Build / run / device setup
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
cd "/Users/gaurav/Projects/GPayReady"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# ONE-TIME grant (per install — see gotchas), no root:
adb shell pm grant dev.gpayready android.permission.WRITE_SECURE_SETTINGS
```
Test device: user's Pixel 11, **Android 17 (QPR3 Beta 3)**, build `DP11.261016.004`, verified-boot green,
bootloader locked. Connected over USB (often unplugged — check `adb get-state`). Debug-signed only (no
release keystore). Cut releases with `gh release create` + stable-named `GPayReady.apk` asset so the
README button (`releases/latest/download/GPayReady.apk`) always resolves.

## Architecture (`app/src/main/java/dev/gpayready/`)
- **`GpayFix.kt`** — the core. `disableAccessibility`/`restoreAccessibility` (write
  `enabled_accessibility_services` via WRITE_SECURE_SETTINGS, saved/restored in prefs),
  `disableDevOptions`/`restoreDevOptions` (dev/USB/wireless debugging), `disableForPay`, `restoreAll`,
  `restoreAfterUse` (kills caches before restoring — see note), `killBackground`
  (ActivityManager.killBackgroundProcesses), `runFix`/`runFixFor(pkg)`, multi-target selection
  (`targetPackages`), per-tile/per-app helpers, `payState` (the 3 toggles), `appInfoIntent`.
- **`A11yAudit.kt`** — the stoplight classifier. Reads each accessibility service's
  `AccessibilityServiceInfo.getCapabilities()` and tiers it CONTROL (perform-gestures/filter-keys/
  screenshot/fingerprint → 🔴 flagged by GPay) / READ (retrieve-content → 🟡) / MINIMAL (events → 🟢).
  Can write a SUBSET of enabled services (turn off only 🔴, keep 🟢). **Key feature — this is the honest
  value prop now.**
- **`MainActivity.kt`** — Compose UI: pay-ready status + debug mode, Select apps, per-app cards
  (Test / Add to Home / QS tile / restore-on-close), **Accessibility audit** dialog (stoplights), overlay
  manager, "Still blocked? Restart the phone" card, Auto toggle, Restore/Share, setup.
- **`GpayWatchService.kt`** — Usage-Access automation (NOT accessibility): turns toggles off when a chosen
  app is foreground, restores on leave (gated per-app by `restoreOnClose`; kills target before restoring).
- **`OverlayApps.kt`** — lists apps with "Display over other apps" (SYSTEM_ALERT_WINDOW) and jumps to each
  one's overlay toggle (can't toggle another app's overlay no-root — MANAGE_APP_OPS_MODES isn't grantable).
- **`DebugStatus.kt`** — "pay-ready" notification (Accessibility/Dev-Options/USB each OFF + verdict).
- **`AppTile.kt`** — pool of 5 per-app QS tiles (`AppTile0..4`) + `TileIcons` (app logo / emoji / white glyph).
- **`DispatchActivity`** (trampoline, reads `EXTRA_PKG`), **`FixTile`** (generic QS tile),
  **`CreateShortcutActivity`** (CREATE_SHORTCUT → MacroDroid/Tasker/launchers), **`RunReceiver`**
  (am broadcast RUN/RESTORE), **`BootReceiver`**, **`IconPacks.kt`** (home-icon icon packs).

## THE CORE PROBLEM (why the fix broke) — confirmed by on-device test + APK teardown
Google Pay India **356** blocks payments when an accessibility service that can **control or capture** the
screen is enabled. It used to be clearable by toggling accessibility off; **356 made it a latch.**

**Mechanism (from decompiling GPay 356 — strings in the base.apk dex + Dart `.so`):**
- Accessibility detection is **live**: `getEnabledAccessibilityServiceList` +
  `AccessibilityStateChangeListener`; classifier `ACCESSIBILITY_SERVICE_WITH_POTENTIAL_AUTOMATION_USE` →
  `AccessibilityFocusBlockType` / `isAccessibilityFocusBlocked`. Maps to Play Integrity **app-access-risk**
  verdicts `KNOWN_CONTROLLING` / `KNOWN_CAPTURING`. GPay reads the **resolved** list
  (`getEnabledAccessibilityServiceList`), NOT the raw `enabled_accessibility_services` string (that string
  is absent from the dex). Google also **allowlists** services that passed its "enhanced accessibility
  review" (real screen readers), so capability is a strong *guide*, not a perfect predictor.
- The block is a **cached Play Integrity / DroidGuard attestation latch**:
  `tapandpay_get_last_attestation_result` + `dcs_get_verdict`. A process kill re-reads the **cached**
  verdict; **only a reboot forces fresh attestation** → clears it. GPay's own screen says *"restart your
  phone to unblock."*
- Overlay/screen-capture is a **separate** axis: `HIDE_OVERLAY_WINDOWS`, `setFilterTouchesWhenObscured`,
  message "Close any app that is displaying over your screen" (= `KNOWN_OVERLAYS`).

**Binary diff 355.1.5 vs 356.1.4/1.5/1.6:** the security code is **identical** (same accessibility +
integrity + overlay strings/counts); the ~5k new strings in 356 are telemetry/feature churn, no new
blocking logic. **Conclusion: the change that broke the fix is SERVER-SIDE** (a Google risk-verdict
rollout the same client enforces). The newer block wording ("restart to unblock" / "some apps have
permission to make payments") is **not in the 356 strings** → server-delivered. (APK copies were pulled
read-only for analysis; never modify/reinstall GPay.)

## THE BLOCKAGE (current) — what is NOT solvable, tested on device
The user wants to keep automation apps enabled AND pay with GPay. On GPay 356 that needs: accessibility
off **+ a reboot** to clear the latch, before every clean payment, and the latch returns the moment a
flagged service is seen again. **No no-root app can reboot or force fresh attestation.** Dead ends
**verified on this device — do not re-propose:**
1. **Headless `killBackgroundProcesses`** → no-op on Android 14+ (own process only; can't stop GMS/GPay).
2. **Real `am force-stop com.google.android.gms` + GPay (no reboot)** → STILL BLOCKED. The "Deep reset"
   (App-Info Force stop) therefore does NOT clear 356's latch. Only a full **reboot** does.
3. **Shizuku** → its only extra power is shell-level force-stop, which is #2 (already failed). It can't
   restart the framework (root-only). Also self-defeating: needs Developer Options/debugging ON (GPay
   flags that) and is a risk signal.
4. **Delay / force-close the automation apps before launching GPay** → GPay reads the enabled-service
   **setting/resolved list**, not running processes; Android auto-restarts an enabled service. No help.
5. **Private Space** → it's a **profile** (`parentId=0`) that shares the parent user's accessibility →
   GPay there sees the main profile's services → BLOCKED. Confirmed: none of the automation apps are even
   installed in Private Space, yet it still blocks. Work Profile fails for the same reason.
6. **Downgrade GPay to 355** → binary-identical enforcement; server pushes the same verdict → won't help.

**Still-open / untested idea:** a **fully separate secondary USER** (not a profile — a peer with
`parentId` of its own) has independent accessibility and should see no automation service. Likely snag is
**UPI registration** (secondary users usually lack SMS/telephony to send the bind-SMS), not the block.
Needs a device test; verify via adb that the new user's `enabled_accessibility_services` is empty first.

## What the app RELIABLY does vs can't
- **Reliably:** turn accessibility off (+ Dev Options/USB debugging), restore them; classify which services
  trip GPay (**stoplight audit — works even with no adb grant**, read-only); surface overlay apps; expose
  as a MacroDroid/Tasker/launcher shortcut; debug-mode "ready to pay?" notification.
- **Cannot (no-root ceiling):** clear the 356 latch (needs a reboot), force-stop other apps on A14+, toggle
  another app's overlay permission, reboot.

**Honest recipe shipped in README/app:** turn off the 🔴 control-capable services → **reboot** → pay before
re-enabling. The stoplight audit means you keep your 🟢/🟡 apps.

## Why Paytm works (recommend it for power users) — confirmed by teardown
Paytm (`net.one97.paytm` 10.86.9, native app) reads the **same** signals (accessibility list, live
listener, overlay/obscured, Play Integrity + SafetyNet + **Protectt.ai** SDK) BUT blocks only on **active
screen recording / sharing / mirroring** or a **hardcoded remote-access app blocklist** (AnyDesk,
TeamViewer, AirDroid, named scam apps) — not on the mere *presence* of an automation-capable accessibility
service. **No latch** (no "restart your phone" strings, no cached-verdict). So idle MacroDroid/Tasker don't
trip it. Recommendation framing: **general users → Google Pay** (its strictness is real anti-scam
security); **power users → Paytm/PhonePe/BHIM/bank app** (works today, but they read the same signals and
could tighten later).

## Open items / what a new agent could do
1. **Confirm the stoplight audit end-to-end** on device: audit → turn off the 🔴 services → reboot → pay
   (keeping 🟢 on). If good, **cut v1.2** (bump versionCode/Name, build, `gh release create` with both
   `GPayReady.apk` + versioned asset).
2. **Secondary-user test** (above) — the last isolation idea; verify accessibility isolation + whether UPI
   setup is possible there.
3. **Docs:** optional `docs/PAYTM_VS_GPAY.md` / `docs/WHAT_CHANGED_355_to_356.md` from the findings here;
   note GPay now explicitly flags Developer Options.
4. A Reddit write-up draft exists (user's, not in repo) — the user posts it; **Claude never posts to Reddit.**

## Rules / gotchas (do not violate)
- **Read-only on Google Pay / banking apps** — never drive/automate their UI, never make a payment. Pull
  APKs read-only (copy, never modify/reinstall).
- **Do NOT run `runFix` / `disableForPay` over adb** — it turns **USB debugging off** → drops the adb
  connection. For device tests, write secure settings directly (leave `adb_enabled` on) and restore after.
- The `pm grant WRITE_SECURE_SETTINGS` is **per-install** — uninstalling/reinstalling **revokes** it and
  the app silently no-ops. Re-grant after every install; the status card says "Needs one-time adb setup"
  when missing. Verify: `adb shell dumpsys package dev.gpayready | grep -i WRITE_SECURE_SETTINGS`.
- **No root, no Shizuku, no wireless/USB-debugging left on** — banking apps read that state.
- **No `INTERNET` permission** in the manifest — keep it that way (it physically can't exfiltrate data).
- Material **icons-core only** (Face/Lock/Filled.List are NOT core; use AutoMirrored.List, Star, etc.).
- When toggling the user's real accessibility for a device test, **save the current
  `enabled_accessibility_services` first and restore it after** (the user had 9–10 services).
- `app/build/` is gitignored; debug-signed APK is the release artifact.
