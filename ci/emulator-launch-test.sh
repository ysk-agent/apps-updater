#!/usr/bin/env bash
# Emulator launch test for Apps Updater.
#
# Installs the debug APK, launches MainActivity (plain, then with the
# auto-download extras pointing at a nonexistent item, which must be a
# no-op), and verifies after each step that the app process is alive
# and MainActivity is the top resumed activity. Fails if logcat
# contains a FATAL EXCEPTION for the app. Screenshots of both states
# are captured for visual inspection before handover.
#
# This script exists as a file (not inline in the workflow) because
# reactivecircus/android-emulator-runner executes an inline `script:`
# block line-by-line in separate shells, so shell state and `exit`
# do not work there.
set -u
cd "$(dirname "$0")/.."

APK=app/build/outputs/apk/debug/app-debug.apk
PKG=dev.moriya.appsupdater
FAIL=0

note_fail() { echo "LAUNCH-TEST FAILURE: $1"; FAIL=1; }

top_activity() {
  adb shell dumpsys activity activities 2>/dev/null | grep "topResumedActivity" | head -1
}

check_top() { # $1 = label
  local top
  top=$(top_activity)
  echo "top after $1: $top"
  if echo "$top" | grep -q "$PKG/.MainActivity"; then
    echo "OK: MainActivity is top resumed activity"
  else
    note_fail "MainActivity is not the top resumed activity after $1"
  fi
}

screencap() { # $1 = output png name
  adb exec-out screencap -p > "$1" 2>/dev/null || adb shell screencap -p "/sdcard/$1" && adb pull "/sdcard/$1" "$1" >/dev/null 2>&1
  if [ -s "$1" ]; then echo "screenshot saved: $1 ($(stat -c%s "$1") bytes)"; else echo "WARNING: screenshot $1 empty/missing"; fi
}

adb root || true
sleep 2
adb install -r "$APK" || note_fail "adb install failed"
# Pre-grant the notification permission so the first-launch system
# dialog does not sit on top of MainActivity during the checks.
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

# Light-mode screenshot first (the emulator boots in light mode).
adb shell am start -n $PKG/.MainActivity
sleep 5

# --- Package-probe evidence (v0.2.1) ------------------------------
# The first scan must learn the packages of the files that are not
# in the hardcoded map (findmy-mac.apk, yt-client.apk) by
# range-probing their release assets, logging one
# "package probe <file>: <package> (<n> req, <b> B, <ms>ms)" line
# per file under the UpdaterTiming tag. Poll for the scan's
# completion line, then capture the timing log BEFORE the
# `logcat -c` further down wipes it.
for i in $(seq 1 45); do
  if adb logcat -d -s UpdaterTiming 2>/dev/null | grep -q "scan total"; then break; fi
  sleep 2
done
adb logcat -d -s UpdaterTiming > probe-logcat.txt 2>&1 || true
cat probe-logcat.txt
if grep -q "package probe findmy-mac.apk: dev.moriya.findmymac" probe-logcat.txt && \
   grep -q "package probe yt-client.apk: app.ytclient" probe-logcat.txt; then
  echo "OK: package probe learned findmy-mac + yt-client packages from their assets"
else
  note_fail "package probe did not learn findmy-mac/yt-client packages (see probe-logcat.txt)"
fi

# Auth mode (v0.2.2): every scan logs exactly one
# "github auth token" / "github auth none" line under
# UpdaterTiming. CI sets no token, so "none" must appear (and the
# token value can never appear — there is none, and the app logs
# only the mode).
if grep -q "github auth none" probe-logcat.txt; then
  echo "OK: scan logged its auth mode (github auth none — no token configured in CI)"
else
  note_fail "scan did not log an auth mode line (see probe-logcat.txt)"
fi

screencap screenshot-main-light.png

# Moriya's phones run in dark mode; capture the rest in dark mode.
adb shell cmd uimode night yes || true
sleep 3
adb logcat -c || true

adb shell am start -n $PKG/.MainActivity
sleep 5
PID=$(adb shell pidof $PKG | tr -d '\r\n')
echo "PID after MainActivity: '$PID'"
[ -n "$PID" ] || note_fail "app process not alive after MainActivity launch"
check_top "MainActivity launch"
screencap screenshot-main.png

