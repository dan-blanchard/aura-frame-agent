#!/usr/bin/env bash
# frame-agent-system.sh — copy the frame-agent APK and config into a frame's /system overlay.
#
# The copy in /system/app survives a /data wipe. Android only picks it up at boot; until
# then this changes nothing that's running, and it never reboots the frame. To move a frame
# over, run this, `adb uninstall net.blanchard.frameagent`, then reboot. frame-agent-install.sh
# reruns this on any frame already moved, so a wiped frame doesn't come back on an old version.
#
# /system/app, not priv-app: the app needs no privileged permission, and these frames
# enforce the priv-app allowlist at boot. To undo: remove both paths below and reboot.
#
# Usage: frame-agent-system.sh <serial>
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
APK="${FRAME_AGENT_APK:-$ROOT/frame-agent/app/build/outputs/apk/release/app-release.apk}"
CONFIGS="${FRAME_AGENT_CONFIG_DIR:-$ROOT/configs}"
APP_DIR=/system/app/FrameAgent
CONF_DIR=/system/etc/frame-agent

SERIAL="${1:?usage: frame-agent-system.sh <serial>}"
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
HW_SERIAL="$("${A[@]}" shell getprop ro.serialno | tr -d '\r')"
CONF="$CONFIGS/$HW_SERIAL.json"
[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }
[ -f "$CONF" ] || { echo "no config at $CONF" >&2; exit 1; }

"${A[@]}" remount >/dev/null
wait_frame
"${A[@]}" shell mkdir -p "$APP_DIR" "$CONF_DIR"
"${A[@]}" push "$APK" "$APP_DIR/FrameAgent.apk" >/dev/null
"${A[@]}" push "$CONF" "$CONF_DIR/config.json" >/dev/null
"${A[@]}" shell chmod 755 "$APP_DIR" "$CONF_DIR"
"${A[@]}" shell chmod 644 "$APP_DIR/FrameAgent.apk" "$CONF_DIR/config.json"
"${A[@]}" shell restorecon -R "$APP_DIR" "$CONF_DIR"

WANT="$(shasum -a 256 "$APK" | cut -d' ' -f1)"
GOT="$("${A[@]}" shell sha256sum "$APP_DIR/FrameAgent.apk" | cut -d' ' -f1)"
[ "$WANT" = "$GOT" ] || { echo "APK on $SERIAL doesn't match the build" >&2; exit 1; }
echo "system copy in place on $SERIAL"
