#!/usr/bin/env bash
# frame-disable-ota.sh — freeze the Aura OTA/APK updater on a frame over adb.
# Reversible. Does NOT disable any package and does NOT touch the home launcher.
#
# WHY NOT `pm disable`: com.pushd.updater is ALSO the frame's HOME launcher
# (.UpdaterActivity = category.HOME/LAUNCHER) and the thing that launches the
# photo app com.pushd.storyframe. Disabling it => no home => black FallbackHome
# screen. The photo app's SetupActivity also WAITS for the updater to broadcast
# an update status, so you must leave the updater running and let the CHECK answer
# (normally "no_update"). Only the download+APPLY may be neutered.
#
# METHOD (static-RE of Updater.apk v1.2.234, proven on three frames 2026-10-30):
# the OTA/APK apply (RecoverySystem.installPackage -> reboot to recovery -> reflash)
# is only reached if the download produces a finished file. Occupy the two download
# STAGING paths with immutable, non-empty, root-owned DIRECTORIES so the download
# stream throws -> caught -> returns null -> exits BEFORE verify and BEFORE the
# reboot. The update check still runs and still says no_update, so photos are
# unaffected. NEVER occupy /cache/ota_update.zip itself — an existing FINAL zip is
# treated as "already downloaded" and goes straight to verify->reboot.
#
# Usage:
#   frame-disable-ota.sh [serial]          # apply the freeze
#   frame-disable-ota.sh [serial] --undo   # remove the freeze
# serial may be a USB serial (V60-...) or a TCP endpoint (192.168.1.50:5555).
#
# NOTE: /cache is wiped by a factory reset/recovery — re-run this after any reset.
set -euo pipefail

SERIAL=""; UNDO=0
for a in "$@"; do
  case "$a" in
    --undo) UNDO=1 ;;
    *) SERIAL="$a" ;;
  esac
done
S=(); [ -n "$SERIAL" ] && S=(-s "$SERIAL")

# The two download staging paths. NOT the final /cache/ota_update.zip.
BLOCKS=(/cache/ota_update.zip-checkpoint /cache/app_update.apk)
FINAL=/cache/ota_update.zip

adb "${S[@]}" wait-for-device
adb "${S[@]}" root >/dev/null 2>&1 || true
adb "${S[@]}" wait-for-device

if [ "$UNDO" -eq 1 ]; then
  echo "== removing OTA staging-block =="
  for P in "${BLOCKS[@]}"; do
    adb "${S[@]}" shell "toybox chattr -i '$P' 2>/dev/null; rm -rf '$P' && echo '  removed $P' || echo '  (nothing at $P)'"
  done
  echo "Done. OTA download/apply is no longer blocked."
  exit 0
fi

echo "== pre-check =="
# A stale FINAL zip would apply on reboot — remove it (it's just a staged download).
adb "${S[@]}" shell "if [ -e '$FINAL' ]; then echo '  WARNING: stale $FINAL present -> removing so it cannot apply'; toybox chattr -i '$FINAL' 2>/dev/null; rm -f '$FINAL'; else echo '  ok: no stale final zip'; fi"

echo "== planting immutable staging blocks =="
for P in "${BLOCKS[@]}"; do
  adb "${S[@]}" shell "
    toybox chattr -i '$P' 2>/dev/null; rm -rf '$P' 2>/dev/null
    mkdir '$P' && : > '$P/.block' && chown -R root:root '$P' && chmod 000 '$P'
    if toybox chattr +i '$P' 2>/dev/null; then echo '  +i set on $P'; else echo '  WARN: +i NOT applied on $P (relying on non-empty dir)'; fi
  "
done

echo "== verify =="
for P in "${BLOCKS[@]}"; do
  adb "${S[@]}" shell "
    t=\$(toybox lsattr -d '$P' 2>/dev/null | awk '{print \$1}')
    d=\$([ -d '$P' ] && echo dir || echo NOT-DIR)
    n=\$(ls -A '$P' 2>/dev/null | wc -l)
    echo \"  $P: type=\$d entries=\$n attrs=\$t\"
  "
done
adb "${S[@]}" shell "[ -e '$FINAL' ] && echo '  !! final zip present (unexpected)' || echo '  final zip absent: good'"
echo -n "  foreground: "; adb "${S[@]}" shell 'dumpsys activity activities 2>/dev/null | grep -m1 ResumedActivity | grep -oE "com\.[a-z.]+/[.A-Za-z]+"'

cat <<'EOF'
Done. OTA/APK download is dead-ended at immutable staging dirs; the update CHECK
still answers no_update so the launcher/photos are unaffected.
 - Reboot once to confirm a clean boot to CarouselActivity + blocks persist.
 - Re-run after any factory reset (/cache gets wiped).
 - Undo: frame-disable-ota.sh [serial] --undo
EOF
