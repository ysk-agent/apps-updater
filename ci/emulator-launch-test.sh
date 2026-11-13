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

adb shell dumpsys activity activities > dumpsys-activities.txt 2>&1 || true
adb logcat -d -v threadtime > logcat.txt 2>&1 || true
adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
adb pull /sdcard/window_dump.xml window_dump.xml >/dev/null 2>&1 || true

if grep -q "FATAL EXCEPTION" logcat.txt && grep -A3 "FATAL EXCEPTION" logcat.txt | grep -q "$PKG"; then
  note_fail "FATAL EXCEPTION for $PKG in logcat"
fi

echo "LAUNCH_TEST_FAIL=$FAIL"
exit $FAIL
