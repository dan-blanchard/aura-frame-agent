#!/usr/bin/env -S uv run --script
# /// script
# dependencies = ["paho-mqtt>=2"]
# ///
"""Publish a hand-written cmd/morning to one frame, standing in for HA.

Logs in with that frame's own MQTT login, read from its config in configs/ (or
$FRAME_AGENT_CONFIG_DIR), named by the frame's hardware serial.

  frame-morning-test.py V60-12345678 Alex=+10m Sam=+40s --event "07:45=Piano lesson"
  frame-morning-test.py V60-12345678 Alex=-1m --cleared Alex
  frame-morning-test.py V60-12345678 Alex=+1m Sam=+20m --no-countdown Alex
  frame-morning-test.py V60-12345678 --off
  frame-morning-test.py V60-12345678 --watch 60

Offsets are relative to now: +10m, +40s, -1m. The payload is retained, so finish
with --off. Prints the frame's state, its cmd/morning and any events it sends.
"""
import argparse
import json
import os
import re
import time
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import paho.mqtt.client as mqtt

CONFIGS = Path(os.environ.get("FRAME_AGENT_CONFIG_DIR") or Path(__file__).resolve().parent.parent / "configs")
COLORS = ["#2bb3a3", "#f28c28", "#8e5bd0", "#d64550"]
ZONE = ZoneInfo("America/New_York")  # replaced by the config's timezone


def leave_at(offset: str) -> str:
    m = re.fullmatch(r"([+-])(\d+)([ms])", offset)
    if not m:
        raise SystemExit(f"bad offset {offset!r}, expected like +10m or -30s")
    seconds = int(m[2]) * (60 if m[3] == "m" else 1) * (1 if m[1] == "+" else -1)
    return (datetime.now(ZONE) + timedelta(seconds=seconds)).isoformat(timespec="seconds")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("serial", help="hardware serial (ro.serialno), names the config in configs/")
    p.add_argument("kids", nargs="*", help="Name=offset")
    p.add_argument("--off", action="store_true", help='publish {"active":false}')
    p.add_argument("--event", action="append", default=[], help="start=title")
    p.add_argument("--note", default="")
    p.add_argument("--cleared", action="append", default=[], help="kid to mark cleared")
    p.add_argument("--no-countdown", action="append", default=[], help="kid with no countdown")
    p.add_argument("--watch", type=int, default=3, help="seconds to print state and events for")
    args = p.parse_args()

    conf = json.loads((CONFIGS / f"{args.serial}.json").read_text())
    global ZONE
    ZONE = ZoneInfo(conf.get("timezone", "America/New_York"))
    base = f"aura/{conf['frame_id']}"
    host, port = conf["broker_uri"].removeprefix("tcp://").split(":")

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.username_pw_set(conf["username"], conf["password"])
    client.on_message = lambda _c, _u, m: print(f"{time.strftime('%H:%M:%S')} {m.topic}: {m.payload.decode()}")
    client.connect(host, int(port))
    client.subscribe([(f"{base}/state", 1), (f"{base}/event", 1), (f"{base}/cmd/morning", 1)])
    client.loop_start()

    payload = None
    if args.off:
        payload = {"active": False}
    elif args.kids:
        payload = {
            "active": True,
            "events": [dict(zip(("start", "title"), e.split("=", 1))) for e in args.event],
            "kids": [
                {
                    "name": name,
                    "color": COLORS[i % len(COLORS)],
                    "leave_at": leave_at(offset),
                    "note": args.note,
                    "cleared": name in args.cleared,
                    "countdown": name not in args.no_countdown,
                }
                for i, (name, offset) in enumerate(k.split("=", 1) for k in args.kids)
            ],
        }
    if payload is not None:
        print(f"-> {base}/cmd/morning {json.dumps(payload)}")
        client.publish(f"{base}/cmd/morning", json.dumps(payload), qos=1, retain=True).wait_for_publish()

    time.sleep(args.watch)
    client.loop_stop()
    client.disconnect()


if __name__ == "__main__":
    main()
