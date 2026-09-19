# Pixel Pulse

Live resource monitor for Google Pixel phones. Built for a Pixel 10 Pro, works on any phone running Android 12 or newer.

The app stays on-device. It does not request internet access or accounts. Per-app network and recently-used apps need optional Usage access.

## Download and sideload

**Install page:** https://jolly-bugle-ntfv.here.now/  
**Direct APK:** https://jolly-bugle-ntfv.here.now/pixel-pulse-1.1.3.apk

That download is an anonymous host and expires about 24 hours after publish unless you claim it here: https://here.now/c/Z_cbVYYPnGhCqA6C

On a Pixel 10 Pro:

1. Open the link on the phone (Chrome).
2. If Chrome warns that the file can harm your device, tap **Download anyway**. That warning is normal for sideloaded APKs.
3. Open the downloaded file.
4. If Android asks to allow installs from Chrome (or Files), allow it, then tap **Install**.
5. Open **Pulse** from the app drawer.

Unknown-app installs live under **Settings → Apps → Special app access → Install unknown apps**.

## What it shows

Live values refresh about once a second while the app is open.

- **Battery** — percent, charging state, charge source (USB / wall / wireless), watts, volts, amps, temperature, health, and a time-to-full or time-left estimate
- **CPU** — overall busy % from kernel counters or `/proc/uptime` (Pixels usually block `/proc/stat`), per-core bars, a sparkline, and live frequencies. The card labels the source so a frozen 0% is not mistaken for idle.
- **Memory** — used / total RAM, plus a breakdown of apps vs file cache vs kernel, and a list of apps/services (tap the Memory card)
- **Network** — Wi-Fi or cellular, live up/down rates, 5-minute timeline, diagnosis, and per-app usage (tap the Network card)
- **Storage** — internal used / free
- **Thermal** — Android thermal status (none → shutdown)

Charge speed labels: Trickle, Standard, Fast charge, Super fast, plus Wireless when applicable. Watts come from `BatteryManager` current × voltage (the same source AccuBattery-style apps use). Pixel phones report current in microamps.

## Permissions

- `ACCESS_NETWORK_STATE` — whether you are on Wi-Fi or cellular
- `ACCESS_WIFI_STATE` — Wi-Fi link speed
- `PACKAGE_USAGE_STATS` — optional. Per-app network history and recently active apps/services.
- `QUERY_ALL_PACKAGES` — resolve app names and icons for those lists

No location permission, so the Wi-Fi SSID is not shown.

Usage access is **not** a normal in-app permission prompt. Android will never show “Allow / Deny” for it. Because this APK is sideloaded from Chrome, Pixel also locks it behind **Restricted settings**:

1. In Pulse, tap **Open Usage access**. If Settings says Restricted setting, that is expected.
2. Tap **Allow restricted settings** (or Settings → Apps → Pulse → ⋮ → Allow restricted settings).
3. Return to **Usage access** and turn **Pulse** on.

Pulse never sends this data off the device.

Android hides other apps' exact RAM sizes from third-party apps. Pulse shows the system-level split (apps, cache, kernel, free) and lists processes it is allowed to see.

## Build from source

Needs JDK 17+ and Android SDK 36.

```bash
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleRelease test
```

The signed APK is written to `app/build/outputs/apk/release/`.

## Notes

- v1.1.3 keeps Pulse from crashing after Usage access is granted (per-app collectors and app icons).
- v1 samples only while Pulse is in the foreground. That keeps the monitor from becoming a battery drain.
- Some fields are best-effort. Android does not let unprivileged apps read every kernel counter on every build.
- The release keystore in `app/keystore/` is for this personal sideload app so updates keep the same signature.
