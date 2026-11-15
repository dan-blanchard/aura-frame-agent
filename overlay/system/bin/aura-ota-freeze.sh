#!/system/bin/sh
# aura-ota-freeze.sh — keep Aura's updater from ever finishing a download.
#
# Runs as root at every boot (zz-aura-otafreeze.rc), before any app starts, and from
# frame-disable-ota.sh. The updater only installs a download that completes, so this
# occupies its two staging paths in /cache with immutable, non-empty directories. A factory
# reset clears /cache; running from the /system overlay puts the freeze back on the first
# boot afterwards. It must stay short and never wait on anything: init runs it inline.
#
# Never occupy /cache/ota_update.zip itself. The updater treats an existing final zip as
# already downloaded and goes straight to verify and reboot, so that one is removed.

log() { echo "aura-ota-freeze: $*" > /dev/kmsg 2>/dev/null; echo "$*"; }

FINAL=/cache/ota_update.zip
if [ -e "$FINAL" ]; then
  toybox chattr -i "$FINAL" 2>/dev/null
  rm -rf "$FINAL" && log "removed a staged $FINAL"
fi

for P in /cache/ota_update.zip-checkpoint /cache/app_update.apk; do
  case "$(toybox lsattr -d "$P" 2>/dev/null)" in
    *i*" $P") [ -d "$P" ] && [ -e "$P/.block" ] && continue ;;
  esac
  toybox chattr -i "$P" 2>/dev/null
  rm -rf "$P" 2>/dev/null
  if mkdir "$P" && : > "$P/.block" && chown -R root:root "$P" && chmod 000 "$P"; then
    toybox chattr +i "$P" 2>/dev/null || log "could not make $P immutable; relying on it being non-empty"
    log "blocked $P"
  else
    log "FAILED to block $P"
  fi
done
