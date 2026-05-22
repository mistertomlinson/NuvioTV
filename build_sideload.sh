#!/bin/bash
set -e

echo "================================================"
echo "  Nuvio TV — Sideload Build & Install Script"
echo "================================================"
echo ""

# ── 1. Detect Android SDK ──────────────────────────────────────────────────
detect_sdk() {
    # Common locations
    for candidate in \
        "$ANDROID_HOME" \
        "$ANDROID_SDK_ROOT" \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk" \
        "/usr/local/lib/android/sdk" \
        "/opt/android-sdk"; do
        if [ -n "$candidate" ] && [ -d "$candidate/platform-tools" ]; then
            echo "$candidate"
            return 0
        fi
    done
    return 1
}

echo "→ Detecting Android SDK..."
SDK_PATH=$(detect_sdk || true)

if [ -z "$SDK_PATH" ]; then
    echo ""
    echo "ERROR: Android SDK not found automatically."
    echo "Please enter your Android SDK path (e.g. /Users/yourname/Library/Android/sdk):"
    read -r SDK_PATH
    if [ ! -d "$SDK_PATH/platform-tools" ]; then
        echo "ERROR: Invalid SDK path. Exiting."
        exit 1
    fi
fi
echo "✓ SDK found at: $SDK_PATH"

# ── 2. Generate local.properties ──────────────────────────────────────────
echo ""
echo "→ Writing local.properties..."
cat > local.properties << PROPS
sdk.dir=$SDK_PATH
SUPABASE_URL=https://dpyhjjcoabcglfmgecug.supabase.co
SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRweWhqamNvYWJjZ2xmbWdlY3VnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzA3ODYyNDcsImV4cCI6MjA4NjM2MjI0N30.U-3QSNDdpsnvRk_7ZL419AFTOtggHJJcmkodxeXjbkg
AVATAR_PUBLIC_BASE_URL=
TRAKT_REDIRECT_URI=urn:ietf:wg:oauth:2.0:oob
TMDB_API_KEY=8c19bf2233c8821cb53a945902eefed6
TRAKT_CLIENT_ID=d8d3c82a1d4837ad2f33c0f7ccb9038e52bb36b6487c16a30146893f02e5f374
TRAKT_CLIENT_SECRET=833f17e098b9c3ba13a54f81cbaf5ba749782a0281075d374b9e5f398c962a4d
TV_LOGIN_WEB_BASE_URL=https://nuvioapp.space/tv-login
USE_DEBUG_RELEASE_SIGNING=true
NUVIO_RELEASE_KEY_ALIAS=nuvio
NUVIO_RELEASE_KEY_PASSWORD=android
NUVIO_RELEASE_STORE_PASSWORD=android
PROPS
echo "✓ local.properties written"

# ── 3. Check ADB + device ─────────────────────────────────────────────────
echo ""
echo "→ Checking ADB..."
ADB="$SDK_PATH/platform-tools/adb"
if [ ! -f "$ADB" ]; then
    ADB=$(command -v adb || true)
fi
if [ -z "$ADB" ]; then
    echo "ERROR: adb not found. Make sure Android SDK platform-tools is installed."
    exit 1
fi
echo "✓ adb found: $ADB"

echo ""
echo "→ Waiting for device..."
echo "  Make sure your Android TV is connected via USB or ADB over WiFi."
echo "  For WiFi: run 'adb connect <TV_IP>:5555' first, then press Enter."
echo ""
read -rp "Press Enter when your device is ready..."

DEVICE_COUNT=$("$ADB" devices | grep -c "device$" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "ERROR: No device found. Run 'adb devices' to check connection."
    exit 1
elif [ "$DEVICE_COUNT" -gt 1 ]; then
    echo ""
    echo "Multiple devices found:"
    "$ADB" devices
    echo ""
    read -rp "Enter device serial (from list above): " DEVICE_SERIAL
    ADB="$ADB -s $DEVICE_SERIAL"
else
    DEVICE_SERIAL=$("$ADB" devices | grep "device$" | awk '{print $1}')
    ADB="$ADB -s $DEVICE_SERIAL"
    echo "✓ Device: $DEVICE_SERIAL"
fi

# ── 4. Build sideload variant ─────────────────────────────────────────────
echo ""
echo "→ Building Nuvio sideload APK (this may take 5–15 minutes)..."
echo "  No minification. Optimized for AOT compilation."
echo ""
./gradlew assembleSideload --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g" 2>&1 | grep "^e:\|FAILED\|BUILD\|warning:" | tail -20

if [ ! -f "app/build/outputs/apk/sideload/app-universal-sideload.apk" ]; then
    echo ""
    echo "ERROR: Build failed or APK not found. Check output above."
    exit 1
fi
echo ""
echo "✓ Build complete"

# ── 5. Install ────────────────────────────────────────────────────────────
echo ""
echo "→ Installing APK..."
$ADB install -r app/build/outputs/apk/sideload/app-universal-sideload.apk
echo "✓ Installed"

# ── 6. AOT compile ────────────────────────────────────────────────────────
echo ""
echo "→ AOT compiling (this makes the app feel native-fast)..."
$ADB shell cmd package compile -m speed -f com.nuvio.tv.sideload
echo "✓ AOT compile complete"

# ── Done ──────────────────────────────────────────────────────────────────
echo ""
echo "================================================"
echo "  ✓ Nuvio is installed and ready!"
echo "  App ID: com.nuvio.tv.sideload"
echo "  Find it in your launcher alongside the official app."
echo "================================================"
