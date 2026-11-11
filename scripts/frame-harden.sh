#!/usr/bin/env bash
# frame-harden.sh — keep adb on across reboots and turn off Aura's remote wipe, over adb.
#
# Writes four files into the frame's /system overlay (the `adb remount` scratch overlay,
# which these frames ship with):
#   /system/bin/factory-reset-watcher.sh   Aura's button watcher minus the step that turns
#                                          adb off at every boot, plus the DragonDog heartbeat
#   /system/etc/init/zz-aura-watcher.rc    runs that copy in place of the stock one
#   /system/etc/init/zz-aura-lifeline.rc   overrides Aura's lifeline service to disabled
#   /system/etc/init/zz-aura-screen.rc     lets frame-agent switch the panel on and off
# then sets adb_enabled=1. Nothing takes effect until the next reboot, which is left to you.
#
# Run frame-backup-identity.sh first. Then run frame-disable-ota.sh to freeze updates.
#
# Undo: adb remount, delete the four files above, reboot.
#
# Usage: frame-harden.sh <serial>
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OVERLAY="$(dirname "$HERE")/overlay/system"

SERIAL="${1:?usage: frame-harden.sh <serial>}"
A=(adb -s "$SERIAL")

# Frames drop off adb at random; never one-shot against them.
wait_frame() {
  local tries=0
  until "${A[@]}" get-state >/dev/null 2>&1; do
    [[ "$SERIAL" == *:* ]] && adb connect "$SERIAL" >/dev/null 2>&1 || true
    tries=$((tries + 1))
    [ $tries -ge 60 ] && { echo "frame $SERIAL never came back" >&2; exit 1; }
    sleep 2
  done
}

wait_frame
"${A[@]}" root >/dev/null 2>&1 || true
wait_frame

# Without the overlay, /system writes fail. Stop rather than half-apply.
"${A[@]}" shell 'mount | grep -q overlay' \
  || { echo "no /system overlay on $SERIAL; stopping" >&2; exit 1; }

# The overlay comes back read-only after every reboot.
"${A[@]}" remount >/dev/null
wait_frame

push() {  # push <local> <remote> <mode>
  "${A[@]}" push "$1" "$2" >/dev/null
  "${A[@]}" shell chmod "$3" "$2"
  "${A[@]}" shell restorecon "$2"
  echo "  $2"
}

echo "== writing overlay files on $SERIAL"
push "$OVERLAY/bin/factory-reset-watcher.sh" /system/bin/factory-reset-watcher.sh 0755
for rc in zz-aura-watcher.rc zz-aura-lifeline.rc zz-aura-screen.rc; do
  push "$OVERLAY/etc/init/$rc" "/system/etc/init/$rc" 0644
done

"${A[@]}" shell settings put global adb_enabled 1
# Give SettingsProvider time to write that to disk before anyone reboots.
sleep 10

cat <<EOF
Done. Reboot $SERIAL, then check:
  adb -s $SERIAL shell 'dmesg | grep OVERRIDE-ACTIVE'   # the replacement watcher is running
  adb -s $SERIAL shell getprop init.svc.lifeline        # empty: lifeline is off
adb should come back by itself within about 20 seconds of boot, with no button press.
EOF
