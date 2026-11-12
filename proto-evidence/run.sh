#!/bin/bash
# usage: run.sh <serial> <agent-device> <outdir> <alias:marker>...
S=$1; AD=$2; OUT=$3; shift 3
P=de.markusfisch.android.shadereditor.edproto
mkdir -p "$OUT"
for item in "$@"; do
  alias=${item%%:*}; marker=${item#*:}
  if adb -s $S shell dumpsys window | grep -q "isKeyguardShowing=true"; then echo "LOCKED, stop"; exit 2; fi
  adb -s $S logcat -c
  "$AD" open $P --activity $P/de.markusfisch.android.shadereditor.editor.proto.$alias --relaunch >/dev/null 2>&1
  n=0
  until adb -s $S logcat -d -s EdProto:I | grep -q "$marker"; do
    sleep 3; n=$((n+1))
    if [ $n -gt 80 ]; then echo "$alias: TIMEOUT"; break; fi
    if ! adb -s $S shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q "$P"; then echo "$alias: not in front, stop"; adb -s $S logcat -d -s EdProto:I AndroidRuntime:E > "$OUT/$alias.log"; exit 3; fi
  done
  adb -s $S logcat -d -s EdProto:I AndroidRuntime:E > "$OUT/$alias.log"
  echo "== $alias"; grep -v " key \| frame " "$OUT/$alias.log" | cut -d: -f4- | grep -v "^ *$"
done
