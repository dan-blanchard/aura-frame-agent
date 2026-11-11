#!/usr/bin/env bash
# frame-network-adb.sh — turn on adb over the network (port 5555), open only to the hosts you list.
#
# The frames run an engineering build with no adb key requirement, so anyone who can reach
# port 5555 gets a root shell. This writes two init files into the /system overlay:
#   zz-aura-adbtcp.rc  starts adbd on port 5555 once the frame has booted
#   zz-aura-adbfw.rc   rejects 5555 from everyone except the IPv4 addresses given, and
#                      rejects it over IPv6 entirely
# Give your machines fixed addresses (DHCP reservations) before relying on this.
# Both take effect at the next reboot, which is left to you.
#
# Undo: adb remount, delete both files, reboot.
#
# Usage: frame-network-adb.sh <serial> <allowed-ipv4> [<allowed-ipv4> ...]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OVERLAY="$(dirname "$HERE")/overlay/system"

SERIAL="${1:?usage: frame-network-adb.sh <serial> <allowed-ipv4>...}"
shift
[ $# -gt 0 ] || { echo "list at least one host allowed to reach adb" >&2; exit 1; }
for ip in "$@"; do
  [[ "$ip" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "not an IPv4 address: $ip" >&2; exit 1; }
done
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

# -I inserts at the top, so the REJECT goes in first and each ACCEPT lands above it.
FW="$(mktemp)"
trap 'rm -f "$FW"' EXIT
{
  echo "on property:sys.boot_completed=1"
  echo "    exec u:r:su:s0 root root -- /system/bin/iptables -w 10 -I INPUT -p tcp --dport 5555 -j REJECT --reject-with tcp-reset"
  for ip in "$@"; do
    echo "    exec u:r:su:s0 root root -- /system/bin/iptables -w 10 -I INPUT -s $ip -p tcp --dport 5555 -j ACCEPT"
  done
  echo "    exec u:r:su:s0 root root -- /system/bin/ip6tables -w 10 -I INPUT -p tcp --dport 5555 -j REJECT --reject-with tcp-reset"
} > "$FW"

wait_frame
"${A[@]}" root >/dev/null 2>&1 || true
wait_frame
"${A[@]}" remount >/dev/null
wait_frame

# Firewall first, so there is never a boot with 5555 open and no firewall.
for pair in "$FW:zz-aura-adbfw.rc" "$OVERLAY/etc/init/zz-aura-adbtcp.rc:zz-aura-adbtcp.rc"; do
  src="${pair%%:*}" rc="${pair##*:}"
  "${A[@]}" push "$src" "/system/etc/init/$rc" >/dev/null
  "${A[@]}" shell chmod 644 "/system/etc/init/$rc"
  "${A[@]}" shell restorecon "/system/etc/init/$rc"
  echo "  /system/etc/init/$rc"
done

cat <<EOF
Done. After the next reboot, from an allowed host:
  adb connect <frame-ip>:5555
and from the frame, the rules should be in place:
  adb -s $SERIAL shell 'iptables -S INPUT | grep 5555; ip6tables -S INPUT | grep 5555'
EOF
