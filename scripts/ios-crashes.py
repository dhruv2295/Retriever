#!/usr/bin/env python3
"""Every Retriever iOS crash this Mac can see, newest first, with the crashed thread's top frames.

Sources:
  store    TestFlight and App Store crashes, from Xcode's Organizer cache (Xcode's own crash tool for agents fails to
           download, so this reads the cache). --refresh opens the Organizer's crash view and each crash point's
           download link (xcode://organizer/crashes...), so Xcode fetches new crashes first; Xcode must be signed in.
  local    Simulator (and Mac) runs: ~/Library/Logs/DiagnosticReports/Retriever-*.ips
  device   Every connected iPhone, copied off with devicectl (crash logs live on the phone until synced).

Usage: scripts/ios-crashes.py [--refresh] [--since YYYY-MM-DD] [--frames N] [--no-device]
"""
import argparse
import glob
import json
import os
import re
import subprocess
import tempfile
import time
from datetime import datetime

APP = "Retriever"
BUNDLE = "app.retriever"
ADAM_ID = "6829902478"  # App Store Connect app id
POINTS = os.path.expanduser(f"~/Library/Developer/Xcode/Products/{BUNDLE}/Crashes/Points")


def refresh(wait=20):
    """Has Xcode fetch the crash list, then every known crash point's logs."""
    subprocess.run(["open", "xcode://organizer/crashes"])
    time.sleep(wait)
    for point in glob.glob(f"{POINTS}/*.xccrashpoint"):
        pid = os.path.basename(point).removesuffix(".xccrashpoint")
        subprocess.run(["open", f"xcode://organizer/crashes/downloadPoint?adamId={ADAM_ID}&platformId=iOS"
                                f"&installPlatformId=iOS&analyticsPointId={pid}&bundleId={BUNDLE}"])
        time.sleep(wait / 2)


def ips(path):
    """A .ips (JSON header line, then the report): when, version, why, and the crashed thread's frames."""
    with open(path, encoding="utf-8", errors="replace") as f:
        head, _, body = f.read().partition("\n")
    h, b = json.loads(head), json.loads(body)
    if h.get("app_name") != APP and b.get("procName") != APP:
        return None
    images = b.get("usedImages", [])
    thread = b.get("threads", [{}])[b.get("faultingThread", 0)]
    frames = [f"{images[fr['imageIndex']].get('name', '?')}  {fr.get('symbol', '?')}" for fr in thread.get("frames", [])]
    exc = b.get("exception", {})
    return {
        "when": b.get("captureTime", h.get("timestamp", ""))[:19],
        "version": f"{h.get('app_version')} ({h.get('build_version')})",
        "os": h.get("os_version", ""),
        "why": f"{exc.get('type', '')} {exc.get('signal', '')}".strip() + (f" — {b['asi']}" if b.get("asi") else ""),
        "frames": frames,
    }


def crash(path):
    """A legacy .crash (text), as the Organizer stores store crashes."""
    text = open(path, encoding="utf-8", errors="replace").read()
    def get(key):
        m = re.search(rf"^{key}:\s*(.+)$", text, re.M)
        return m.group(1).strip() if m else ""

    crashed = re.search(r"^Thread (\d+) Crashed:\n((?:.+\n)+)", text, re.M)
    frames = []
    if crashed:
        for line in crashed.group(2).splitlines():
            parts = line.split(None, 3)
            if len(parts) == 4:
                symbol = re.sub(r" \+ \d+.*$", "", parts[3])
                frames.append(f"{parts[1]}  {symbol}")
    return {
        "when": get("Date/Time")[:19],
        "version": get("Version"),
        "os": get("OS Version"),
        "why": f"{get('Exception Type')} {get('Termination Reason')}".strip(),
        "frames": frames,
    }


def store():
    for point in glob.glob(f"{POINTS}/*.xccrashpoint/Filters/*"):
        name = ""
        info = os.path.join(point, "PointInfo.json")
        if os.path.exists(info):
            name = json.load(open(info)).get("analyticsPointName", "")
        for log in glob.glob(f"{point}/Logs/*.crash"):
            yield "store", name, crash(log)


def local():
    for path in glob.glob(os.path.expanduser(f"~/Library/Logs/DiagnosticReports/{APP}-*.ips")):
        report = ips(path)
        if report:
            yield "local", os.path.basename(path), report


def device():
    try:
        listed = subprocess.run(["xcrun", "devicectl", "list", "devices", "--json-output", "/dev/stdout"],
                                capture_output=True, text=True, timeout=60).stdout
        devices = json.loads(listed[listed.index("{"):])["result"]["devices"]
    except Exception:
        return
    for d in devices:
        if d.get("connectionProperties", {}).get("tunnelState") != "connected":
            continue
        udid = d["hardwareProperties"]["udid"]
        with tempfile.TemporaryDirectory() as out:
            files = subprocess.run(["xcrun", "devicectl", "device", "info", "files", "--device", udid,
                                    "--domain-type", "systemCrashLogs"], capture_output=True, text=True, timeout=120).stdout
            for name in re.findall(rf"^({APP}[-.][^\s]+\.ips)", files, re.M):
                dest = os.path.join(out, os.path.basename(name))
                subprocess.run(["xcrun", "devicectl", "device", "copy", "from", "--device", udid, "--domain-type",
                                "systemCrashLogs", "--source", name, "--destination", dest], capture_output=True, timeout=120)
                if os.path.exists(dest) and (report := ips(dest)):
                    yield f"device {d.get('deviceProperties', {}).get('name', udid)}", name, report


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--since", help="only crashes on or after this date (YYYY-MM-DD)")
    p.add_argument("--frames", type=int, default=12, help="frames of the crashed thread to show (default 12)")
    p.add_argument("--no-device", action="store_true", help="skip connected iPhones")
    p.add_argument("--refresh", action="store_true", help="have Xcode fetch new store crashes first")
    a = p.parse_args()
    if a.refresh:
        refresh()
    found = list(store()) + list(local()) + ([] if a.no_device else list(device()))
    found = [f for f in found if not a.since or f[2]["when"][:10] >= a.since]
    found.sort(key=lambda f: f[2]["when"], reverse=True)
    if not found:
        print("No Retriever crashes found. Store crashes appear after Xcode > Window > Organizer > Crashes refreshes.")
    for source, name, r in found:
        print(f"\n{r['when']}  [{source}]  {r['version']}  iOS {r['os']}\n  {name}\n  {r['why']}")
        app_frames = [f for f in r["frames"] if not f.startswith(("libsystem", "libdispatch", "libswift_Concurrency"))]
        for f in (app_frames or r["frames"])[: a.frames]:
            print(f"    {f}")
    stamp = os.path.expanduser(f"~/Library/Developer/Xcode/Products/{BUNDLE}/Info.json")
    if os.path.exists(stamp):
        print(f"\nOrganizer cache last refreshed {datetime.fromtimestamp(os.path.getmtime(stamp)):%Y-%m-%d %H:%M}.")


if __name__ == "__main__":
    main()
