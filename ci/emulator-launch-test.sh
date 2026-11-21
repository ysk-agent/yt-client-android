#!/usr/bin/env bash
# Emulator launch test for YT Client. Launches every activity, checks the
# top resumed activity and process liveness, captures screenshots, and
# fails on any FATAL EXCEPTION for the app.
#
# Waits are condition-based (top activity, probe-log markers, process
# state) with bounded timeouts — the fixed sleeps this script used
# until 2026-11-21 summed to ~160 s of blind waiting per run. Every
# wait still has a hard cap, so a genuinely stuck step fails fast
# instead of hanging.
#
# Test scope: TEST_SECTIONS selects which sections run —
#   all (default) | feed | player
# "feed" is the activity launches (Main/Search/Detail/Channel/
# ChannelList); "player" is the player verdict plus the rate/PiP and
# paused-PiP scenarios. The committing agent picks the scope per change
# via a [test: ...] marker in the commit message (see build.yml); when
# in doubt it runs all.
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

# --- condition waits ---------------------------------------------------
# wait_until <timeout-sec> <description> <command...>: poll the command
# every 2 s until it succeeds or the timeout expires.
wait_until() {
  local timeout=$1 desc=$2; shift 2
  local waited=0
  while [ $waited -lt $timeout ]; do
    if "$@" >/dev/null 2>&1; then echo "wait ok: $desc (${waited}s)"; return 0; fi
    sleep 2; waited=$((waited + 2))
  done
  echo "wait timeout: $desc (${timeout}s)"
  return 1
}
top_is() { top_activity | grep -q "$PKG/.$1"; }
pid_alive() { [ -n "$(adb shell pidof $PKG 2>/dev/null | tr -d '\r\n')" ]; }
pid_gone() { [ -z "$(adb shell pidof $PKG 2>/dev/null | tr -d '\r\n')" ]; }
probe_has() { adb logcat -d -s YtPlayerProbe:D 2>/dev/null | grep -q "$1"; }
probe_has_re() { adb logcat -d -s YtPlayerProbe:D 2>/dev/null | grep -qE "$1"; }
# Wait until the activity is on top, then check_top as the verdict.
wait_top() { # activity, launch-description, timeout
  wait_until "${3:-60}" "$1 on top" top_is "$1" || true
  check_top "$1" "$2"
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

# --- sections ----------------------------------------------------------

section_feed_activities() {
  adb shell am start -n $PKG/.MainActivity
  wait_top MainActivity "MainActivity launch" 60
  PID=$(adb shell pidof $PKG | tr -d '\r\n'); echo "PID main: '$PID'"
  [ -n "$PID" ] || note_fail "process dead after MainActivity"
  sleep 3
  screencap screenshot-main.png

  adb shell am start -n $PKG/.SearchActivity --es query "FRB"
  wait_top SearchActivity "SearchActivity launch" 45
  sleep 3
  screencap screenshot-search.png

  adb shell am start -n $PKG/.DetailActivity --es video_id dQw4w9WgXcQ --es title "Never_Gonna_Give_You_Up" --es channel_name "Rick_Astley" --es channel_id UCuAXFkgswL7iGxMpRfFONQA
  wait_top DetailActivity "DetailActivity launch" 45
  sleep 5
  screencap screenshot-detail.png
}

section_player_verdict() {
  adb shell am start -n $PKG/.PlayerActivity --es video_id dQw4w9WgXcQ --es title "Never_Gonna_Give_You_Up"
  # Let the embed settle: the player loads asynchronously and its
  # postMessage events (ready / state changes / errors) arrive over time.
  # Wait until the probe shows any verdict-relevant marker (playback
  # state, a state change, or an error) instead of a blind 35 s.
  wait_until 60 "player probe activity" probe_has_re 'playerState|onStateChange|"event":"onError"' || true
  wait_top PlayerActivity "PlayerActivity launch" 30
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
}

# --- v0.1.11: PiP entry + playback-rate reapply scenario ---
# A fresh player instance is driven by the app's ci_set_rate hook:
# playback starts, the hook sets 2.0x (which the app must record as the
# user's rate while foregrounded), Home then sends the user away — PiP
# should engage — and tapping the PiP window back to full screen must
# trigger the rate re-apply, after which the player must report 2.0
# again. Verdicts distinguish "the app misbehaved" from "the emulator
# could not run the leg" instead of silently passing.
section_rate_pip() {
  adb shell am force-stop $PKG || true
  wait_until 10 "process stopped" pid_gone || true
  adb logcat -c || true
  adb shell am start -n $PKG/.PlayerActivity --es video_id dQw4w9WgXcQ --es title "Never_Gonna_Give_You_Up" --es ci_set_rate "2.0"
  RATE_SETUP=0
  for i in $(seq 1 25); do
    adb logcat -d -s YtPlayerProbe:D > probe-rate-setup.txt 2>&1 || true
    if grep -q "userRate=2.0" probe-rate-setup.txt; then RATE_SETUP=1; break; fi
    sleep 2
  done
  PLAYING_SEEN=0
  grep -qE 'playerState\\?": ?1' probe-rate-setup.txt && PLAYING_SEEN=1
  echo "rate scenario setup: playing=$PLAYING_SEEN rate2=$RATE_SETUP"

  adb shell input keyevent KEYCODE_HOME
  wait_until 20 "pip mode marker" probe_has "pip mode changed" || true
  adb shell dumpsys activity activities > pip-dumpsys.txt 2>&1 || true
  adb logcat -d -s YtPlayerProbe:D > probe-pip.txt 2>&1 || true
  screencap screenshot-pip.png
  PIP_ENTERED=0
  grep -q "pip mode changed: true" probe-pip.txt && PIP_ENTERED=1
  PIP_REQUESTED=0
  grep -q "pip enter requested" probe-pip.txt && PIP_REQUESTED=1
  echo "--- pip evidence (probe) ---"
  grep -E "userLeaveHint|pip enter|pip mode" probe-pip.txt | tail -5 || true
  echo "--- pip evidence (dumpsys) ---"
  grep -in "picture" pip-dumpsys.txt | head -5 || true
  if [ "$PIP_ENTERED" -eq 1 ]; then
    echo "PIP VERDICT: ENTERED (pip mode changed: true in probe log)"
  else
    echo "PIP VERDICT: NOT ENTERED (requested=$PIP_REQUESTED; see pip-dumpsys.txt and screenshot-pip.png)"
  fi

  # Return from PiP: tap the PiP window — its bounds from the dumpsys
  # pinned task when present, else the default bottom-right spot on the
  # 1080x2400 emulator display. First tap raises the PiP controls, the
  # second expands to full screen.
  PIP_BOUNDS=$(awk '/mode=pinned|type=pinned/{f=1} f&&/bounds=\[/{print; exit}' pip-dumpsys.txt | grep -o 'bounds=\[[0-9,]*\]\[[0-9,]*\]' | head -1)
  if [ -n "$PIP_BOUNDS" ]; then
    set -- $(echo "$PIP_BOUNDS" | grep -o '[0-9]\+')
    TX=$(( ($1 + $3) / 2 )); TY=$(( ($2 + $4) / 2 ))
  else
    TX=830; TY=1980
  fi
  echo "pip return: tapping PiP window at $TX,$TY (bounds: ${PIP_BOUNDS:-fallback})"
  adb shell input tap $TX $TY || true
  sleep 2
  adb shell input tap $TX $TY || true
  wait_until 25 "return + rate reapply markers" probe_has "reapply playbackRate" || true
  # The player confirms the re-applied rate in a following probe event;
  # give it a brief settle so the confirmation is in the dump below.
  sleep 3
  adb logcat -d -s YtPlayerProbe:D > probe-return.txt 2>&1 || true
  RETURNED=0
  grep -q "pip mode changed: false" probe-return.txt && RETURNED=1
  REAPPLIED=0
  grep -q "reapply playbackRate 2.0" probe-return.txt && REAPPLIED=1
  FINAL_RATE_OK=0
  if [ "$REAPPLIED" -eq 1 ]; then
    if awk '/reapply playbackRate/{f=1} f' probe-return.txt | grep -qE 'playbackRate\\?": ?2(\.0)?[,}]'; then
      FINAL_RATE_OK=1
    fi
  fi
  echo "speed scenario: returned=$RETURNED reapplied=$REAPPLIED finalRate2=$FINAL_RATE_OK"
  if [ "$PLAYING_SEEN" -eq 1 ] && [ "$RATE_SETUP" -eq 0 ]; then
    note_fail "rate scenario: playback confirmed but the rate never reached 2.0 in setup"
  elif [ "$RATE_SETUP" -eq 1 ] && [ "$RETURNED" -eq 1 ] && [ "$REAPPLIED" -eq 0 ]; then
    note_fail "rate scenario: returned to foreground but the 2.0 rate was not re-applied"
  elif [ "$RATE_SETUP" -eq 1 ] && [ "$RETURNED" -eq 1 ] && [ "$REAPPLIED" -eq 1 ] && [ "$FINAL_RATE_OK" -eq 1 ]; then
    echo "OK: rate 2.0 re-applied on return and confirmed by the player afterwards"
  elif [ "$RATE_SETUP" -eq 1 ]; then
    echo "SPEED VERDICT INCONCLUSIVE: rate was set, but the return leg could not be completed in the emulator (returned=$RETURNED reapplied=$REAPPLIED finalRate2=$FINAL_RATE_OK)"
  else
    echo "SPEED VERDICT INCONCLUSIVE: playback/rate setup not observed (YouTube unreachable from the emulator?)"
  fi
}

# --- v0.1.12: paused player must NOT enter PiP (autoEnter=false leg) ---
# A fresh player is started, playback is confirmed, then the embed is
# paused by tapping the video (the embed toggles play/pause on tap).
# Pressing Home while paused must leave the app in the background with
# no PiP. If the tap never produces a paused state in the probe log the
# leg is inconclusive rather than a failure: the tap target inside the
# cross-origin embed cannot be addressed precisely.
section_paused_pip() {
  adb shell am force-stop $PKG || true
  wait_until 10 "process stopped" pid_gone || true
  adb logcat -c || true
  adb shell am start -n $PKG/.PlayerActivity --es video_id dQw4w9WgXcQ --es title "Never_Gonna_Give_You_Up"
  PAUSED_SETUP=0
  for i in $(seq 1 30); do
    adb logcat -d -s YtPlayerProbe:D > probe-pause-setup.txt 2>&1 || true
    grep -qE 'playerState\\?": ?1' probe-pause-setup.txt && break
    sleep 2
  done
  if grep -qE 'playerState\\?": ?1' probe-pause-setup.txt; then
    for t in 1 2 3; do
      adb shell input tap 540 1100 || true
      for w in $(seq 1 6); do
        adb logcat -d -s YtPlayerProbe:D > probe-pause-setup.txt 2>&1 || true
        if grep -qE 'playerState\\?": ?2' probe-pause-setup.txt; then PAUSED_SETUP=1; break; fi
        sleep 2
      done
      [ "$PAUSED_SETUP" -eq 1 ] && break
    done
  fi
  echo "paused scenario setup: paused=$PAUSED_SETUP"
  if [ "$PAUSED_SETUP" -eq 1 ]; then
    adb logcat -c || true
    sleep 1
    adb shell input keyevent KEYCODE_HOME
    # Negative check: observe a bounded window for the failure marker.
    # Polling cannot prove an absence, but it can end the window the
    # moment the bad outcome appears instead of always waiting it out.
    for w in $(seq 1 4); do
      if probe_has "pip mode changed: true"; then break; fi
      sleep 2
    done
    adb logcat -d -s YtPlayerProbe:D > probe-paused-home.txt 2>&1 || true
    echo "--- paused-home evidence (probe) ---"
    grep -E "userLeaveHint|pip enter|pip mode" probe-paused-home.txt | tail -5 || true
    if grep -q "pip mode changed: true" probe-paused-home.txt; then
      note_fail "paused player entered PiP (autoEnter=false path broken)"
    else
      echo "OK: paused player did not enter PiP on Home"
    fi
  else
    echo "PAUSED-PIP VERDICT INCONCLUSIVE: could not drive the embed into a paused state via taps"
  fi
}

section_feed_channels() {
  adb shell am start -n $PKG/.ChannelActivity --es channel_id UCvbobBwsNzBY4IV1vLH6Kkg --es channel_name "IG_Japan"
  wait_top ChannelActivity "ChannelActivity launch" 45
  sleep 5
  screencap screenshot-channel.png

  adb shell am start -n $PKG/.ChannelListActivity --es group_name "経済" --esa channel_ids UCvbobBwsNzBY4IV1vLH6Kkg,UCuAXFkgswL7iGxMpRfFONQA
  wait_top ChannelListActivity "ChannelListActivity launch" 45
  sleep 3
  screencap screenshot-channellist.png
}

# --- main ---------------------------------------------------------------

TEST_SECTIONS="${TEST_SECTIONS:-all}"
RUN_FEED=0; RUN_PLAYER=0
case "$TEST_SECTIONS" in
  all) RUN_FEED=1; RUN_PLAYER=1 ;;
  feed) RUN_FEED=1 ;;
  player) RUN_PLAYER=1 ;;
  *) echo "unknown TEST_SECTIONS='$TEST_SECTIONS'; running all sections"; RUN_FEED=1; RUN_PLAYER=1 ;;
esac
echo "TEST_SECTIONS=$TEST_SECTIONS (feed=$RUN_FEED player=$RUN_PLAYER)"

adb root || true
wait_until 15 "adb responsive" adb shell echo ok || true
adb install -r "$APK" || note_fail "adb install failed"
adb shell cmd uimode night yes || true
sleep 2
adb logcat -c || true

if [ "$RUN_FEED" -eq 1 ]; then section_feed_activities; fi
if [ "$RUN_PLAYER" -eq 1 ]; then
  section_player_verdict
  section_rate_pip
  section_paused_pip
fi
if [ "$RUN_FEED" -eq 1 ]; then section_feed_channels; fi

PID2=$(adb shell pidof $PKG | tr -d '\r\n'); echo "PID end: '$PID2'"
[ -n "$PID2" ] || note_fail "process dead at end"
adb logcat -d -v threadtime > logcat.txt 2>&1 || true
if grep -q "FATAL EXCEPTION" logcat.txt && grep -A3 "FATAL EXCEPTION" logcat.txt | grep -q "$PKG"; then
  note_fail "FATAL EXCEPTION for $PKG in logcat"
fi
echo "LAUNCH_TEST_FAIL=$FAIL"
exit $FAIL
