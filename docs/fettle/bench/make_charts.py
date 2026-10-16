#!/usr/bin/env python3
"""Fettle 基准出图脚本。

读取 docs/fettle/bench/data/ 下的 CSV（缺文件即跳过对应图），
输出 5 张 PNG 到 --out 目录。

用法:
    python make_charts.py --data docs/fettle/bench/data --out docs/fettle/charts
"""
from __future__ import annotations

import argparse
import os
import sys

import matplotlib
matplotlib.use("Agg")  # 无 GUI 环境必须
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

BUILD_ORDER = ["base", "fettle", "fastsuite", "both"]


def _order(builds):
    known = [b for b in BUILD_ORDER if b in builds]
    rest = sorted(b for b in builds if b not in known)
    return known + rest


def _read(data_dir, name):
    path = os.path.join(data_dir, name)
    if not os.path.exists(path):
        print("[skip] %s 不存在" % name)
        return None
    try:
        df = pd.read_csv(path)
    except Exception as exc:  # noqa: BLE001
        print("[skip] %s 读取失败: %s" % (name, exc))
        return None
    if df.empty:
        print("[skip] %s 为空" % name)
        return None
    return df


def chart_tick_distribution(df, out_dir, scenario):
    d = df if not scenario else df[df["scenario"] == scenario]
    if d.empty:
        return False
    builds = _order(d["build"].unique().tolist())
    groups = [d[d["build"] == b]["tick_ms"].values for b in builds]
    fig, ax = plt.subplots(figsize=(8, 5))
    try:
        bp = ax.boxplot(groups, tick_labels=builds, showfliers=False, patch_artist=True)
    except TypeError:  # matplotlib < 3.9 用 labels=
        bp = ax.boxplot(groups, labels=builds, showfliers=False, patch_artist=True)
    for patch in bp["boxes"]:
        patch.set_alpha(0.6)
    for i, b in enumerate(builds, start=1):
        s = d[d["build"] == b]["tick_ms"]
        if len(s):
            ax.scatter([i] * len(s), s, s=6, alpha=0.25, color="black")
            ax.text(i, s.quantile(0.95), " p95=%.2f" % s.quantile(0.95), fontsize=8, va="bottom")
    ax.set_ylabel("tick time (ms)")
    ax.set_title("Tick time distribution%s" % (" — " + scenario if scenario else ""))
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    path = os.path.join(out_dir, "01_tick_distribution.png")
    fig.savefig(path, dpi=120)
    plt.close(fig)
    print("[ok] " + path)
    return True


def chart_scaling(df, out_dir, scenario):
    d = df if not scenario else df[df["scenario"] == scenario]
    if d.empty:
        return False
    fig, ax = plt.subplots(figsize=(8, 5))
    for b in _order(d["build"].unique().tolist()):
        s = d[d["build"] == b].sort_values("scale_value")
        err = s["mspt_p95_err"] if "mspt_p95_err" in s else None
        ax.errorbar(s["scale_value"], s["mspt_p95"], yerr=err, marker="o", capsize=3, label=b)
    label = "scale"
    if "scale_label" in d.columns and len(d["scale_label"].dropna()):
        label = str(d["scale_label"].dropna().iloc[0])
    ax.set_xlabel(label)
    ax.set_ylabel("MSPT p95 (ms)")
    ax.set_title("Scaling vs MSPT p95%s" % (" — " + scenario if scenario else ""))
    ax.legend()
    ax.grid(alpha=0.3)
    fig.tight_layout()
    path = os.path.join(out_dir, "02_scaling_vs_mspt.png")
    fig.savefig(path, dpi=120)
    plt.close(fig)
    print("[ok] " + path)
    return True


