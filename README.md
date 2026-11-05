# GPay Ready

A tiny, no-root Android utility that fixes **Google Pay India refusing payments when a legitimate
accessibility app is enabled** (MacroDroid, custom launchers, side-bar/gesture apps, etc.).

Recent Google Pay builds flag any accessibility service that *looks like* an automation tool and show
**"Something went wrong and your payment can't be completed"** / **"Close screen sharing app"** — even
though those apps are legitimate and the user installed them on purpose. The only reliable fix is to
turn accessibility **off while you pay** and back **on afterwards**, which is exactly the state Google
Pay's check wants. This app does that in one tap, with no root and no Shizuku.

> Tested on a **Pixel 11, Android 17 (QPR3 Beta 3)** — works perfectly there. **Not yet tried on other
> phones / Android versions.** It uses standard Android settings, so it should work on other devices,
> but please report back.

## ⚠️ Required one-time setup (needs a computer with adb, once)

This app **cannot do anything until you grant it one permission over adb**. There is **no on-device way**
to grant `WRITE_SECURE_SETTINGS` — you must connect the phone to a computer **once** and run:

```
adb shell pm grant dev.gpayready android.permission.WRITE_SECURE_SETTINGS
```

- **No root, no Shizuku.** It's a one-time command; you never need the computer again afterwards.
- It's the same documented grant the "Dev Options toggler" apps use. Revoke any time with `pm revoke`.
- Everything else (Usage Access for the automation, the Quick Settings tile) is granted **on-device**, no computer.

Without this grant the accessibility toggle silently does nothing.

## What it does

One tap (button, Quick Settings tile, or from MacroDroid/Tasker/launcher) runs:

1. **Turn accessibility OFF** — saves your currently-enabled accessibility services, then clears them.
2. **Stop Google Pay** and **Stop Play Services** — kills their cached background processes so they
   re-check cleanly on next open.
3. **Open Google Pay** — ready to pay.

Afterwards, **Restore accessibility** (button / QS tile / automation) turns your services back on.

### Automation (optional, Usage Access — not accessibility)

Enable **Auto** in the app and it watches the foreground app via **Usage Access**: accessibility is
turned **off the moment Google Pay comes to the front**, and **restored the moment you leave it**. So
your automation apps are only off for the few seconds you're actually in Google Pay.

## How it works (no root, no Shizuku, no debugging left on)

| Step | Mechanism | Permission |
|------|-----------|------------|
| Accessibility off/on | writes the `enabled_accessibility_services` secure setting (saved & restored) | `WRITE_SECURE_SETTINGS` — granted **once over adb** |
| Stop GPay / Play Services | `ActivityManager.killBackgroundProcesses()` | `KILL_BACKGROUND_PROCESSES` (normal, auto-granted) |
| Auto on open/close | `UsageStatsManager` foreground-app events | Usage Access (granted in Settings) |

No Shizuku and no wireless/USB debugging are used, because banking apps also read developer/ADB state.
`killBackgroundProcesses` is **not** a full `am force-stop` (it won't stop a running foreground
service), but turning accessibility off is the actual block-clear; the kills just nudge the cached
state.

## Setup

1. Install the APK (`./gradlew :app:assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
2. Grant the one-time permission over adb (no root):

   ```
   adb shell pm grant dev.gpayready android.permission.WRITE_SECURE_SETTINGS
   ```

3. Open the app → **Fix & open Google Pay**. Optionally **Add Quick Settings tile** and/or turn on
   **Auto** (it will ask for Usage Access).

## Use from MacroDroid / Tasker / launchers

- **MacroDroid → Action → Launch Shortcut → GPay Ready** (uses the `CREATE_SHORTCUT` picker).
- **Broadcast:** `adb shell am broadcast -n dev.gpayready/.RunReceiver -a dev.gpayready.RUN`
  (restore with `-a dev.gpayready.RESTORE`). The headless parts always run; opening Google Pay is most
  reliable via the Launch Shortcut path.

## Honest notes

- This does **not** bypass any security. It toggles **your own** accessibility services — which you
  control — to the state Google Pay asks for, then restores them. Use it on your own device.
- If Google changes the check, this may need updating. PRs welcome.

## License

MIT — see [LICENSE](LICENSE).
