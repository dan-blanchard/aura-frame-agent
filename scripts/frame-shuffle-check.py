# /// script
# requires-python = ">=3.11"
# ///
"""Does each frame show its photos in a new order every day, and does it get to all of them?

Aura's photo app logs every photo it shows in impressions_v1.dat, but it periodically
rewrites that file down to one row per photo, so the order can only be studied from daily
copies. nightly-reboot.sh saves one per frame before each reboot. This merges those copies
with a live pull from each frame and reports, per frame and day:

  - shows and distinct photos.
  - order repeat: of the photos shown on both days, the share followed by the same photo as
    the day before (only counting shows at most 7 minutes apart). Without a daily restart
    this was 94-100 % on our frames; a real reshuffle gives a few percent.
  - never shown: photos the frame holds on Aura that it didn't show in the window. This needs
    an Aura login (the photo sorter's, aura-login.json) and is read-only. Frames are matched
    to Aura by serial number, so nothing else needs configuring.

Frames come from configs/frames.txt (or $FRAMES_FILE), the same file nightly-reboot.sh reads;
copies from $IMPRESSIONS_DIR (default impressions/ in this repo). The live pull and serial
lookup use network adb (frame-network-adb.sh), so this machine must be on its allow list.

Usage: uv run scripts/frame-shuffle-check.py [--since YYYY-MM-DD] [--only <frame_id>] [--no-live] [--no-aura]
"""
import argparse
import collections
import datetime as dt
import glob
import json
import os
import shutil
import struct
import subprocess
import urllib.request
import uuid

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FRAMES_FILE = os.environ.get("FRAMES_FILE") or f"{REPO}/configs/frames.txt"
IMPRESSIONS_DIR = os.environ.get("IMPRESSIONS_DIR") or f"{REPO}/impressions"
AURA_LOGIN = os.environ.get("AURA_LOGIN") or os.path.join(
    os.environ.get("AURA_SORTER_HOME") or os.path.expanduser("~/Library/Application Support/aura-photo-sorter"),
    "aura-login.json")
ADB = os.environ.get("ADB") or shutil.which("adb")
API = "https://api.pushd.com/v5"
USER_AGENT = "Aura/4.7.790 (Android 30; Client)"
IMPRESSIONS = "/data/data/com.pushd.storyframe/files/impressions_v1.dat"
RECORD = 59  # bytes per row, big-endian (StoredImpression.writeTo)
GAP_S = 420  # photos change every 300 s; a longer gap means missing rows or a sleeping screen


def read_frames():
    """[(frame_id, ip)] from the frames file: "<frame_id> <ip>" per line, # comments."""
    frames = []
    with open(FRAMES_FILE) as f:
        for line in f:
            parts = line.split("#", 1)[0].split()
            if len(parts) >= 2:
                frames.append((parts[0], parts[1]))
    return frames


