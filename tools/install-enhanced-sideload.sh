#!/usr/bin/env bash
set -euo pipefail

ROOT="/Users/mac/Developer/NuvioTV_Enhanced"
PACKAGE="com.nuvio.tv.sideload"
DEVICE="${NUVIO_ADB_DEVICE:-192.168.50.83:5555}"
APK="app/build/outputs/apk/sideload/app-armeabi-v7a-sideload.apk"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

cd "$ROOT" || fail "canonical Enhanced repo not found"

[[ "$(git rev-parse --show-toplevel)" == "$ROOT" ]] ||
  fail "wrong Git repository"

[[ "$(git branch --show-current)" == "dev" ]] ||
  fail "canonical Enhanced repo must be on dev"

echo "===== CANONICAL REPO ====="
echo "repo=$ROOT"
echo "branch=$(git branch --show-current)"
git --no-pager log -1 --oneline --decorate

echo
echo "===== WORKTREE ====="
git status --short --branch
git diff --check

[[ -f local.properties ]] ||
  fail "local.properties missing"

grep -q '^CREDIT_ANALYZER_BASE_URL=.' local.properties ||
  fail "CREDIT_ANALYZER_BASE_URL missing"

grep -q '^CREDIT_ANALYZER_TOKEN=.' local.properties ||
  fail "CREDIT_ANALYZER_TOKEN missing"

echo "Credit analyzer configuration: present"

echo
echo "===== BUILD ====="
./gradlew :app:assembleSideload

[[ -f "$APK" ]] ||
  fail "expected APK not found: $APK"

LOCAL_HASH="$(shasum -a 256 "$APK" | awk '{print $1}')"

echo
echo "===== APK ====="
ls -lhT "$APK"
echo "sha256=$LOCAL_HASH"

AAPT="$(
  find "$HOME/Library/Android/sdk/build-tools" -name aapt -type f |
    sort -V |
    tail -1
)"

[[ -n "$AAPT" ]] ||
  fail "aapt not found"

BADGING="$("$AAPT" dump badging "$APK")"

echo "$BADGING" | grep "^package:"
echo "$BADGING" | grep "^native-code:"

echo "$BADGING" |
  grep -q "package: name='com.nuvio.tv.sideload'" ||
  fail "APK package is not $PACKAGE"

echo "$BADGING" |
  grep -q "native-code:.*'armeabi-v7a'" ||
  fail "APK does not contain armeabi-v7a"

echo
echo "===== DEVICE ====="
adb -s "$DEVICE" get-state
adb -s "$DEVICE" shell getprop ro.product.model

echo
echo "===== INSTALL SIDELOAD ONLY ====="
adb -s "$DEVICE" install -r "$APK"

REMOTE_APK="$(
  adb -s "$DEVICE" shell pm path "$PACKAGE" |
    head -1 |
    tr -d '\r' |
    sed 's/^package://'
)"

[[ -n "$REMOTE_APK" ]] ||
  fail "$PACKAGE was not found after installation"

TMP_APK="$(mktemp /tmp/nuvio-enhanced-installed.XXXXXX.apk)"
trap 'rm -f "$TMP_APK"' EXIT

adb -s "$DEVICE" pull "$REMOTE_APK" "$TMP_APK" >/dev/null

INSTALLED_HASH="$(shasum -a 256 "$TMP_APK" | awk '{print $1}')"

echo
echo "===== HASH VERIFICATION ====="
echo "built=$LOCAL_HASH"
echo "installed=$INSTALLED_HASH"

[[ "$LOCAL_HASH" == "$INSTALLED_HASH" ]] ||
  fail "installed APK does not match built APK"

echo
echo "===== AOT SIDELOAD ONLY ====="
adb -s "$DEVICE" shell cmd package compile -f -m speed "$PACKAGE"

echo
echo "===== VERIFIED ====="
echo "package=$PACKAGE"
echo "sha256=$INSTALLED_HASH"
echo "AOT=complete"
