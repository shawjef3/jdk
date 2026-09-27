#!/usr/bin/env python3
"""Summarize sweep results: per label and k, the shape counts across JVMs
(both sites hot-inlined / mixed / virtual) and sorted per-JVM throughput.

usage: summarize.py results/LABEL.jsonl [...]
"""
import collections
import json
import sys


def shape(c):
    a, b = c.get("A"), c.get("B")
    if a == b == "hot-inlined":
        return "inl"
    if a in ("virtual", "other-inlined") and b in ("virtual", "other-inlined"):
        return "virt"
    return "mixed(A=%s,B=%s)" % (a, b)


def main():
    rows = collections.defaultdict(list)
    for path in sys.argv[1:]:
        for line in open(path):
            d = json.loads(line)
            rows[(d["label"], d["k"])].append(d)
    print("%-28s %3s %4s  %-26s  %s" % ("label", "k", "jvms", "shapes", "per-JVM final steps/us, sorted (i=inlined v=virtual m=mixed)"))
    for (label, k), ds in sorted(rows.items()):
        cnt = collections.Counter(shape(d["cls"]) for d in ds)
        tags = sorted(((d["final"], shape(d["cls"])[0]) for d in ds))
        per = " ".join("%.1f%s" % (f, t) for f, t in tags)
        shapes = " ".join("%s=%d" % kv for kv in sorted(cnt.items()))
        print("%-28s %3d %4d  %-26s  %s" % (label, k, len(ds), shapes, per))


if __name__ == "__main__":
    main()
