#!/usr/bin/env bash
# Store screenshots with demo data. Needs two running emulators with the debug build installed:
# SHOT (the phone in the screenshots, default emulator-5554) and PEER ("Sarah", default emulator-5556),
# so the lists really show as connected. Replaces the debug app's data on both!
#   bash scripts/screenshots.sh [out_dir]      (default docs/play/screenshots)
set -euo pipefail
export MSYS_NO_PATHCONV=1
ADB=${ADB:-adb}
SHOT=${SHOT:-emulator-5554}; PEER=${PEER:-emulator-5556}; P=app.twodo.debug
OUT=${1:-docs/play/screenshots}
TMP=$(mktemp -d)
# On Windows (Git Bash), Python needs the Windows form of the temp path.
command -v cygpath >/dev/null && TMP=$(cygpath -m "$TMP")
python scripts/demo_data.py "$TMP"

load() { # serial who
  $ADB -s $1 shell am force-stop $P
  $ADB -s $1 shell pm clear $P >/dev/null
  $ADB -s $1 shell pm grant $P android.permission.POST_NOTIFICATIONS
  $ADB -s $1 shell "run-as $P mkdir -p files/lists shared_prefs"
  $ADB -s $1 push "$TMP/$2/identity.xml" /data/local/tmp/identity.xml >/dev/null
  $ADB -s $1 shell "run-as $P cp /data/local/tmp/identity.xml shared_prefs/identity.xml"
  for f in "$TMP/$2/lists/"*.json; do
    $ADB -s $1 push "$f" /data/local/tmp/list.json >/dev/null
    $ADB -s $1 shell "run-as $P cp /data/local/tmp/list.json files/lists/$(basename "$f")"
  done
}
load $PEER sarah
load $SHOT me
$ADB -s $PEER shell am start -n $P/app.twodo.MainActivity >/dev/null
$ADB -s $SHOT shell am start -n $P/app.twodo.MainActivity >/dev/null
# Clean status bar: fixed time, full battery and signal, no notifications.
$ADB -s $SHOT shell settings put global sysui_demo_allowed 1
# The clock shows the real time, so it matches the "5 min ago" and History times in the demo data.
for cmd in "enter" "clock -e hhmm $(date +%H%M)" "battery -e level 100 -e plugged false" "network -e wifi show -e level 4 -e fully true" "network -e mobile hide" "notifications -e visible false"; do
  set -- $cmd; c=$1; shift
  $ADB -s $SHOT shell am broadcast -a com.android.systemui.demo -e command $c "$@" >/dev/null
done
echo "Waiting for the phones to connect..."; sleep 15

tap_text() { # tap the first node whose text or content-desc is $1
  $ADB -s $SHOT shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  b=$($ADB -s $SHOT shell cat /sdcard/ui.xml | grep -o "<node [^>]*\(text\|content-desc\)=\"$1\"[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b; $ADB -s $SHOT shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 1.5
}
shot() { $ADB -s $SHOT exec-out screencap -p > "$OUT/$1.png"; echo "  $1"; }
back() { $ADB -s $SHOT shell input keyevent 4; sleep 1; }

mkdir -p "$OUT"
shot 1-home
tap_text "Groceries"; shot 2-list
tap_text "Share"; sleep 1; shot 5-share; tap_text "Done"
tap_text "More"; tap_text "History"; shot 6-history; back; back
tap_text "Family diary"; shot 3-diary
tap_text "More"; tap_text "Theme"; shot 7-themes; tap_text "Done"; back
tap_text "Holiday packing"; shot 4-themed-list; back
$ADB -s $SHOT shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
echo "Screenshots in $OUT"
