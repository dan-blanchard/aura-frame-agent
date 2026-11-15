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
# The freeze itself is overlay/system/bin/aura-ota-freeze.sh. This installs it into the
# frame's /system overlay with an init file that reruns it at every boot, then runs it once
# now. /cache is wiped by a factory reset, including one Aura's app starts on a command from
# Aura; the boot-time run puts the freeze back before the updater starts again.
#
# Usage:
#   frame-disable-ota.sh [serial]          # apply the freeze
#   frame-disable-ota.sh [serial] --undo   # remove the freeze
# serial may be a USB serial (V60-...) or a TCP endpoint (192.168.1.50:5555).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OVERLAY="$(dirname "$HERE")/overlay/system"
SCRIPT=/system/bin/aura-ota-freeze.sh
RC=/system/etc/init/zz-aura-otafreeze.rc

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

# Without the overlay, /system writes fail. Stop rather than half-apply.
adb "${S[@]}" shell 'mount | grep -q overlay' \
  || { echo "no /system overlay on this frame; stopping" >&2; exit 1; }
# The overlay comes back read-only after every reboot.
adb "${S[@]}" remount >/dev/null
adb "${S[@]}" wait-for-device

if [ "$UNDO" -eq 1 ]; then
  echo "== removing the OTA freeze =="
  adb "${S[@]}" shell "rm -f $SCRIPT $RC && echo '  removed the boot-time script'"
  for P in "${BLOCKS[@]}"; do
    adb "${S[@]}" shell "toybox chattr -i '$P' 2>/dev/null; rm -rf '$P' && echo '  removed $P' || echo '  (nothing at $P)'"
  done
  echo "Done. OTA download/apply is no longer blocked."
  exit 0
fi

echo "== installing the boot-time freeze =="
adb "${S[@]}" push "$OVERLAY/bin/aura-ota-freeze.sh" "$SCRIPT" >/dev/null
adb "${S[@]}" push "$OVERLAY/etc/init/zz-aura-otafreeze.rc" "$RC" >/dev/null
adb "${S[@]}" shell "chmod 0755 $SCRIPT; chmod 0644 $RC; restorecon $SCRIPT $RC"

echo "== planting immutable staging blocks =="
adb "${S[@]}" shell "sh $SCRIPT" | sed 's/^/  /'

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

cat <<'EOT'
Done. OTA/APK download is dead-ended at immutable staging dirs; the update CHECK
still answers no_update so the launcher/photos are unaffected.
 - Reboot once to confirm a clean boot to CarouselActivity + blocks persist.
 - The freeze is rebuilt at every boot, so it comes back after a factory reset.
 - Undo: frame-disable-ota.sh [serial] --undo
EOT
