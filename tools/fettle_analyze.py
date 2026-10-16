
import csv, json, os, statistics

data = "docs/fettle/bench/data"
server = "run/server"
windows = [
    ("fettle-on",  "fettle-on-crafters.csv"),
    ("fettle-off", "fettle-off-crafters.csv"),
    ("fettle-on",  "fettle-on-crafters-b.csv"),
    ("fettle-off", "fettle-off-crafters-b.csv"),
    ("fettle-on",  "on-count.csv"),
    ("fettle-off", "off-count.csv"),
]

# deterministic, noise-free evidence: real neighbour-signal queries counted inside the mixin
counters = [("fettle-on", "on-count.csv"), ("fettle-off", "off-count.csv")]

def pct(values, f):
    ordered = sorted(values)
    return ordered[min(len(ordered)-1, int(round(f*(len(ordered)-1))))]

def load(path):
    with open(path, encoding="utf8") as fh:
        return list(csv.DictReader(fh))

def collect(raw):
    rows = load(os.path.join(server, "fettle-ticks-" + raw))[40:]
    tick = [float(r["tick_ms"]) for r in rows]
    calls = statistics.fmean(float(r["crafters_tick_calls"]) for r in rows)
    section = statistics.fmean(float(r["crafters_tick_ms"]) for r in rows)
    per_call_us = (section / calls * 1000.0) if calls else 0.0
    return tick, calls, section, per_call_us

tick_rows = [["scenario", "build", "tick_ms"]]
scale_rows = [["scenario", "build", "scale_value", "scale_label", "mspt_p95", "mspt_p95_err"]]
print("%-12s %8s %8s %8s %8s %10s %12s" % ("build", "ticks", "mean", "p50", "p95", "crafter_ms", "per_call_us"))
summary = {}
for label, raw in windows:
    tick, calls, section, per_call = collect(raw)
    key = label
    summary.setdefault(key, []).append((statistics.fmean(tick), pct(tick,0.95), section, per_call))
    for v in tick:
        tick_rows.append(["crafters", label, "%.4f" % v])
    scale_rows.append(["crafters", label, 202, "mechanical crafters", "%.4f" % pct(tick,0.95), "0.0"])
    print("%-12s %8d %8.3f %8.3f %8.3f %10.4f %12.4f" % (label, len(tick), statistics.fmean(tick), pct(tick,0.50), pct(tick,0.95), section, per_call))

print()
for key, values in summary.items():
    means = [v[0] for v in values]; p95s = [v[1] for v in values]
    sections = [v[2] for v in values]; per_call = [v[3] for v in values]
    print("%-12s n=%d  mean_ms=%s  p95=%s  crafter_section_ms=%s  per_call_us=%s" % (
        key, len(values),
        "/".join("%.3f" % m for m in means),
        "/".join("%.3f" % p for p in p95s),
        "/".join("%.4f" % s for s in sections),
        "/".join("%.4f" % c for c in per_call)))

with open(os.path.join(data, "tick_times.csv"), "w", newline="", encoding="utf8") as fh:
    csv.writer(fh).writerows(tick_rows)
with open(os.path.join(data, "scaling.csv"), "w", newline="", encoding="utf8") as fh:
    csv.writer(fh).writerows(scale_rows)
print()
print("deterministic signal-query counts (per tick):")
for label, raw in counters:
    rows = load(os.path.join(server, "fettle-ticks-" + raw))[40:]
    calls = statistics.fmean(float(r["crafters_tick_calls"]) for r in rows)
    memo = statistics.fmean(float(r["crafters_signal_calls"]) for r in rows)
    vanilla = 2 * calls
    print("  %-11s crafters=%6.1f  memoised_queries=%6.1f  vanilla_would_query=%6.1f  saved=%.1f%%"
          % (label, calls, memo, vanilla, (1 - memo / vanilla) * 100 if vanilla else 0))

print("\nrewrote tick_times.csv (%d rows) and scaling.csv" % (len(tick_rows)-1))