def chart_allocation(df, out_dir, scenario):
    d = df if not scenario else df[df["scenario"] == scenario]
    if d.empty:
        return False
    pivot = d.pivot_table(index="build", columns="class", values="mb_per_s", aggfunc="sum").fillna(0.0)
    pivot = pivot.reindex(_order(list(pivot.index)))
    fig, ax = plt.subplots(figsize=(9, 5))
    bottom = None
    for cls in pivot.columns:
        vals = pivot[cls].values
        ax.bar(pivot.index, vals, bottom=bottom, label=str(cls))
        bottom = vals if bottom is None else bottom + vals
    ax.set_ylabel("allocation per tick (MB/s)")
    ax.set_title("Per-tick allocation by class%s" % (" — " + scenario if scenario else ""))
    ax.legend(fontsize=8)
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    path = os.path.join(out_dir, "03_allocation.png")
    fig.savefig(path, dpi=120)
    plt.close(fig)
    print("[ok] " + path)
    return True


def chart_hotspots(df, out_dir, scenario, top=10):
    d = df if not scenario else df[df["scenario"] == scenario]
    if d.empty:
        return False
    builds = _order(d["build"].unique().tolist())[:2]
    methods = (
        d.groupby("method")["self_pct"].max().sort_values(ascending=False).head(top).index.tolist()
    )
    fig, ax = plt.subplots(figsize=(9, max(4, 0.45 * len(methods) + 2)))
    height = 0.8 / max(1, len(builds))
    for i, b in enumerate(builds):
        s = d[d["build"] == b].set_index("method").reindex(methods)["self_pct"].fillna(0.0)
        ax.barh([m - (len(builds) - 1) * height / 2 + i * height for m in range(len(methods))],
                s.values, height=height, label=b)
    ax.set_yticks(range(len(methods)))
    ax.set_yticklabels(methods, fontsize=8)
    ax.set_xlabel("self time (%)")
    ax.set_title("Hot methods%s" % (" — " + scenario if scenario else ""))
    ax.legend()
    ax.grid(axis="x", alpha=0.3)
    fig.tight_layout()
    path = os.path.join(out_dir, "04_hotspots.png")
    fig.savefig(path, dpi=120)
    plt.close(fig)
    print("[ok] " + path)
    return True


def chart_gc(df, out_dir, scenario):
    d = df if not scenario else df[df["scenario"] == scenario]
    if d.empty:
        return False
    builds = _order(d["build"].unique().tolist())
    counts = [len(d[d["build"] == b]) for b in builds]
    totals = [d[d["build"] == b]["pause_ms"].sum() for b in builds]
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(10, 4))
    ax1.bar(builds, counts, color="tab:blue", alpha=0.7)
    ax1.set_title("GC pause count")
    ax1.grid(axis="y", alpha=0.3)
    ax2.bar(builds, totals, color="tab:red", alpha=0.7)
    ax2.set_title("GC pause total (ms)")
    ax2.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    path = os.path.join(out_dir, "05_gc_pauses.png")
    fig.savefig(path, dpi=120)
    plt.close(fig)
    print("[ok] " + path)
    return True


def main():
    ap = argparse.ArgumentParser(description="Fettle benchmark charts")
    ap.add_argument("--data", default="docs/fettle/bench/data", help="CSV directory")
    ap.add_argument("--out", default="docs/fettle/charts", help="PNG output directory")
    ap.add_argument("--scenario", default="", help="only plot one scenario")
    args = ap.parse_args()

    if not os.path.isdir(args.data):
        print("data directory not found: %s" % args.data, file=sys.stderr)
        return 2
    os.makedirs(args.out, exist_ok=True)

    done = 0
    specs = [
        ("tick_times.csv", chart_tick_distribution, "tick_ms"),
        ("scaling.csv", chart_scaling, "mspt_p95"),
        ("alloc.csv", chart_allocation, "mb_per_s"),
        ("hotspots.csv", chart_hotspots, "self_pct"),
        ("gc.csv", chart_gc, "pause_ms"),
    ]
    for name, fn, required in specs:
        df = _read(args.data, name)
        if df is None:
            continue
        if required not in df.columns:
            print("[skip] %s 缺少列 %s" % (name, required))
            continue
        try:
            if fn(df, args.out, args.scenario):
                done += 1
        except Exception as exc:  # noqa: BLE001
            print("[fail] %s: %s" % (name, exc))

    print("done: %d chart(s) written to %s" % (done, args.out))
    return 0 if done else 1


if __name__ == "__main__":
    raise SystemExit(main())
