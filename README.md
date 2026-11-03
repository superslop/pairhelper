# PairHelper

**Pair Android Wireless debugging without split-screen, then hand ADB to Termux on port 5555.**

Some phones cannot put the Settings app in split-screen, and the *Pair device with pairing code* dialog is dismissed (and its code invalidated) the moment you switch to another app. That makes the standard Termux pairing flow unusable: by the time Termux is in front, the code is dead.

PairHelper fixes the input problem the way Shizuku does: it posts a notification with an inline **Reply** field. The notification shade overlays Settings without dismissing the dialog, so you type the 6-digit code there while the dialog stays alive underneath.

## What it does

1. **Pair once, from the notification shade.** Tap *Start Pairing*, open Wireless debugging → *Pair device with pairing code*, pull down the shade, reply with the code. Pairing runs over mDNS-discovered `_adb-tls-pairing` using [libadb-android](https://github.com/MuntashirAkon/libadb-android) (SPAKE2 + TLS, via Conscrypt). PairHelper's RSA key is stored in app-private preferences, so this is one-time.
2. **Reconnect after reboot without a code.** Pairing trust survives reboots; only Wireless debugging itself turns off. PairHelper reconnects with its stored key via mDNS `_adb-tls-connect`. After the first connection it also grants itself `WRITE_SECURE_SETTINGS` over its own ADB shell, after which it can turn Wireless debugging back on by itself.
3. **Bridge to Termux / proot on port 5555.** One tap runs the on-device equivalent of `adb tcpip 5555` (`setprop service.adb.tcp.port 5555; stop adbd; start adbd`) over the authorised connection and verifies that port 5555 answers. Termux then only needs:

   ```
   adb connect 127.0.0.1:5555
   ```

   No pairing, no authorisation prompt — plain-TCP adbd is unauthenticated.

> ⚠️ **Security:** plain-TCP adbd on 5555 is unauthenticated to *anything that can reach the port*, not just this phone. Use it on your own hotspot or an offline LAN (for example a travel router with no other clients), never on public or busy Wi-Fi. A reboot closes it.

## Requirements

- Android 11+ (Wireless debugging). Built and targeted for Android 15 (API 35).
- A Wi-Fi connection (or hotspot) while Wireless debugging is enabled — Android requires one. It does not need internet access.

## Build

Built in GitHub Actions — every push to `main` produces a `pairhelper-debug` APK artifact (Actions → Build → latest run).

Locally:

```
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, JDK 17, version catalog in `gradle/libs.versions.toml`.

## Status

v0.1.0. The pairing, mDNS discovery, notification-input and wireless-debugging self-grant code is carried over from the upstream fork base and is field-proven there; the PairHelper-specific parts (rebrand, targetSdk 35 foreground-service typing, the 5555 bridge) are CI-built but **not yet device-verified** on a physical device. The 5555 bridge in particular depends on the shell user being allowed the `setprop`/`stop adbd` sequence on a given build; if it fails, the app reports that port 5555 did not come up instead of claiming success.

## Origin and licence

PairHelper is a fork of [sam1am/anyapk](https://github.com/sam1am/anyapk), reduced to its pairing/connection layer: the APK-installer, bundle, updater and settings code was removed, the package was renamed to `io.github.superslop.pairhelper`, the toolchain was modernised, and the 5555 bridge was added. Upstream had no licence file at fork time (2026-11-03); this fork is maintained for personal use, and libadb-android is used under its Apache-2.0 option.