# Auto-download extras for an item that does not exist: must be a
# no-op (scan finds nothing to match) and must not crash.
adb shell am start -n $PKG/.MainActivity --es auto_project no-such-project --es auto_file no-such-file.apk
sleep 5
PID2=$(adb shell pidof $PKG | tr -d '\r\n')
echo "PID after auto-extras launch: '$PID2'"
[ -n "$PID2" ] || note_fail "app process not alive after auto-extras launch"
check_top "auto-extras launch"
screencap screenshot-auto.png

# --- Foreground-service download test (v0.1.9) -------------------
# Downloads used to die when the app was backgrounded because they
# ran on an activity thread. They now run in DownloadService (a
# foreground service). This section exercises that real path end to
# end: a payload served from the runner host (the emulator reaches
# it as 10.0.2.2; the app's network security config allows
# cleartext to that address for exactly this test), started through
# MainActivity's debug-only test extras.
#
# Success case: the .done.json marker appears and the published
# file's SHA-256 matches the payload's.
# Mismatch case: a wrong expected hash must produce NO marker and
# NO published file (the mismatched bytes are deleted on the spot),
# and a mismatch status must be recorded in the app's prefs.
mkdir -p ci-test-assets
head -c 8388608 /dev/urandom > ci-test-assets/ci-test.apk
TEST_SHA=$(sha256sum ci-test-assets/ci-test.apk | cut -d' ' -f1)
TEST_SIZE=$(stat -c%s ci-test-assets/ci-test.apk)
# NOTE: the server must be killed before this script exits (see the
# kill at the end of this section). A lingering background child
# keeps the emulator-runner step open indefinitely — the first
# v0.1.9 run passed every check in ~10 seconds and then hung the
# step for 50 minutes on exactly this.
python3 -m http.server 8123 --directory ci-test-assets >/dev/null 2>&1 &
HTTP_PID=$!
sleep 1

adb shell am start -n $PKG/.MainActivity \
  --es test_file ci-test.apk \
  --es test_url http://10.0.2.2:8123/ci-test.apk \
  --es test_sha256 "$TEST_SHA" \
  --el test_size "$TEST_SIZE"
DL_OK=0
for i in $(seq 1 30); do
  if adb shell "test -e /sdcard/Download/apps-updater/ci-test.apk.done.json" 2>/dev/null; then
    DL_OK=1; break
  fi
  sleep 2
done
if [ "$DL_OK" = 1 ]; then
  echo "OK: foreground-service download produced the done marker"
else
  note_fail "foreground-service download did not produce the done marker within 60s"
fi
adb pull /sdcard/Download/apps-updater/ci-test.apk ci-test-downloaded.apk >/dev/null 2>&1 || true
GOT_SHA=$(sha256sum ci-test-downloaded.apk 2>/dev/null | cut -d' ' -f1)
if [ "$GOT_SHA" = "$TEST_SHA" ]; then
  echo "OK: downloaded bytes match the payload SHA-256"
else
  note_fail "downloaded bytes SHA-256 mismatch (got '$GOT_SHA')"
fi
adb shell dumpsys activity services $PKG > dumpsys-services.txt 2>&1 || true

BAD_SHA=0000000000000000000000000000000000000000000000000000000000000000
adb shell am start -n $PKG/.MainActivity \
  --es test_file ci-test-bad.apk \
  --es test_url http://10.0.2.2:8123/ci-test.apk \
  --es test_sha256 "$BAD_SHA" \
  --el test_size "$TEST_SIZE"
sleep 15
if adb shell "test -e /sdcard/Download/apps-updater/ci-test-bad.apk.done.json" 2>/dev/null; then
  note_fail "mismatched download produced a done marker (must be discarded)"
else
  echo "OK: mismatched download produced no done marker"
fi
if adb shell "test -e /sdcard/Download/apps-updater/ci-test-bad.apk" 2>/dev/null; then
  note_fail "mismatched download was published to Download/ (must be discarded)"
else
  echo "OK: mismatched download was not published"
fi
if adb shell run-as $PKG cat shared_prefs/appsupdater-prefs.xml 2>/dev/null | grep -q "ci-test-bad.apk"; then
  echo "OK: mismatch status recorded for ci-test-bad.apk"
else
  note_fail "no status recorded for the mismatched download"
fi
kill $HTTP_PID 2>/dev/null || true

