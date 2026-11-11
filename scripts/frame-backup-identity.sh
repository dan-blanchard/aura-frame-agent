#!/usr/bin/env bash
# frame-backup-identity.sh — save a frame's Aura identity and small partitions off the frame.
#
# Run this first, before changing anything. A frame's identity is a random frameId plus an
# auth token in PushdFramePrefsFile.xml (about 1 KB). Aura keeps its own copy on the
# unencrypted `private` partition and restores it after a /data wipe, but an erase gesture
# can remove that copy too. With this backup, pushing the file back to /mnt/private/app/ in
# the first seconds of boot brings the frame back as itself, photos included.
#
# The prefs file holds your Wi-Fi password in plaintext. Keep the backup somewhere private.
#
# Usage: frame-backup-identity.sh <serial> [dest-dir]
# dest-dir defaults to ./backups/<ro.serialno>/.
set -euo pipefail

SERIAL="${1:?usage: frame-backup-identity.sh <serial> [dest-dir]}"
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
DEST="${2:-backups/$HW_SERIAL}"
mkdir -p "$DEST"
chmod 700 "$DEST"
umask 077

"${A[@]}" root >/dev/null 2>&1 || true
wait_frame

"${A[@]}" exec-out cat /mnt/private/app/PushdFramePrefsFile.xml > "$DEST/PushdFramePrefsFile.xml" || true
if [ -s "$DEST/PushdFramePrefsFile.xml" ]; then
  echo "identity: $(wc -c < "$DEST/PushdFramePrefsFile.xml" | tr -d ' ') bytes"
else
  echo "WARNING: no identity backup at /mnt/private/app/PushdFramePrefsFile.xml" >&2
  echo "  (Aura only writes it once the frame is set up with Wi-Fi and registered)" >&2
fi

for p in private env misc; do
  "${A[@]}" exec-out "dd if=/dev/block/by-name/$p bs=1048576 2>/dev/null" > "$DEST/$p.img"
  echo "$p.img: $(wc -c < "$DEST/$p.img" | tr -d ' ') bytes"
done

"${A[@]}" shell 'getprop ro.serialno; getprop ro.build.version.incremental' | tr -d '\r' > "$DEST/build.txt"
echo "saved to $DEST"
