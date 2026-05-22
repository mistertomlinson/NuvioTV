# Nuvio TV — Sideload Build Guide

This guide lets you build and install Nuvio TV yourself so you can run it
alongside the official beta. The sideload build has no minification and is
AOT compiled on-device for native-level performance.

---

## Prerequisites

### 1. Android Studio
Download and install Android Studio from:
https://developer.android.com/studio

This gives you everything needed: ADB, build tools, JDK, and SDK manager.
You do not need to open any project in Android Studio — just install it.

### 2. Enable ADB on your Android TV
On your TV:
- Go to Settings > Device Preferences > About
- Click Build 7 times to enable Developer Options
- Go to Settings > Device Preferences > Developer Options
- Enable USB Debugging (and Network Debugging for WiFi)

### 3. Connect ADB
WiFi (recommended):
  adb connect <YOUR_TV_IP>:5555

Find your TV IP at: Settings > Device Preferences > About > Network

Verify it works:
  adb devices

You should see your TV listed as 'device' (not 'unauthorized').

---

## Build & Install

### macOS / Linux

  cd NuvioTV_420
  chmod +x build_sideload.sh
  ./build_sideload.sh

### Windows

Open Command Prompt or PowerShell in the NuvioTV_420 folder:

  gradlew.bat assembleSideload
  adb install -r app\build\outputs\apk\sideload\app-universal-sideload.apk
  adb shell cmd package compile -m speed -f com.nuvio.tv.sideload

---

## What the script does

1. Detects your Android SDK location automatically
2. Generates local.properties with all required config
3. Builds the sideload APK (no minification, ~5-15 min first run)
4. Installs it to your connected TV
5. AOT compiles it on-device for maximum performance

---

## Notes

- App ID is com.nuvio.tv.sideload — runs alongside the official beta
- The app name appears identical to the official app in your launcher
- First build downloads Gradle dependencies (~500MB) — subsequent builds are faster
- If the build fails, make sure Android Studio has finished SDK setup
  (Android Studio > SDK Manager > install Android 14/15 SDK)

---

## Troubleshooting

adb: command not found
  Add platform-tools to your PATH:
  Mac:     export PATH=$PATH:~/Library/Android/sdk/platform-tools
  Windows: Add C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools to System PATH

INSTALL_FAILED_UPDATE_INCOMPATIBLE
  Uninstall the existing build first:
  adb uninstall com.nuvio.tv.sideload

Device shows 'unauthorized'
  Check your TV screen for an ADB debugging prompt and accept it.

Build fails with SDK error
  Open Android Studio > SDK Manager and install:
  - Android SDK Platform 35 or 36
  - Android SDK Build-Tools 35+
