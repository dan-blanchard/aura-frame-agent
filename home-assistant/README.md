# Home Assistant side

Each frame runs `frame-agent`, which connects to Home Assistant's MQTT broker and announces
itself through MQTT discovery. HA drives it entirely over MQTT; nothing on the HA side talks
to the frames directly.

`packages/aura_frames.yaml` is an example package with the automations I use, cut down to a
generic household: two kids (Alex and Sam) with their own portrait frames, and one family
frame that shows a band with everyone on it. Edit the lists at the top of the file (frame
order on the wall, which frames show the band, and the kids), plus the leave-time helpers
and the screen selects in the 6 AM automation. Then copy it into `config/packages/`, and
make sure `configuration.yaml` has:

```yaml
homeassistant:
  packages: !include_dir_named packages
```

It includes:

- **Seasonal effects.** Halloween cobwebs and ghosts through October (with flies and spiders
  after dark), and Thanksgiving leaves and visitors during Thanksgiving week.
- **Visitor tours and peeks.** About every half hour, one visitor travels across the frames
  in wall order. Every 6 minutes or so, one peeks in on a frame; the frames take turns.
- **Morning countdowns.** On school days (weekdays, minus whatever the
  [Workday](https://www.home-assistant.io/integrations/workday/) integration rules out),
  the band appears an hour before the first kid leaves. Each kid's frame shows a countdown
  card from 15 minutes out, and a full-screen "Time to go!" when it's time. Up to three of
  the morning's events from `calendar.family` show on the band, if you have that calendar.
  Turn `input_boolean.aura_morning_enabled` off for vacations.
- **Screen control.** `switch.frames` turns every screen off (or hands them back to Aura's
  own light-sensor schedule), and an Off left on overnight goes back to Auto at 6 AM.
  Its state is whether any frame's panel is actually lit, so it reads off when Aura has
  them dark at night.
- **Scenes.** `script.aura_show_media` shows an image or video from `/config/www/aura_scenes`
  on some or all frames; `script.aura_clear_media` clears it.

My own version also works out which kids are home each morning from our family calendars
and the school district's calendar feeds, and sends alerts when a frame isn't showing what
it should. That's too specific to my household to be useful here.

## Broker logins

Give each frame its own MQTT-only login whose username equals its `frame_id`. With the
Mosquitto add-on, these go in the add-on's `logins` option, **not** as Home Assistant users:
an HA user, even a non-admin one, can log into the HA UI and API. Everything on the frame can
read the password (Aura's apps run as `system`), so the login should be worth nothing beyond
the frame itself.

```yaml
logins:
  - username: frame_kitchen     # one entry per frame
    password: "..."
```

An ACL that limits each login to its own topics would look like this:

```
user homeassistant
topic readwrite #
user addons
topic readwrite #

pattern readwrite aura/%u/#
pattern write homeassistant/+/%u/#
```

**As of Mosquitto add-on 7.1.3 (October 2026) that ACL is loaded but not enforced.** The
add-on's auth plugin approves every ACL check, so frame logins can read and write every
topic. Test it yourself by logging in as one frame and subscribing to another frame's
`aura/<id>/#`. Until it works, treat the frame logins as having full broker access.

## Frame config

Each frame reads `config.json` from its app folder
(`/sdcard/Android/data/net.blanchard.frameagent/files/`), or from `/system/etc/frame-agent/`
once the app is moved into `/system`. `scripts/frame-agent-install.sh` pushes it. See
[`config.example.json`](../config.example.json):

| Key | Meaning |
|---|---|
| `frame_id` | Base of every topic and entity id, e.g. `frame_kitchen` |
| `frame_name` | Device name in HA |
| `broker_uri` | `tcp://<broker-ip>:1883`. Use an IP address; Android 10 can't resolve `.local` names |
| `username`, `password` | The frame's MQTT login |
| `features` | Any of `effects`, `morning`, `media`, `screen` (default: just `effects`) |
| `morning_layout` | `band` (all kids along the bottom) or `card` (one kid's personal countdown) |
| `timezone` | For the morning clock; the frames' system clock is on GMT and Aura relies on that |

`screen` also needs `zz-aura-screen.rc`, which `scripts/frame-harden.sh` installs.

## MQTT contract

Base topic per frame: `aura/<frame_id>/`.

| Topic | Direction | Payload |
|---|---|---|
| `availability` | frame -> HA, retained | `online` / `offline` (LWT) |
| `state` | frame -> HA, retained | `{"effect":"...","media":null,"morning":"idle\|band\|alert"}` (`media` is the scene's URL while one is up), plus `"screen":"on\|off","screen_control":"ha\|aura","screen_mode":"ON\|OFF\|AUTO"` with the `screen` feature |
| `cmd/effect` | HA -> frame, retained | `off`, `halloween`, `halloween_spooky`, `thanksgiving` |
| `cmd/ghost` | HA -> frame | See below |
| `cmd/screen` | HA -> frame | `ON`, `OFF`, `AUTO` (AUTO hands control back to Aura). Not retained, so it's lost if the frame reboots |
| `cmd/media` | HA -> frame | `{"url":"http://...","type":"image\|video","fit":"cover\|contain","timeout_s":0}`; an empty payload or `clear` clears. `timeout_s` counts from when the scene appears; 0 holds it. Not retained, so a reboot goes back to photos |
| `cmd/morning` | HA -> frame, retained | `{"active":true,"events":[{"start":"7:45 AM","title":"..."}],"kids":[{"name":"Alex","color":"#2bb3a3","leave_at":"2026-11-03T07:30:00-05:00","note":"","cleared":false,"countdown":true}]}`; `{"active":false}` hides it. `leave_at` must carry its UTC offset. `countdown: false` keeps the band up for that kid with no chip, card or alert |
| `event` | frame -> HA | `{"type":"dismiss","kid":"Alex"}` when a touch-bar press dismisses a kid's alert; `{"type":"media_error","url":"...","reason":"..."}` when a scene can't load or play |

`cmd/ghost` payloads, all ignored while no effect is on:

- `{"peek":true}`, optionally with `style` and `kind`: one visitor peeks in on that frame.
  Frames have no peek timer of their own; all the timing is in HA. In spooky Halloween mode,
  a peek is a ghost half the time, otherwise a fly or a spider (`kind`: `ghost`, `fly`,
  `spider`). Under Thanksgiving, `kind` is `corner`, `side`, `bottom` or `walk`.
- `{"visit":true,"style":"boo","from":"left","to":"right","from_pos":0.4,"to_pos":0.5,"duration_ms":4500}`:
  one crossing, which is a single leg of a tour. Edges are `left`, `right`, `top`, `bottom`;
  positions run 0-1 along the edge. Hooty also takes `hold_ms` (how long his body stays
  stretched across after his head leaves) and `return_ms` (how long his head takes to come
  back); -1 means the defaults.
- `{"quiet_ms":N}`: a visitor that's out ducks away, and no peek starts for N ms. Sent to
  every frame just before a tour.

Styles: `classic`, `boo`, `blinky`, `pinky`, `inky`, `clyde` under Halloween; `hooty`,
`pumpkin`, `franklin` under Thanksgiving. Anything else means random.

Discovery entities per frame: `select.<frame_id>_effect` and `sensor.<frame_id>_state` (its
state is the `morning` field; the rest are attributes). Frames with the `screen` feature add
`select.<frame_id>_screen`, and frames with `media` add `button.<frame_id>_clear_media`.

Overlays stack effects, then media, then morning, so a scene covers the ghosts and cobwebs
but never a countdown. Scenes are silent, and video pauses while the panel is off. Use H.264
MP4 at 1080p or less; the frames' hardware decoder handled 720p and 1080p at 30 fps. Files
under `/local` need no login, so anyone on your network can read them: keep them to scene art.

The frame computes countdowns locally from `leave_at`, so HA only republishes `cmd/morning`
when something changes. The frame also clears a kid by itself 10 minutes after their leave
time, so a retained payload HA never cleared can't leave an alert up.

To try `cmd/morning` without HA, use `scripts/frame-morning-test.py`.

## Testing without MQTT

frame-agent has a debug receiver for poking it over adb:

```sh
adb shell am broadcast -n net.blanchard.frameagent/.DebugReceiver \
  -a net.blanchard.frameagent.SET_EFFECT --es effect halloween
adb shell am broadcast -n net.blanchard.frameagent/.DebugReceiver \
  -a net.blanchard.frameagent.GHOST --es style boo
```
