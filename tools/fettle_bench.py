#!/usr/bin/env python3
"""Fettle benchmark driver.

Builds a scenario on a running dev server over RCON, arms Fettle's own per-tick recorder
(/fettle bench start|stop), and appends the measurement to docs/fettle/bench/data/.

Usage:
    python tools/fettle_bench.py --scenario panels --panels 400 --tag fettle-on  --scale-value 400
    python tools/fettle_bench.py --scenario panels --panels 400 --tag fettle-off --scale-value 400 --skip-build
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import socket
import struct
import statistics
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
DATA_DIR = os.path.join(PROJECT, "docs", "fettle", "bench", "data")
SERVER_DIR = os.path.join(PROJECT, "run", "server")
TERMINATOR = bytes(2)


# --------------------------------------------------------------------------------------- RCON

def rcon_connect(host, port, password, timeout=30):
    sock = socket.create_connection((host, port), timeout=timeout)

    def send(req_id, req_type, body):
        payload = struct.pack("<ii", req_id, req_type) + body.encode("utf8") + TERMINATOR
        sock.sendall(struct.pack("<i", len(payload)) + payload)

    def recv():
        raw = sock.recv(4)
        if len(raw) < 4:
            raise RuntimeError("rcon closed")
        length = struct.unpack("<i", raw)[0]
        data = b""
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                raise RuntimeError("rcon closed mid-packet")
            data += chunk
        return struct.unpack("<ii", data[:8])[1], data[8:-2].decode("utf8", "ignore")

    send(1, 3, password)
    req_id, _ = recv()
    if req_id == -1:
        raise RuntimeError("rcon auth failed")
    return sock, send, recv


class Rcon:
    def __init__(self, host, port, password):
        self.sock, self._send, self._recv = rcon_connect(host, port, password)
        self._id = 10

    def cmd(self, command):
        self._id += 1
        self._send(self._id, 2, command)
        return self._recv()[1]

    def close(self):
        self.sock.close()


def build_scenario(rcon, scenario, args):
    rcon.cmd("gamerule doMobSpawning false")
    rcon.cmd("gamerule doDaylightCycle false")
    rcon.cmd("gamerule doWeatherCycle false")
    rcon.cmd("gamerule randomTickSpeed 0")
    rcon.cmd("time set noon")
    rcon.cmd("weather clear")
    rcon.cmd("kill @e[type=!minecraft:player]")
    if args.clear:
        # /fill is capped at 32768 blocks per command, so clear in strips.
        span = max(32, min(200, args.panels))
        x0, x1 = args.origin_x - 8, args.origin_x + span + 8
        y0, y1 = args.origin_y - 1, args.origin_y + 2
        step = 30000 // max(1, (x1 - x0 + 1) * (y1 - y0 + 1))
        z = args.origin_z - 8
        while z <= args.origin_z + span + 8:
            rcon.cmd("fill %d %d %d %d %d %d minecraft:air" % (x0, y0, z, x1, y1, min(z + max(1, step) - 1, args.origin_z + span + 8)))
            z += max(1, step)
    failures = 0

    # Force-load the whole scene area BEFORE placing anything: setblock into an unloaded chunk
    # silently fails with "That position is not loaded". /forceload is capped at 256 chunks per
    # command, so load it in bands.
    x0, x1 = args.origin_x - 32, args.origin_x + 352
    z = args.origin_z - 32
    z1 = args.origin_z + 352
    while z <= z1:
        rcon.cmd("forceload add %d %d %d %d" % (x0, z, x1, min(z + 127, z1)))
        z += 128

    placed = 0
    if scenario in ("panels", "mixed"):
        side = max(1, int(args.panels ** 0.5))
        for i in range(args.panels):
            x = args.origin_x + (i % side) * 4
            z = args.origin_z + (i // side) * 4
            failures += count_failure(rcon.cmd("setblock %d %d %d minecraft:chest" % (x, args.origin_y, z)))
            failures += count_failure(rcon.cmd("setblock %d %d %d create:factory_gauge" % (x, args.origin_y + 1, z)))
            placed += 2
    if scenario in ("belts", "mixed"):
        for i in range(args.belts):
            rcon.cmd("setblock %d %d %d create:belt" % (args.origin_x + i, args.origin_y, args.origin_z + 60))
            placed += 1
    if scenario in ("crafters", "mixed"):
        side = max(1, int(args.crafters ** 0.5))
        for i in range(args.crafters):
            x = args.origin_x + (i % side) * 4
            z = args.origin_z + (i // side) * 4 + 40
            failures += count_failure(rcon.cmd("setblock %d %d %d create:mechanical_crafter" % (x, args.origin_y, z)))
            placed += 1

    return placed, failures


def count_failure(reply):
    low = reply.lower()
    return 1 if ("could not" in low or "unknown" in low or "not loaded" in low) else 0


def append_csv(path, header, rows):
    exists = os.path.exists(path)
    with open(path, "a", newline="", encoding="utf8") as fh:
        writer = csv.writer(fh)
        if not exists:
            writer.writerow(header)
        writer.writerows(rows)


def percentile(values, fraction):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", default="panels", choices=["panels", "belts", "crafters", "mixed", "none"])
    ap.add_argument("--tag", required=True)
    ap.add_argument("--panels", type=int, default=200)
    ap.add_argument("--belts", type=int, default=200)
    ap.add_argument("--crafters", type=int, default=100)
    ap.add_argument("--origin-x", type=int, default=0)
    ap.add_argument("--origin-y", type=int, default=100)
    ap.add_argument("--origin-z", type=int, default=0)
    ap.add_argument("--warmup", type=int, default=45)
    ap.add_argument("--sample", type=int, default=60)
    ap.add_argument("--scale-value", type=int, default=0)
    ap.add_argument("--scale-label", default="factory gauges")
    ap.add_argument("--skip-build", action="store_true")
    ap.add_argument("--clear", action="store_true", help="clear the scenario area before building")
    ap.add_argument("--feature", action="append", default=[],
                    help="runtime feature override applied before measuring, e.g. crafterSignalCache=false")
    ap.add_argument("--drop-first", type=int, default=40, help="ticks dropped from the start of the window")
    ap.add_argument("--host", default=os.environ.get("FETTLE_RCON_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("FETTLE_RCON_PORT", "25575")))
    ap.add_argument("--password", default=os.environ.get("FETTLE_RCON_PASSWORD", "fettle"))
    args = ap.parse_args()

    os.makedirs(DATA_DIR, exist_ok=True)
    scale_value = args.scale_value or {"panels": args.panels, "belts": args.belts,
                                       "crafters": args.crafters}.get(args.scenario, 0)

    rcon = Rcon(args.host, args.port, args.password)
    try:
        if args.skip_build:
            print("[bench] scenario build skipped")
        else:
            print("[bench] building scenario %s ..." % args.scenario)
            placed, failures = build_scenario(rcon, args.scenario, args)
            print("[bench] placed %d blocks, %d placement problems" % (placed, failures))
        print("[bench] warming up %ds ..." % args.warmup)
        time.sleep(args.warmup)

        for feature in args.feature:
            name, _, value = feature.partition("=")
            print("   " + " | ".join(rcon.cmd("fettle feature %s %s" % (name, value)).strip().splitlines()))
        print("[bench] arming recorder '%s'" % args.tag)
        print("   " + " | ".join(rcon.cmd("fettle bench start " + args.tag).strip().splitlines()))
        print("[bench] sampling %ds ..." % args.sample)
        time.sleep(args.sample)
        stop_reply = rcon.cmd("fettle bench stop")
        print("   " + " | ".join(stop_reply.strip().splitlines()))
    finally:
        rcon.close()

    raw = os.path.join(SERVER_DIR, "fettle-ticks-%s.csv" % args.tag)
    if not os.path.exists(raw):
        print("[bench] ERROR: recorder file not found at %s" % raw)
        return 1
    with open(raw, encoding="utf8") as fh:
        rows = list(csv.DictReader(fh))
    rows = rows[args.drop_first:]
    values = [float(row["tick_ms"]) for row in rows]
    sections = {}
    for column in ("panels_tick_ms", "crafters_tick_ms", "panels_tick_calls", "crafters_tick_calls"):
        if rows and column in rows[0]:
            sections[column] = statistics.fmean(float(row[column]) for row in rows)
    if not values:
        print("[bench] ERROR: no samples in window")
        return 1

    p50 = percentile(values, 0.50)
    p95 = percentile(values, 0.95)
    p99 = percentile(values, 0.99)
    mean = statistics.fmean(values)
    print("[bench] ticks=%d mean=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f"
          % (len(values), mean, p50, p95, p99, max(values)))
    for column, average in sections.items():
        print("[bench]   mean %s = %.4f" % (column, average))

    append_csv(os.path.join(DATA_DIR, "tick_times.csv"), ["scenario", "build", "tick_ms"],
               [[args.scenario, args.tag, "%.4f" % v] for v in values])
    append_csv(os.path.join(DATA_DIR, "scaling.csv"),
               ["scenario", "build", "scale_value", "scale_label", "mspt_p95", "mspt_p95_err"],
               [[args.scenario, args.tag, scale_value, args.scale_label, "%.4f" % p95, "0.0"]])
    meta = {
        "tag": args.tag, "scenario": args.scenario, "panels": args.panels, "belts": args.belts,
        "crafters": args.crafters, "scale_value": scale_value, "warmup_s": args.warmup,
        "sample_s": args.sample, "ticks": len(values), "mean_ms": mean, "p50_ms": p50,
        "p95_ms": p95, "p99_ms": p99, "max_ms": max(values), "raw_csv": os.path.basename(raw),
        "drop_first": args.drop_first, "sections": sections,
    }
    with open(os.path.join(DATA_DIR, "meta-%s.json" % args.tag), "w", encoding="utf8") as fh:
        json.dump(meta, fh, indent=2, ensure_ascii=False)
    print("[bench] done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
