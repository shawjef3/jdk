#!/bin/bash
# usage: adversarial.sh LABEL JAVA REPS [jvm flags...]
# For each case: REPS JVMs; prints mean of second-half throughput, C2 compiles of work(),
# uncommon traps and C2 make_not_entrant events, per JVM. Appends JSONL to results/adv-LABEL.jsonl
set -u
label=$1; java=$2; reps=$3; shift 3
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$here/results" "$here/logs/adv-$label"
out="$here/results/adv-$label.jsonl"
for c in mega rare3 phase trapflip; do
  for r in $(seq 1 "$reps"); do
    log="$here/logs/adv-$label/$c-r$r.log"
    line=$("$java" -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile="$log" "$@" \
           -cp "$here/out-adv" lottery.Adversarial case=$c durationMs=8000 | tail -1)
    thr=$(sed -n 's/.*secondHalfMean=\([0-9.]*\).*/\1/p' <<<"$line")
    c2=$(grep -c "^<nmethod .*compiler='c2'.*method='lottery.Adversarial work" "$log")
    traps=$(grep -c "^<uncommon_trap thread=" "$log")
    mne=$(grep -c "^<make_not_entrant .*compiler='c2'" "$log")
    echo "{\"label\":\"$label\",\"case\":\"$c\",\"rep\":$r,\"thr\":$thr,\"c2_work\":$c2,\"traps\":$traps,\"c2_not_entrant\":$mne}" >> "$out"
    gzip -f "$log"
  done
done
