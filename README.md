# aura-frame-agent

Root access, a cloud kill switch, and Home Assistant control for Aura Walden photo frames.

Everything here installs over `adb` through the frame's micro-USB port, which is hidden behind a sticker next to the power port.
You don't need to open the frame. When you're done, each frame:

- keeps showing your Aura photos exactly as before
- has `adb` on after every reboot, over USB and (optionally) over your network
- no longer runs Aura's `lifeline` service, which can remotely reboot or factory-wipe it
- can't install firmware or app updates, which could undo all of the above
- runs `frame-agent`, a small Android app that draws on top of Aura's slideshow and takes
  commands from Home Assistant over MQTT: school-morning countdowns, seasonal effects with
  visitors that travel from frame to frame, scenes (images or video), and screen on/off

The story of how this came together is in
[Rooting our Aura frames](https://dan-blanchard.github.io/blog/aura-frames/).

> [!WARNING]
> This is tested on four Walden (AF110) frames running Aura's firmware as of October 2026,
> which is an Android 10 engineering build. Other models or newer firmware may differ. It
> can leave a frame in a state you'll have to recover from, and it's certainly not supported
> by Aura. Back up each frame's identity before you change anything (step 2).

## Before you start

- `adb` ([Android platform tools](https://developer.android.com/tools/releases/platform-tools))
- A micro-USB data cable
- To build frame-agent: JDK 17 and the Android SDK (compileSdk 34)
- For frame-agent: Home Assistant with an MQTT broker (the Mosquitto add-on is fine)
- Fixed IP addresses (DHCP reservations) for the frames and for your computer

**The Walden has two pinhole buttons, and the obvious one erases the frame.** The pinhole
out in the open is Aura's reset: hold it for about 8 seconds and the frame wipes itself.
Leave that one alone. The one you want is under the sticker next to the power port, beside the
micro-USB port.

## Setup

Do the whole thing on one frame first, and try anything new on that frame before the others.

### 1. Turn on adb

Peel back the sticker next to the power port, connect the micro-USB port to your computer,
and keep the frame's normal power plugged in too (USB alone browns out partway through boot).
The power plug covers the micro-USB port when it's plugged in normally, so plug it in
sideways to fit both cables. With the frame booted, press the pinhole under the sticker for
about 2 seconds and release. Holding it for 10 seconds or more
reflashes the factory image instead.

```sh
adb devices   # should list the frame by its serial, e.g. V60-12345678
```

The rest of these steps take that serial, or `<ip>:5555` once network adb is on. Until
step 3, stock firmware turns adb off again at every boot, so you may need to press the
button again after a reboot.

### 2. Back up the frame's identity

```sh
scripts/frame-backup-identity.sh V60-12345678
```

A frame's Aura identity is a random ID plus an auth token in a small preferences file. Aura
keeps its own copy on a separate partition and restores it after a wipe, but the erase
button can take that copy too. This saves the file (and three small partitions) to
`backups/<serial>/`. **That file also holds your Wi-Fi password in plaintext**, so keep the
backup private.

If a frame ever comes back from a wipe as a new, empty frame, don't pair the setup code it
shows. Push the saved file back during the first seconds of boot, before Aura's app starts:

```sh
adb wait-for-device && adb push backups/<serial>/PushdFramePrefsFile.xml /mnt/private/app/
```

It comes back as itself, with its photos.

### 3. Keep adb on and turn off remote wipe

```sh
scripts/frame-harden.sh V60-12345678
adb -s V60-12345678 reboot
```

This uses Android's init `override` to replace two of Aura's boot services, writing only to
the frame's `/system` overlay (the one `adb remount` uses):

- Aura's button watcher, which turns adb off at every boot, is replaced with a copy that
  doesn't. It keeps the button gestures, and it also feeds the hardware watchdog's heartbeat.
- The `lifeline` service, which polls Aura's servers and can reboot, reset or wipe the
  frame, is overridden to disabled.

It also lets frame-agent turn the panel on and off. The overlay lives on its own partition,
so all of this survives a `/data` wipe and a factory reflash. adb should be back within about
20 seconds of boot with no button press.

### 4. Freeze updates

```sh
scripts/frame-disable-ota.sh V60-12345678
```

This plants immutable directories where Aura's updater stages its downloads. The update
check still runs and still answers "no update", so the frame boots normally, but no update
can be written. Two of my four frames already had a complete firmware update downloaded and
waiting to install on the next reboot; the script removes one if it finds it. Reboot once
afterwards and check that the photos come back.

**Don't disable Aura's updater app** (`com.pushd.updater`) instead. It's also the frame's
home screen and what starts the photo app, so disabling it leaves the screen black.

`/cache` is cleared by a factory reset, so run this again after one.

### 5. Network adb (optional)

```sh
scripts/frame-network-adb.sh V60-12345678 192.168.1.20 192.168.1.21
adb -s V60-12345678 reboot
adb connect <frame-ip>:5555
```

The frames have no adb key requirement, so anyone who can reach port 5555 gets a root
shell. This firewalls 5555 to the IPv4 addresses you list and blocks it over IPv6 entirely.
I chose that over requiring an adb key, because Android expires keys that go unused for a
week and the key list lives in `/data`, so either a wipe or a quiet week would lock me out.

### 6. Install frame-agent

```sh
(cd frame-agent && ./gradlew assembleRelease)
mkdir -p configs
cp config.example.json configs/V60-12345678.json   # then edit it
scripts/frame-agent-install.sh V60-12345678
```

The config is named by the frame's hardware serial (`adb shell getprop ro.serialno`), since
network addresses can change. See [home-assistant/README.md](home-assistant/README.md#frame-config)
for what goes in it, and set up an MQTT login for the frame first.

Release builds use the debug key unless you give them your own; see the comment at the top
of `frame-agent/app/build.gradle.kts`. Every update has to be signed with the same key as
the copy already on the frame.

That installs frame-agent as an ordinary app, which a `/data` wipe removes. To make it
survive one, copy it into the `/system` overlay too, then remove the ordinary copy and reboot:

```sh
scripts/frame-agent-system.sh V60-12345678
adb -s V60-12345678 uninstall net.blanchard.frameagent
adb -s V60-12345678 reboot
```

After that, `frame-agent-install.sh` keeps the `/system` copy current as well.

### 7. Home Assistant

See [home-assistant/README.md](home-assistant/README.md) for the MQTT contract and an
example package with the automations.

### 8. Nightly reboot (optional)

Aura's photo app doesn't really reshuffle: each frame replays the same order and skips the
same few photos on every pass until the app restarts. `scripts/nightly-reboot.sh` reboots
every frame, checks that each one comes back to its photos, and re-sends any screen holds
from Home Assistant. Copy `frames.example.txt` to `configs/frames.txt`, then run it from cron
or launchd at 3 AM.

Before each reboot it saves a copy of each frame's display history to `impressions/`.
`scripts/frame-shuffle-check.py` reads those copies and tells you whether it's working. For
each frame and day, it shows how many photos were shown and what share came in the same
order as the day before. Without the reboot, most days on our frames repeated 90-100%; with it, 0-2%.
Given the photo sorter's Aura login, it also counts the photos a frame holds but never showed:

```sh
uv run scripts/frame-shuffle-check.py --since 2026-11-01
```

## Undoing it

Everything lives in the `/system` overlay and two directories in `/cache`:

```sh
adb remount
adb shell rm /system/bin/factory-reset-watcher.sh /system/etc/init/zz-aura-*.rc
adb shell rm -r /system/app/FrameAgent /system/etc/frame-agent
scripts/frame-disable-ota.sh <serial> --undo
adb uninstall net.blanchard.frameagent
adb reboot
```

## What's here

| Path | What it is |
|---|---|
| `frame-agent/` | The Android app (Kotlin, no dependencies beyond Paho MQTT and Media3) |
| `overlay/` | Files the scripts write into each frame's `/system` overlay |
| `scripts/` | The install scripts, plus `frame-morning-test.py` for trying the morning display without HA |
| `home-assistant/` | The MQTT contract and an example HA package |

## License

[0BSD](LICENSE), with two exceptions:

- `overlay/system/bin/factory-reset-watcher.sh` is a modified copy of Aura's own script.
- The seasonal effects draw fan art of characters that belong to other people:
  - Boo and Franklin (*Super Mario* and *Animal Crossing*) are Nintendo's.
  - The Pac-Man ghosts (Blinky, Pinky, Inky and Clyde) are Bandai Namco's.
  - Hooty (*The Owl House*) is Disney's.
  - Pumpkin (*Steven Universe*) is Cartoon Network's.

  The code is mine to license, but the characters aren't. The 0BSD license doesn't cover
  their designs, and this project isn't affiliated with or endorsed by any of their owners.
  The ghost called `classic` and the autumn leaves are original.

Almost all of this was written by [Claude Code](https://claude.com/claude-code), working
with me. This project is not affiliated with or endorsed by Aura.
