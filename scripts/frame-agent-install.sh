#!/usr/bin/env bash
# frame-agent-install.sh — install or update the frame-agent overlay app on one frame.
#
# Installs as a normal user app (survives reboot, not a /data wipe), grants the
# draw-over-apps appop, pushes the frame's config.json if one exists, and starts
# the service. Does not touch com.pushd.* in any way.
#
# Usage:
#   frame-agent-install.sh <serial> [config.json]
#   frame-agent-install.sh <serial> --uninstall
# serial may be a USB serial (V60-...) or a TCP endpoint (192.168.1.50:5555).
# Config defaults to configs/<ro.serialno>.json (or $FRAME_AGENT_CONFIG_DIR/<ro.serialno>.json),
# keyed by the hardware serial because the TCP addresses come from DHCP. See
# config.example.json. The APK defaults to the release build; set FRAME_AGENT_APK to override.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=net.blanchard.frameagent
ROOT="$(dirname "$HERE")"
APK="${FRAME_AGENT_APK:-$ROOT/frame-agent/app/build/outputs/apk/release/app-release.apk}"
CONFIGS="${FRAME_AGENT_CONFIG_DIR:-$ROOT/configs}"
CONF_REMOTE="/sdcard/Android/data/$PKG/files/config.json"

SERIAL="${1:?usage: frame-agent-install.sh <serial> [config.json|--uninstall]}"
ARG2="${2:-}"
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

if [ "$ARG2" = "--uninstall" ]; then
  "${A[@]}" uninstall "$PKG"
  echo "uninstalled $PKG from $SERIAL"
  exit 0
fi

# Keyed by the hardware serial, since the TCP addresses come from DHCP.
HW_SERIAL="$("${A[@]}" shell getprop ro.serialno | tr -d '\r')"
CONF="${ARG2:-$CONFIGS/$HW_SERIAL.json}"
[ -f "$APK" ] || { echo "no APK at $APK; build with (cd frame-agent && ./gradlew assembleRelease)" >&2; exit 1; }

echo "== installing on $SERIAL"
"${A[@]}" install -r "$APK"
wait_frame
"${A[@]}" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
# Keep doze from parking the MQTT connection.
"${A[@]}" shell dumpsys deviceidle whitelist +"$PKG" >/dev/null

if [ -f "$CONF" ]; then
  "${A[@]}" shell mkdir -p "$(dirname "$CONF_REMOTE")"
  "${A[@]}" push "$CONF" "$CONF_REMOTE" >/dev/null
  echo "pushed config $CONF"
else
  echo "no config at $CONF; agent runs without MQTT (adb test hooks still work)"
fi

# A frame already moved to /system keeps that copy current too.
if "${A[@]}" shell '[ -d /system/app/FrameAgent ]'; then
  "$HERE/frame-agent-system.sh" "$SERIAL"
fi

# Restart so a new config is picked up.
"${A[@]}" shell am force-stop "$PKG"
"${A[@]}" shell am broadcast -n "$PKG/.DebugReceiver" -a "$PKG.START" >/dev/null
sleep 2
"${A[@]}" shell pidof "$PKG" >/dev/null && echo "agent running on $SERIAL" || { echo "agent did not start" >&2; exit 1; }
