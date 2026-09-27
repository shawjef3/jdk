#!/bin/bash
# usage: renaissance.sh LABEL JAVA ROUND [jvm flags...]  -> /root/bench/ren/LABEL/ROUND/<bench>.{json,out}
set -u
label=$1; java=$2; round=$3; shift 3
BENCHES="scrabble future-genetic akka-uct scala-doku fj-kmeans rx-scrabble reactors scala-stm-bench7 par-mnemonics scala-kmeans philosophers mnemonics dotty finagle-chirper finagle-http"
d=/root/bench/ren/$label/$round; mkdir -p $d
cd /root/bench
for b in $BENCHES; do
  "$java" -XX:+CITime "$@" -jar renaissance.jar -r ${REPS:-10} --json $d/$b.json $b > $d/$b.out 2>&1
  echo "$label $round $b rc=$?"
done