# --- Failed-scan resilience (v0.2.2) ------------------------------
# A scan whose API requests fail (rate limit, network down) must
# keep the persisted rows on screen under a failure notice instead
# of wiping the list. Exercise the real path: cut the emulator off
# from api.github.com, relaunch (onNewIntent re-runs the scan),
# and inspect the UI. The scans above have already populated the
# persisted last scan, which is what must survive. Two blocking
# methods, tried in order, each verified before use:
#   1. /etc/hosts entry pointing api.github.com at 127.0.0.1
#      (observed not to take effect in the API 35 default image:
#      ping still resolved the real address after the append);
#   2. airplane mode, which refuses every request device-side.
# If neither verifiably blocks the API, the section skips with a
# note instead of failing.
adb root >/dev/null 2>&1 || true
sleep 1
adb remount >/dev/null 2>&1 || true
adb shell "cp /etc/hosts /sdcard/hosts.bak" >/dev/null 2>&1 || true
adb shell "echo '127.0.0.1 api.github.com' >> /etc/hosts" >/dev/null 2>&1 || true
BLOCKED=""
if adb shell ping -c 1 -W 2 api.github.com 2>/dev/null | head -1 | grep -q "127.0.0.1"; then
  BLOCKED="hosts"
fi
if [ -z "$BLOCKED" ]; then
  adb shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || true
  sleep 2
  if ! adb shell ping -c 1 -W 2 api.github.com >/dev/null 2>&1; then
    BLOCKED="airplane"
  fi
fi
echo "failure-path block method: ${BLOCKED:-none}"
if [ -n "$BLOCKED" ]; then
  # Relaunch (onNewIntent re-runs the scan), then wait for the
  # scan to actually finish before dumping the UI: under airplane
  # mode the blocked requests hang until their timeouts (~13 s
  # observed), so a fixed sleep races the render. The scan logs
  # one "scan total" line under UpdaterTiming when it lands.
  SCANS_BEFORE=$(adb logcat -d -s UpdaterTiming 2>/dev/null | grep -c "scan total")
  adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1
  for i in $(seq 1 24); do
    SCANS_NOW=$(adb logcat -d -s UpdaterTiming 2>/dev/null | grep -c "scan total")
    if [ "$SCANS_NOW" -gt "$SCANS_BEFORE" ]; then break; fi
    sleep 5
  done
  echo "scan total lines before/after blocked relaunch: $SCANS_BEFORE -> $SCANS_NOW"
  sleep 2
  adb shell uiautomator dump /sdcard/window_dump_fail.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window_dump_fail.xml window_dump_fail.xml >/dev/null 2>&1 || true
  if grep -q "更新に失敗しました" window_dump_fail.xml && \
     grep -q "前回の結果を表示しています" window_dump_fail.xml; then
    echo "OK: failed scan showed the failure notice"
  else
    note_fail "failed scan did not show the failure notice (see window_dump_fail.xml)"
  fi
  if grep -q "apps-updater.apk" window_dump_fail.xml; then
    echo "OK: persisted rows still rendered after the failed scan"
  else
    note_fail "persisted rows vanished after the failed scan (see window_dump_fail.xml)"
  fi
  screencap screenshot-failed-scan.png
else
  echo "NOTE: could not block api.github.com in this emulator; failure-path UI check skipped"
fi
adb shell "cp /sdcard/hosts.bak /etc/hosts" >/dev/null 2>&1 || true
adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
sleep 2
# Relaunch so the app re-scans with the API reachable again and
# the sections below see the normal list.
adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1
sleep 5

# Scrolled screenshot (v0.2.1): bring the last projects' rows into
# view so the learned-package rows (yt-client) can be inspected
# visually, not only via logcat.
for i in 1 2 3 4 5; do adb shell input swipe 540 1600 540 500 250 >/dev/null 2>&1; sleep 1; done
screencap screenshot-scrolled.png

adb shell dumpsys activity activities > dumpsys-activities.txt 2>&1 || true
adb logcat -d -v threadtime > logcat.txt 2>&1 || true
adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
adb pull /sdcard/window_dump.xml window_dump.xml >/dev/null 2>&1 || true

if grep -q "FATAL EXCEPTION" logcat.txt && grep -A3 "FATAL EXCEPTION" logcat.txt | grep -q "$PKG"; then
  note_fail "FATAL EXCEPTION for $PKG in logcat"
fi

# Diagnostic (not a failure): the learned package names should
# also appear in the rendered rows of the final UI dump.
for p in dev.moriya.findmymac app.ytclient; do
  if grep -q "$p" window_dump.xml; then
    echo "UI dump contains learned package: $p"
  else
    echo "NOTE: UI dump does not contain learned package: $p"
  fi
done

echo "LAUNCH_TEST_FAIL=$FAIL"
exit $FAIL
