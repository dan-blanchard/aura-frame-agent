#!/sbin/busybox sh
# A modified copy of Aura's stock /sbin/factory-reset-watcher.sh. It is Aura's code, not
# covered by this repo's license. Two changes from stock:
#  - the block that turned adb off at every boot (once the frame was "verified") is gone
#  - each loop writes the DragonDog heartbeat, atomically (temp file + mv), so the hardware
#    watchdog stays fed even if Aura's updater stops writing it. A plain `>` races
#    DragonDog's read, and one empty read is enough for it to reset the frame.
# The button behavior is unchanged: a press shorter than 10 s on the flap pinhole turns adb on, and
# 10 s or more reflashes the factory image.
set -e
set -x

BUSYBOX=/sbin/busybox
UBOOT_BTN_PROC=/proc/pushd/ubootbtn
PRESSED_SECONDS_TIMEOUT=10
AM_PATH=/system/bin/am
EN_ADB_PRESSED_TIME=5
VERIFIED_FILE=/private/calibration/verified_at
RESET_HELPER=/sbin/factory-reset-trigger.sh

log () {
        echo "[FactoryResetWatcher] $1" > /dev/kmsg || true
}

seconds_since_epoch () {
	echo $($BUSYBOX date +"%s")
}

PRESSED_AT=0

log "OVERRIDE-ACTIVE watching for uboot presses (boot adb-reset removed)"
while [ 1 ];
do
	NOW=$(seconds_since_epoch)
		cut -d. -f1 /proc/uptime > /mnt/tmp/.hb.$$ 2>/dev/null && mv /mnt/tmp/.hb.$$ /mnt/tmp/updater_heartbeat.txt 2>/dev/null  # feed DragonDog atomically (temp+rename; avoids read-during-truncate race)

	read BTN_STATUS < $UBOOT_BTN_PROC
	if [ "$BTN_STATUS" == "not-pressed" ]; then
		if [ "$PRESSED_AT" -ge "$EN_ADB_PRESSED_TIME" ]; then
			log "performing enable usb adb"	
			settings put global adb_enabled 1
		fi
		PRESSED_AT=0
	elif [ "$PRESSED_AT" -eq 0 ]; then
		log "uboot button pressed"
		PRESSED_AT=$NOW
	elif [ "$((NOW-PRESSED_AT))" -ge "$PRESSED_SECONDS_TIMEOUT" ]; then
		if [ -x "$AM_CMD" ]; then
			$AM_CMD broadcast -a com.pushd.intent.TRUE_FACTORY_RESET
		fi
		log "performing factory reset"
		source $RESET_HELPER
	fi

	$BUSYBOX usleep 500000 # 0.5 seconds
done

