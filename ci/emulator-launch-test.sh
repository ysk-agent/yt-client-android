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
dismiss_anr() {
  # A system "isn't responding" dialog (the launcher ANR-ing under emulator
  # load) would otherwise sit on top of every screenshot. Tap its "Wait".
  adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || return 0
  adb pull /sdcard/window_dump.xml window_dump.xml >/dev/null 2>&1 || return 0
  grep -q "isn't responding" window_dump.xml || return 0
  local node bounds nums
  node=$(grep -o 'text="Wait"[^>]*' window_dump.xml | head -1)
  bounds=$(echo "$node" | grep -o 'bounds="\[[0-9,]*\]\[[0-9,]*\]"' | head -1)
  nums=$(echo "$bounds" | grep -o '[0-9]\+')
  set -- $nums
  if [ $# -eq 4 ]; then
    local cx=$(( ($1 + $3) / 2 )) cy=$(( ($2 + $4) / 2 ))
    echo "ANR dialog detected; tapping 'Wait' at $cx,$cy"
    adb shell input tap "$cx" "$cy" || true
    sleep 2
  fi
}
screencap() { dismiss_anr; adb exec-out screencap -p > "$1" 2>/dev/null; echo "screenshot: $1 ($(stat -c%s "$1" 2>/dev/null) bytes)"; }

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

adb shell am start -n $PKG/.SearchActivity --es query "FRB"
sleep 15
check_top SearchActivity "SearchActivity launch"
screencap screenshot-search.png

adb shell am start -n $PKG/.DetailActivity --es video_id dQw4w9WgXcQ --es title "Never_Gonna_Give_You_Up" --es channel_name "Rick_Astley" --es channel_id UCuAXFkgswL7iGxMpRfFONQA
sleep 15
check_top DetailActivity "DetailActivity launch"
screencap screenshot-detail.png

adb shell am start -n $PKG/.PlayerActivity --es video_id dQw4w9WgXcQ --es title "Never_Gonna_Give_You_Up"
# Let the embed settle: the player loads asynchronously and its
# postMessage events (ready / state changes / errors) arrive over time.
sleep 35
check_top PlayerActivity "PlayerActivity launch"
screencap screenshot-player.png
# Player verdict evidence. The wrapper page is cross-origin to the embed
# iframe, so its DOM cannot be inspected; instead the player posts events
# (enablejsapi=1) which the app forwards to logcat under YtPlayerProbe,
# and the rendered UI is dumped with uiautomator for the error-card text
# ("This video is unavailable" / "Error code: 152" / 「設定エラー」).
# A positively detected error fails the run — distinguishing the error
# page from a working player is the point of this section. No verdict at
# all only warns: YouTube may simply be unreachable from the emulator.
dismiss_anr
adb shell uiautomator dump /sdcard/player_window_dump.xml >/dev/null 2>&1 || true
adb pull /sdcard/player_window_dump.xml player-window-dump.xml >/dev/null 2>&1 || true
adb logcat -d -s YtPlayerProbe:D YtAdBlock:D > player-probe.txt 2>&1 || true
echo "--- player probe / adblock log ---"; cat player-probe.txt
PLAYER_ERR=0
grep -q '"event":"onError"' player-probe.txt && PLAYER_ERR=1
grep -qiE "Error code|unavailable|設定エラー|利用できません" player-window-dump.xml 2>/dev/null && PLAYER_ERR=1
if [ "$PLAYER_ERR" -eq 1 ]; then
  note_fail "player shows an error card (see player-probe.txt, player-window-dump.xml, screenshot-player.png)"
elif grep -qE 'playerState\\?": ?1|onStateChange' player-probe.txt; then
  echo "OK: player reports playback (no error card)"
else
  echo "PLAYER VERDICT INCONCLUSIVE: no error detected, but no playback state either"
fi
BLOCKED=$(grep -c "blocked #" player-probe.txt || true)
echo "adblock: $BLOCKED blocked request(s) logged so far"

adb shell am start -n $PKG/.ChannelActivity --es channel_id UCvbobBwsNzBY4IV1vLH6Kkg --es channel_name "IG_Japan"
sleep 15
check_top ChannelActivity "ChannelActivity launch"
screencap screenshot-channel.png

adb shell am start -n $PKG/.ChannelListActivity --es group_name "経済" --esa channel_ids UCvbobBwsNzBY4IV1vLH6Kkg,UCuAXFkgswL7iGxMpRfFONQA
sleep 15
check_top ChannelListActivity "ChannelListActivity launch"
screencap screenshot-channellist.png

PID2=$(adb shell pidof $PKG | tr -d '\r\n'); echo "PID end: '$PID2'"
[ -n "$PID2" ] || note_fail "process dead at end"
adb logcat -d -v threadtime > logcat.txt 2>&1 || true
if grep -q "FATAL EXCEPTION" logcat.txt && grep -A3 "FATAL EXCEPTION" logcat.txt | grep -q "$PKG"; then
  note_fail "FATAL EXCEPTION for $PKG in logcat"
fi
echo "LAUNCH_TEST_FAIL=$FAIL"
exit $FAIL
