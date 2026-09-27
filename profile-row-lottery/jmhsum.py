#!/usr/bin/env python3
"""Per-fork JMH scores: mean of each fork's measurement iterations, sorted,
with median and the ratio of medians between parameter sets.

usage: jmhsum.py results.json [...]
"""
import json
import statistics
import sys

for path in sys.argv[1:]:
    medians = {}
    for b in json.load(open(path)):
        params = ",".join("%s=%s" % kv for kv in sorted(b.get("params", {}).items())
                          if kv[0] in ("k", "mode"))
        forks = sorted(statistics.mean(f) for f in b["primaryMetric"]["rawData"])
        med = statistics.median(forks)
        medians[params] = med
        lo, hi = forks[0], forks[-1]
        print("%s  %-14s forks=%d  median=%.1f  min=%.1f (%+.0f%%)  max=%.1f  %s" % (
            path.split("/")[-1], params, len(forks), med, lo, 100 * (lo / med - 1), hi,
            b["primaryMetric"]["scoreUnit"]))
        print("    sorted: " + " ".join("%.0f" % f for f in forks))
    keys = list(medians)
    if len(keys) == 2:
        a, b = keys
        print("    median %s / %s = %.3f" % (a, b, medians[a] / medians[b]))
