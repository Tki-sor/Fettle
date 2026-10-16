
import csv, statistics
def load(tag):
    rows = list(csv.DictReader(open("run/server/fettle-ticks-%s.csv" % tag, encoding="utf8")))[40:]
    tick = [float(r["tick_ms"]) for r in rows]
    calls = statistics.fmean(float(r["crafters_tick_calls"]) for r in rows)
    sig   = statistics.fmean(float(r["crafters_signal_calls"]) for r in rows)
    sect  = statistics.fmean(float(r["crafters_tick_ms"]) for r in rows)
    return tick, calls, sig, sect

def p95(v):
    o = sorted(v); return o[min(len(o)-1, int(round(0.95*(len(o)-1))))]

print("%-8s %8s %8s %8s %10s %14s %14s" % ("tag","mean","p50","p95","section","queries/tick","per_crafter"))
for tag in ("h-on-1","h-off-1","h-on-2","h-off-2"):
    t, c, s, sec = load(tag)
    print("%-8s %8.3f %8.3f %8.3f %10.4f %14.1f %14.3f" % (
        tag, statistics.fmean(t), statistics.median(t), p95(t), sec, s, s/c if c else 0))
