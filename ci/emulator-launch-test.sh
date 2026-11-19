#!/usr/bin/env bash
# Emulator launch test for YT Client. Launches every activity, checks the
# top resumed activity and process liveness, captures screenshots, and
# fails on any FATAL EXCEPTION for the app.
set -u
cd "$(dirname "$0")/.."
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=app.ytclient
FAIL=0
note_fail() { echo "LAUNCH-TEST FAILURE: $1"; FAIL=1; }
top_activity() { adb shell dumpsys activity activities 2>/dev/null | grep "topResumedActivity" | head -1; }
check_top() {
  local top; top=$(top_activity); echo "top after $2: $top"
  if echo "$top" | grep -q "$PKG/.$1"; then echo "OK: $1 is top"; else note_fail "$1 not top after $2"; fi
}
screencap() { adb exec-out screencap -p > "$1" 2>/dev/null; echo "screenshot: $1 ($(stat -c%s "$1" 2>/dev/null) bytes)"; }

adb root || true
sleep 2
adb install -r "$APK" || note_fail "adb install failed"
adb shell cmd uimode night yes || true
sleep 3
adb logcat -c || true

adb shell am start -n $PKG/.MainActivity
sleep 20
PID=$(adb shell pidof $PKG | tr -d '\r\n'); echo "PID main: '$PID'"
[ -n "$PID" ] || note_fail "process dead after MainActivity"
check_top MainActivity "MainActivity launch"
screencap screenshot-main.png

adb shell am start -n $PKG/.SearchActivity --es query "Federal Reserve"
sleep 15
check_top SearchActivity "SearchActivity launch"
screencap screenshot-search.png

adb shell am start -n $PKG/.DetailActivity --es video_id dQw4w9WgXcQ --es title "Rick Astley - Never Gonna Give You Up" --es channel_name "Rick Astley" --es channel_id UCuAXFkgswL7iGxMpRfFONQA
sleep 15
check_top DetailActivity "DetailActivity launch"
screencap screenshot-detail.png

adb shell am start -n $PKG/.ChannelActivity --es channel_id UCvbobBwsNzBY4IV1vLH6Kkg --es channel_name "IG Japan"
sleep 15
check_top ChannelActivity "ChannelActivity launch"
screencap screenshot-channel.png

PID2=$(adb shell pidof $PKG | tr -d '\r\n'); echo "PID end: '$PID2'"
[ -n "$PID2" ] || note_fail "process dead at end"
adb logcat -d -v threadtime > logcat.txt 2>&1 || true
if grep -q "FATAL EXCEPTION" logcat.txt && grep -A3 "FATAL EXCEPTION" logcat.txt | grep -q "$PKG"; then
  note_fail "FATAL EXCEPTION for $PKG in logcat"
fi
echo "LAUNCH_TEST_FAIL=$FAIL"
exit $FAIL
