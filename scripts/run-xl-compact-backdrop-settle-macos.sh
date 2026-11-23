#!/usr/bin/env bash
# Experimental one-shot installer, intended to be launched detached from
# Termius on the iMac. Report goes to the existing iPhone Copy From VPS shortcut.
set -u

ROOT="$HOME/Developer/NuvioTV_XLCompactLayout_20261122"
DEVICE="192.168.50.83:5555"
APK="$ROOT/app/build/outputs/apk/sideload/app-armeabi-v7a-sideload.apk"
BRANCH="experiment/xl-compact-backdrop-settle-20261123"
CODE_SHA="37700477f0d231561b90a2a8dc47de81bb2901ac"
LOG="/tmp/nuvio-xl-backdrop-settle-build.log"
REPORT="/tmp/nuvio-xl-backdrop-settle-result.txt"
VPS="ubuntu@129.153.176.7"

publish() {
    local status="$1" rc="$2"
    {
        echo "===== COPY FROM HERE ====="
        echo "ACTION=XL_COMPACT_BACKDROP_SETTLE_AB_TEST"
        echo "STATUS=$status"
        echo "EXIT_CODE=$rc"
        echo "BRANCH=$BRANCH"
        if [ -d "$ROOT/.git" ] || [ -f "$ROOT/.git" ]; then
            (cd "$ROOT" && echo "COMMIT=$(git rev-parse --short HEAD 2>/dev/null)")
        fi
        echo "--- BUILD AND TEST HIGHLIGHTS ---"
        if [ -f "$LOG" ]; then
            grep -E '(^> Task .* FAILED|^.*Test > .*FAILED|[0-9]+ tests completed|BUILD (SUCCESSFUL|FAILED)|^(Success|Failure)|^FAILURE:|^STOP:|^AOT=|^INSTALL=|^APK=|^TESTS=|^COMMIT=)' "$LOG" | tail -n 45 || true
            echo "--- FINAL LOG TAIL ---"
            tail -n 35 "$LOG"
        fi
        echo "===== STOP COPYING HERE ====="
    } > "$REPORT"
    ssh -o BatchMode=yes -o ConnectTimeout=8 "$VPS" 'sudo -n tee /var/www/seasonal-output/latest-output.txt >/dev/null' < "$REPORT" || echo "REPORT_TO_VPS_FAILED; result at $REPORT"
}

run() (
    set -euo pipefail
    cd "$ROOT"
    test "$(git branch --show-current)" = "$BRANCH" || { echo "STOP: wrong branch"; exit 1; }
    test -z "$(git status --porcelain)" || { echo "STOP: local changes"; exit 1; }
    git merge-base --is-ancestor "$CODE_SHA" HEAD || { echo "STOP: experimental code not present"; exit 1; }
    echo "COMMIT=$(git rev-parse HEAD)"
    echo "--- CLEAN + COMPACT XL TESTS ---"
    ./gradlew :app:clean :app:testSideloadUnitTest --tests '*ModernXlSceneTransitionTest' --no-build-cache --no-configuration-cache
    echo "TESTS=PASS"
    rm -f "$APK"
    local_start=$(date +%s)
    echo "--- FULL TESTS + APK ---"
    ./gradlew :app:testSideloadUnitTest :app:assembleSideload --no-build-cache --no-configuration-cache
    test -s "$APK" || { echo "STOP: APK missing"; exit 1; }
    test "$(stat -f %m "$APK")" -ge "$local_start" || { echo "STOP: stale APK"; exit 1; }
    echo "APK=FRESH"
    echo "--- INSTALL DOWNSTAIRS ---"
    adb connect "$DEVICE"
    adb -s "$DEVICE" install -r "$APK"
    echo "INSTALL=PASS"
    echo "--- AOT ---"
    adb -s "$DEVICE" shell cmd package compile -f -m speed com.nuvio.tv.sideload
    echo "AOT=PASS"
    adb -s "$DEVICE" shell am force-stop com.nuvio.tv.sideload
    echo "RESULT=TEST_BUILD_INSTALL_AOT_PASS"
)

: > "$LOG"
publish "RUNNING" 0
run > "$LOG" 2>&1
rc=$?
if [ "$rc" -eq 0 ]; then
    publish "SUCCESS" "$rc"
else
    publish "FAILED" "$rc"
fi
exit "$rc"
