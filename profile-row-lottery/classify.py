#!/usr/bin/env python3
"""Classify one JVM run from its -XX:+LogCompilation file.

For every C2 compile of lottery.Interp::run, report at site A (Supplier.get)
and site B (Function.apply): the receiver rows C2 saw, and what it emitted
(inline of the hot class, inline of another class, or a virtual call).
The verdict for the run is taken from the last surviving (not made
not-entrant) standard C2 compile, falling back to the last OSR compile.

usage: classify.py LOGFILE HOT_A HOT_B [--json]
"""
import json
import re
import sys

SITES = {268: "A", 1458: "B"}
ROOT = "lottery.Interp run "
attr_re = re.compile(r"(\w+)='([^']*)'")


def attrs(line):
    return dict(attr_re.findall(line))


def parse(path):
    tasks = {}
    alive = {}          # compile_id -> True/False for c2 nmethods of run()
    cur = None          # current task dict
    depth = 0
    site = None
    ids = {}
    with open(path, errors="replace") as f:
        for line in f:
            if line.startswith("<nmethod ") and ROOT in line and "compiler='c2'" in line:
                alive[attrs(line)["compile_id"]] = True
                continue
            if line.startswith("<make_not_entrant ") and "compiler='c2'" in line:
                cid = attrs(line)["compile_id"]
                if cid in alive:
                    alive[cid] = False
                continue
            if line.startswith("<task ") and ROOT in line and "level='" not in line:
                a = attrs(line)
                cur = {"id": a["compile_id"], "osr": a.get("compile_kind") == "osr",
                       "stamp": float(a["stamp"]), "sites": {}}
                depth = 0
                site = None
                ids = {}
                continue
            if cur is None:
                continue
            if line.startswith("</task>"):
                tasks[cur["id"]] = cur
                cur = None
                continue
            if line.startswith("<task_done") and "success='0'" in line:
                cur["failed"] = True
            if line.startswith("<klass ") or line.startswith("<method "):
                a = attrs(line)
                ids[a["id"]] = a.get("name")
                continue
            if line.startswith("<parse "):
                depth += 1
                continue
            if line.startswith("</parse>"):
                depth -= 1
                continue
            if depth != 1:
                continue
            if line.startswith("<bc "):
                bci = int(attrs(line)["bci"])
                site = SITES.get(bci)
                if site:
                    cur["sites"][site] = {"rows": [], "emitted": []}
                continue
            if site is None:
                continue
            s = cur["sites"][site]
            if line.startswith("<call ") and "virtual='1'" in line and not s["rows"]:
                a = attrs(line)
                s["count"] = int(a["count"])
                for key, ckey in (("receiver", "receiver_count"), ("receiver2", "receiver2_count")):
                    if key in a:
                        s["rows"].append((ids.get(a[key], a[key]), int(a[ckey])))
            elif line.startswith("<predicted_call ") and "exact='1'" in line:
                s["emitted"].append(ids.get(attrs(line)["klass"]))
            elif line.startswith("<uncommon_trap ") and ("class_check" in line or "bimorphic" in line):
                s["trap_miss"] = True
    return tasks, alive


def site_verdict(s, hot):
    if s is None:
        return "absent"
    if not s["emitted"]:
        return "virtual"
    return "hot-inlined" if hot in s["emitted"] else "other-inlined"


def main():
    path, hot_a, hot_b = sys.argv[1:4]
    tasks, alive = parse(path)
    runs = [t for t in tasks.values() if not t.get("failed")]
    live = [t for t in runs if alive.get(t["id"])]
    std = sorted([t for t in live if not t["osr"]], key=lambda t: t["stamp"])
    osr = sorted([t for t in live if t["osr"]], key=lambda t: t["stamp"])
    final = (std or osr or [None])[-1]
    out = {"c2_compiles": len(runs), "live": [t["id"] for t in live]}
    if final:
        out["final_id"] = final["id"]
        out["final_osr"] = final["osr"]
        out["final_stamp"] = final["stamp"]
        for name, hot in (("A", hot_a), ("B", hot_b)):
            s = final["sites"].get(name)
            out[name] = site_verdict(s, hot)
            if s:
                out[name + "_rows"] = [("HOT" if k == hot else "other", c) for k, c in s["rows"]]
                out[name + "_count"] = s.get("count")
    out["history"] = [
        {"id": t["id"], "osr": t["osr"], "stamp": t["stamp"], "alive": bool(alive.get(t["id"])),
         "A": site_verdict(t["sites"].get("A"), hot_a), "B": site_verdict(t["sites"].get("B"), hot_b)}
        for t in sorted(runs, key=lambda t: t["stamp"])]
    if "--json" in sys.argv:
        print(json.dumps(out))
    else:
        print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
