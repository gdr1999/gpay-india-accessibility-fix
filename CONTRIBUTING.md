# Contributing to GPay Ready

Thanks for taking a look! This is a small, single-purpose, no-root utility and contributions are
very welcome — especially test reports from **other phones / Android versions** (it's only been
verified on a Pixel 11, Android 17 QPR3 Beta 3).

> **Transparency:** this app was "vibe-coded" with [Claude Code](https://www.anthropic.com/claude-code).
> The author doesn't know every internal detail, so clear PRs and reviews from people who know
> Android internals are especially appreciated.

## Fork & build

1. **Fork** the repo on GitHub (top-right), or with the CLI:
   ```bash
   gh repo fork gdr1999/gpay-india-accessibility-fix --clone
   ```
2. Build it (needs JDK 17+ and the Android SDK, `ANDROID_HOME` set):
   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell pm grant dev.gpayready android.permission.WRITE_SECURE_SETTINGS
   ```
3. Make your change on a branch, then open a Pull Request against `main`.

## Where things live

- **`app/src/main/java/dev/gpayready/GpayFix.kt`** — all the core logic (what gets toggled, saved,
  killed, opened). Read this first.
- **`MainActivity.kt`** — the Compose UI. **`GpayWatchService.kt`** — the Usage-Access automation.
- Everything else is small glue: `DispatchActivity` (trampoline), `AppTile` (Quick Settings tiles),
  `IconPacks` (home-icon icon packs), `FixTile`, `CreateShortcutActivity`, `RunReceiver`, `BootReceiver`.

## Ground rules

- **No `INTERNET` permission.** The app must never send data anywhere — keep it that way.
- **No root, no Shizuku, no wireless/USB debugging left on** (banking apps read that state).
- It only toggles **the user's own** accessibility / developer settings to the state banking apps
  ask for, and restores them. It must never automate or drive a banking app's UI.
- Prefer documented Android APIs. Call out anything that relies on a hidden/edge behaviour.

## Good first contributions

- Test on your device + Google Pay version and open an issue with the result.
- Improve iconography, accessibility labels, or onboarding copy.
- Harden the Usage-Access automation (restore timing, edge cases).

## Reporting issues

Use **Issues** for bugs/ideas and **Discussions** for questions. Please include your phone model,
Android version, and the app/Google-Pay version when reporting a block or a restore problem.
