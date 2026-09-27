#!/usr/bin/env python3
"""Compare Renaissance runs: per benchmark, mean duration of the last half of
repetitions for each (config, round), ratio comb/stock of the per-config means,
and C2 (Tier4) compiled-method count and compile time from -XX:+CITime.

usage: rensum.py [base=stock] [other=comb]
"""
import glob
import json
import os
import re
import statistics
import sys

base = sys.argv[1] if len(sys.argv) > 1 else "stock"
other = sys.argv[2] if len(sys.argv) > 2 else "comb"
root = "/root/bench/ren"


def load(cfg):
    out = {}
    for rnd in sorted(os.listdir(os.path.join(root, cfg))):
        for js in glob.glob(os.path.join(root, cfg, rnd, "*.json")):
            b = os.path.basename(js)[:-5]
            d = json.load(open(js))["data"][b]["results"]
            durs = [r["duration_ns"] / 1e6 for r in d]
            steady = durs[len(durs) // 2:]
            txt = open(js[:-5] + ".out", errors="replace").read()
            m = re.search(r"Tier4 \{.*?standard:\s+([\d.]+) s, \d+ bytes, (\d+) methods; osr:\s+([\d.]+) s, \d+ bytes, (\d+) methods", txt)
            c2 = (int(m.group(2)) + int(m.group(4)), float(m.group(1)) + float(m.group(3))) if m else (None, None)
            out.setdefault(b, []).append((statistics.mean(steady), c2))
    return out


a, o = load(base), load(other)
print("%-18s %-26s %-26s %7s   %-16s %-16s" % ("benchmark", base + " ms (rounds)", other + " ms (rounds)",
                                               other + "/" + base, "C2 methods " + base, "C2 methods " + other))
ratios = []
for b in sorted(set(a) & set(o)):
    ma = statistics.mean(x[0] for x in a[b])
    mo = statistics.mean(x[0] for x in o[b])
    ratios.append(mo / ma)
    print("%-18s %-26s %-26s %7.3f   %-16s %-16s" % (
        b, " ".join("%.1f" % x[0] for x in a[b]), " ".join("%.1f" % x[0] for x in o[b]), mo / ma,
        " ".join(str(x[1][0]) for x in a[b]), " ".join(str(x[1][0]) for x in o[b])))
if ratios:
    geo = statistics.geometric_mean(ratios)
    print("geomean %s/%s duration ratio: %.3f (<1 = faster)" % (other, base, geo))
