#!/bin/bash
# usage: sweep.sh LABEL JAVA REPS "K LIST" [extra JVM flags...] [-- extra harness args]
# Runs REPS JVMs per k, classifies each from LogCompilation, appends JSONL to results/LABEL.jsonl
set -u
label=$1; java=$2; reps=$3; ks=$4; shift 4
jvmflags=(); hargs=()
while [ $# -gt 0 ]; do
  if [ "$1" = "--" ]; then shift; hargs=("$@"); break; fi
  jvmflags+=("$1"); shift
done
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$here/results" "$here/logs/$label"
out="$here/results/$label.jsonl"
for k in $ks; do
  for r in $(seq 1 "$reps"); do
    log="$here/logs/$label/k$k-r$r.log"
    line=$("$java" -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile="$log" \
           "${jvmflags[@]}" -cp "$here/out" lottery.Main k=$k "${hargs[@]}" | tail -1)
    final=$(sed -n 's/.*final=\([0-9.]*\).*/\1/p' <<<"$line")
    hotA=$(sed -n 's/.*hotA=\([^ ]*\).*/\1/p' <<<"$line")
    hotB=$(sed -n 's/.*hotB=\([^ ]*\).*/\1/p' <<<"$line")
    cls=$(python3 "$here/classify.py" "$log" "$hotA" "$hotB" --json)
    echo "{\"label\":\"$label\",\"k\":$k,\"rep\":$r,\"final\":$final,\"cls\":$cls}" >> "$out"
    python3 -c "import json,sys; d=json.loads(sys.argv[1]); c=d['cls']; print(d['label'],'k=%d r=%d'%(d['k'],d['rep']),'thr=%s'%d['final'],'A=%s'%c.get('A'),'B=%s'%c.get('B'),'Arows=%s'%c.get('A_rows'),'Brows=%s'%c.get('B_rows'),'c2=%s'%c['c2_compiles'])" "$(tail -1 "$out")"
    gzip -f "$log"
  done
done
