#!/usr/bin/env python3
"""Fettle behaviour gate.

Boots the dev server headless (no JVM feature flags: every feature is controlled by its own config
option), then asserts the invariants our optimizations must never break:

  * recipe index parity        - every recipe lookup equals the vanilla brute-force result
  * train collision filter     - identical to vanilla, including the moved-train case
  * kinetic incremental stress - identical to the exact full scan, and after reconciliation

Exit code is the number of failed checks (0 = pass), which makes it usable as a CI gate.
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
RCON = os.path.join(HERE, "fettle_rcon.py")
PY = sys.executable
SERVER_LOG = os.path.join(PROJECT, "run", "server", "logs", "latest.log")


def rcon(command, timeout=120):
    out = subprocess.run([PY, RCON, command], capture_output=True, text=True, timeout=timeout,
                         cwd=PROJECT)
    return (out.stdout or "") + (out.stderr or "")


def start_server():
    env = dict(os.environ)
    env["JAVA_HOME"] = r"C:\Program Files\Java\jdk-21.0.10"
    env["PATH"] = env["JAVA_HOME"] + r"\bin;" + env.get("PATH", "")
    wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
    if os.path.exists(SERVER_LOG):
        os.remove(SERVER_LOG)
    gradle_args = [os.path.join(PROJECT, wrapper), "runServer", "--no-daemon", "--console=plain",
                   r"-Dorg.gradle.java.home=C:\Program Files\Java\jdk-21.0.10",
                   "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=7890",
                   "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=7890",
                   "-Dhttp.nonProxyHosts=localhost"]
    log = open(os.path.join(PROJECT, "build", "gate-server.log"), "w", encoding="utf8")
    proc = subprocess.Popen(gradle_args, cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT)
    # a stale log would make the readiness check pass instantly; wait for a fresh one
    deadline = time.time() + 300
    while time.time() < deadline:
        if os.path.exists(SERVER_LOG):
            try:
                with open(SERVER_LOG, encoding="utf8", errors="ignore") as fh:
                    if "Done (" in fh.read():
                        return proc
            except OSError:
                pass
        if proc.poll() is not None:
            return None
        time.sleep(3)
    return None


def stop_server(proc):
    if proc is None:
        return
    try:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)
    except Exception:
        proc.kill()


def main():
    failures = []
    proc = start_server()
    if proc is None:
        print("[gate] server did not start")
        return 1
    try:
        print("[gate] server up")

        # 1) kinetic differential (needs a live network)
        rcon("execute positioned 0 100 4800 run fettle debug kinetics 200")
        time.sleep(10)
        kin = rcon("fettle debug kinetictest 3")
        print("[gate] " + kin.strip())
        if "mismatches=0" not in kin:
            failures.append("kinetic differential produced no clean result")
        for label in ("stress=[n=", "afterReconcile=[n="):
            if label in kin:
                segment = kin.split(label, 1)[1]
                if not segment.startswith("") or "mismatches=0" not in segment[:40]:
                    failures.append("kinetic " + label + " has mismatches")

        # 2) train collision differential
        trains = rcon("fettle debug trains 3")
        print("[gate] " + trains.strip())
        if "fresh=[n=24 collisions=3 mismatches=0]" not in trains:
            failures.append("collision fresh phase diverged")
        if "stale_guarded=[n=24 collisions=3 mismatches=0 missed=0]" not in trains:
            failures.append("collision guarded phase diverged")

        # 3) sleeping pilot (config driven: beSleeping.classes whitelists the class)
        rcon("execute positioned 0 100 4000 run fettle debug hoppers 20")
        time.sleep(30)
        first = rcon("fettle idle sleeptest")
        time.sleep(20)
        second = rcon("fettle idle sleeptest")

        def pilot_progress(text):
            found = re.search(r"ticks=(\d+) skipped=(\d+)", text)
            return (int(found.group(1)), int(found.group(2))) if found else None

        print("[gate] " + first.strip().splitlines()[0][:140])
        before = pilot_progress(first)
        after = pilot_progress(second)
        if not before or not after:
            failures.append("sleeping pilot reported nothing")
        else:
            delta_ticks = after[0] - before[0]
            delta_skipped = after[1] - before[1]
            ratio = (delta_skipped / delta_ticks) if delta_ticks > 0 else 0.0
            print(f"[gate] pilot delta ticks={delta_ticks} skipped={delta_skipped} ratio={ratio:.2f}")
            if delta_ticks <= 0 or ratio < 5.0:
                failures.append("sleeping pilot skip ratio too low (expected >= 5)")

        # 4) recipe parity
        rcon("fettle selftest")
        time.sleep(8)
        parity = ""
        with open(SERVER_LOG, encoding="utf8", errors="ignore") as fh:
            for line in fh:
                if "parity self-test:" in line:
                    parity = line.strip()
        print("[gate] " + parity)
        match = re.search(r"mismatches=(\d+)", parity)
        if not match or int(match.group(1)) != 0:
            failures.append("recipe parity mismatches")
    finally:
        stop_server(proc)

    if failures:
        print("[gate] FAIL")
        for failure in failures:
            print("  - " + failure)
        return len(failures)
    print("[gate] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