def parse(blob):
    """(impression id, shown at, photo id) for each row."""
    out = []
    for i in range(0, len(blob) // RECORD * RECORD, RECORD):
        a1, a2, i1, i2, viewed, _elapsed, _pos, _feed, _sent = struct.unpack(">qqqqqqhq?", blob[i:i + RECORD])
        u = lambda hi, lo: str(uuid.UUID(int=((hi & (2**64 - 1)) << 64) | (lo & (2**64 - 1))))
        out.append((u(i1, i2), dt.datetime.fromtimestamp(viewed / 1000), u(a1, a2)))
    return out


def adb(ip, *args, timeout=60):
    serial = f"{ip}:5555"
    subprocess.run([ADB, "connect", serial], capture_output=True, timeout=30)
    r = subprocess.run([ADB, "-s", serial, *args], capture_output=True, timeout=timeout)
    return r.stdout if r.returncode == 0 else b""


def aura(path, login):
    request = urllib.request.Request(f"{API}{path}", headers={
        "user-agent": USER_AGENT, "x-token-auth": login["auth_token"], "x-user-id": str(login["user_id"])})
    with urllib.request.urlopen(request, timeout=60) as r:
        return json.load(r)


def held_photos(frames, login):
    """frame_id -> set of photo ids the frame holds on Aura, for frames whose serial matched."""
    by_serial = {f["hw_serial"]: f["id"] for f in aura("/frames.json", login)["frames"] if f.get("hw_serial")}
    out = {}
    for name, ip in frames:
        serial = adb(ip, "shell", "getprop", "ro.serialno", timeout=30).decode().strip()
        if serial not in by_serial:
            print(f"{name}: no Aura frame with serial {serial or '(frame unreachable)'}; skipping 'never shown'")
            continue
        assets = aura(f"/frames/{by_serial[serial]}/assets.json?side_load_users=false&limit=1000", login)["assets"]
        if len(assets) >= 1000:
            print(f"{name}: Aura listed 1000 photos, the most it returns at once; 'never shown' may be high")
        out[name] = {a["id"] for a in assets}
    return out


def report(name, ip, since, live, held):
    rows = {}
    paths = sorted(glob.glob(f"{IMPRESSIONS_DIR}/*-{name}.dat"))
    blobs = [open(p, "rb").read() for p in paths]
    if live:
        blobs.append(adb(ip, "exec-out", "cat", IMPRESSIONS))
    for blob in blobs:
        for impression, when, photo in parse(blob):
            rows[(impression, when, photo)] = (when, photo)  # impression ids alone repeat per photo
    shows = sorted(v for v in rows.values() if since is None or v[0] >= since)
    if not shows:
        print(f"{name}: no shows found ({len(paths)} saved copies{', plus a live pull' if live else ''})")
        return
    days = collections.defaultdict(list)  # day -> runs of back-to-back shows
    last = None
    for when, photo in shows:
        if last is None or when.date() != last.date() or (when - last).total_seconds() > GAP_S:
            days[when.date()].append([])
        days[when.date()][-1].append(photo)
        last = when
    print(f"{name}: {len(shows)} shows over {len(days)} days from {shows[0][0]:%Y-%m-%d %H:%M} "
          f"({len(paths)} saved copies{', plus a live pull' if live else ''})")
    previous = None
    for day in sorted(days):
        runs = days[day]
        shown = [p for run in runs for p in run]
        following = {a: b for run in runs for a, b in zip(run, run[1:])}
        line = f"   {day}: {len(shown):3} shows, {len(set(shown)):3} distinct"
        if previous:
            common = [p for p in following if p in previous]
            if common:
                same = sum(following[p] == previous[p] for p in common)
                line += f", same next photo as the day before {same}/{len(common)} ({100 * same / len(common):.0f} %)"
        print(line)
        previous = following
    if name in held:
        never = held[name] - {photo for _, photo in shows}
        print(f"   holds {len(held[name])} on Aura; never shown in this window: {len(never)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--since", help="only count shows from this date (YYYY-MM-DD)")
    ap.add_argument("--only", metavar="FRAME_ID", help="check one frame from the frames file")
    ap.add_argument("--no-live", action="store_true", help="only read saved copies; don't pull from the frames")
    ap.add_argument("--no-aura", action="store_true", help="skip the 'never shown' count")
    a = ap.parse_args()
    if not os.path.exists(FRAMES_FILE):
        raise SystemExit(f"no frames file at {FRAMES_FILE}; copy frames.example.txt there")
    frames = [f for f in read_frames() if a.only in (None, f[0])]
    if not frames:
        raise SystemExit(f"no frame {a.only!r} in {FRAMES_FILE}")
    needs_adb = not a.no_live or not a.no_aura
    if needs_adb and not ADB:
        raise SystemExit("adb not found; set ADB, or use --no-live --no-aura")
    held = {}
    if not a.no_aura:
        if os.path.exists(AURA_LOGIN):
            with open(AURA_LOGIN) as f:
                held = held_photos(frames, json.load(f))
        else:
            print(f"no Aura login at {AURA_LOGIN} (set AURA_LOGIN); skipping 'never shown'")
    since = dt.datetime.fromisoformat(a.since) if a.since else None
    for name, ip in frames:
        report(name, ip, since, not a.no_live, held)


if __name__ == "__main__":
    main()
