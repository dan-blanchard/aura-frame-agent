#!/bin/bash
# nightly-reboot.sh — reboot every frame, then check each one came back to its photos.
#
# Why: Aura's photo app never really reshuffles. Its end-of-pass "reshuffle" sorts the
# shuffled list back by last-seen time, so each frame replays one fixed order and skips
# the same few photos every pass, until the app restarts. A reboot is the proven restart;
# force-stopping the app alone is untested (white-screen risk). Rebooting all the frames
# together also lines up their photo timers.
#
# Run it from cron or launchd at night (it uses network adb, so frame-network-adb.sh must
# be in place and this machine must be on its allow list). A frame that isn't back on
# com.pushd.storyframe/.CarouselActivity gets one more reboot. If Home Assistant is set up
# below, screens held ON or OFF are re-sent afterwards (frame-agent's cmd/screen is not
# retained), and a frame that still isn't back triggers a notification.
#
# Frames file (default configs/frames.txt, or $FRAMES_FILE): one "<frame_id> <ip>" per line,
# e.g. "frame_kitchen 192.168.1.50". Blank lines and # comments are ignored.
# Home Assistant (optional): HA_URL (http://homeassistant.local:8123), HA_TOKEN (a
# long-lived access token) and HA_NOTIFY (a notify service such as mobile_app_my_phone).
#
# Usage: nightly-reboot.sh [--check] [--only <frame_id>]
#   --check  verify reachability and Carousel only; reboot nothing

set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
FRAMES_FILE="${FRAMES_FILE:-$(dirname "$HERE")/configs/frames.txt}"
ADB="${ADB:-$(command -v adb)}"
[ -x "$ADB" ] || { echo "adb not found; set ADB" >&2; exit 2; }
[ -f "$FRAMES_FILE" ] || { echo "no frames file at $FRAMES_FILE" >&2; exit 2; }

declare -a NAMES=()
while read -r name _; do
  { [ -z "${name:-}" ] || [ "${name:0:1}" = "#" ]; } && continue
  NAMES+=("$name")
done < "$FRAMES_FILE"
ip_of() { awk -v n="$1" '$1 == n { print $2; exit }' "$FRAMES_FILE"; }

CHECK=0
while [ $# -gt 0 ]; do
  case $1 in
    --check) CHECK=1 ;;
    --only) shift; NAMES=("$1") ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
  shift
done

log() { echo "$(date "+%Y-%m-%d %H:%M:%S") $*" >&2; }  # stderr: wait_for_photos's stdout is its result

connect() {  # drop any stale transport, then connect
  local s="$(ip_of "$1"):5555"
  "$ADB" disconnect "$s" >/dev/null 2>&1
  "$ADB" connect "$s" 2>&1 | grep -q "connected to" && \
    [ "$("$ADB" -s "$s" get-state 2>/dev/null)" = device ]
}

on_photos() {
  "$ADB" -s "$(ip_of "$1"):5555" shell 'dumpsys activity activities | grep mResumedActivity' 2>/dev/null \
    | grep -q 'com.pushd.storyframe/.CarouselActivity'
}

reboot_frames() {
  for n in "$@"; do
    if connect "$n"; then
      "$ADB" -s "$(ip_of "$n"):5555" reboot >/dev/null 2>&1 &
      log "$n: reboot sent"
    else
      log "$n: UNREACHABLE before reboot"
    fi
  done
  wait
}

wait_for_photos() {  # echoes the frames still not on photos after up to $1 s
  local deadline=$(( $(date +%s) + $1 )) pending=("${@:2}") left
  while [ ${#pending[@]} -gt 0 ] && [ "$(date +%s)" -lt "$deadline" ]; do
    sleep 15
    left=()
    for n in "${pending[@]}"; do
      if connect "$n" && on_photos "$n"; then log "$n: back on photos"; else left+=("$n"); fi
    done
    pending=("${left[@]+"${left[@]}"}")
  done
  echo "${pending[@]+"${pending[@]}"}"
}

have_ha() { [ -n "${HA_URL:-}" ] && [ -n "${HA_TOKEN:-}" ]; }
ha() {  # ha METHOD PATH [JSON]
  curl -s -m 20 -X "$1" -H "Authorization: Bearer $HA_TOKEN" \
       -H "content-type: application/json" "$HA_URL$2" ${3:+-d "$3"}
}

push() {
  if ! have_ha || [ -z "${HA_NOTIFY:-}" ]; then log "not notifying (no HA_NOTIFY): $1"; return; fi
  ha POST "/api/services/notify/$HA_NOTIFY" \
     "{\"title\":\"Aura frames\",\"message\":\"$1\"}" >/dev/null \
     && log "pushed: $1" || log "PUSH FAILED: $1"
}

reassert_screens() {
  have_ha || return 0
  for n in "${NAMES[@]}"; do
    local e="select.${n}_screen" mode
    mode=$(ha GET "/api/states/$e" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("state",""))' 2>/dev/null)
    if [ "$mode" = ON ] || [ "$mode" = OFF ]; then
      ha POST /api/services/select/select_option "{\"entity_id\":\"$e\",\"option\":\"$mode\"}" >/dev/null \
        && log "$n: screen $mode re-sent" || log "$n: screen $mode re-send FAILED"
    fi
  done
}

log "start (check=$CHECK, frames=${NAMES[*]})"

if [ $CHECK = 1 ]; then
  for n in "${NAMES[@]}"; do
    if connect "$n"; then
      on_photos "$n" && log "$n: reachable, on photos" || log "$n: reachable, NOT on photos"
    else
      log "$n: UNREACHABLE"
    fi
  done
  have_ha && log "HA: $(ha GET /api/ | tr -d '\n')"
  exit 0
fi

reboot_frames "${NAMES[@]}"
sleep 45   # frames take ~25-30 s to reach Carousel; don't poll the old session
read -r -a failed <<< "$(wait_for_photos 300 "${NAMES[@]}")"

if [ ${#failed[@]} -gt 0 ]; then
  log "not on photos after 6 min: ${failed[*]}; rebooting them once more"
  reboot_frames "${failed[@]}"
  sleep 45
  read -r -a failed <<< "$(wait_for_photos 300 "${failed[@]}")"
fi

reassert_screens

if [ ${#failed[@]} -gt 0 ]; then
  push "Nightly reboot: ${failed[*]} did not come back to photos after two reboots."
  log "done with failures: ${failed[*]}"
  exit 1
fi
log "done, all back on photos"
