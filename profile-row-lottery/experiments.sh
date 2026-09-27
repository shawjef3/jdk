#!/bin/bash
# Full classification matrix, 24 JVMs per cell, run sequentially on a quiet host.
# Results: results/<label>.jsonl; summary: python3 summarize.py results/*.jsonl
set -u
cd "$(dirname "$0")"
R=${REPS:-24}
J=/root/jdks
KS="0 1 2 4 8 16"
BG="startupMs=0 bgThreads=1 bgPeriodUs=1000 bgN=16"
M="measureMs=1000"
only=${1:-all}

run() { # label jdk ks [jvm flags...] -- [harness args]
  local label=$1; shift
  if [ "$only" != all ] && [[ "$label" != $only* ]]; then return; fi
  rm -f results/$label.jsonl
  ./sweep.sh "$label" "$@" > /dev/null
  python3 summarize.py results/$label.jsonl | tail -n +2
}

# --- baseline: jdk-25-ga as built here
run base-busy-w2   $J/stock/bin/java $R "$KS" -- $M
run base-busy-w8   $J/stock/bin/java $R "$KS" -XX:TypeProfileWidth=8 -- $M
run base-bg-w2     $J/stock/bin/java $R "1 2 4 8" -- $BG $M
run base-bg-w8     $J/stock/bin/java $R "1 2 4 8" -XX:TypeProfileWidth=8 -- $BG $M
run base-tag-w2    $J/stock/bin/java $R "3" -- tag=1 $M

# --- step 2: what is nondeterministic
# single-threaded: the measuring thread mixes in one k-class program every 10 hot programs
run char-mix       $J/stock/bin/java $R "2" -- startupMs=0 mix=10 bgN=16 $M
run char-mix-sync  $J/stock/bin/java $R "2" -XX:-BackgroundCompilation -- startupMs=0 mix=10 bgN=16 $M
run char-bg-sync   $J/stock/bin/java $R "2" -XX:-BackgroundCompilation -- $BG $M
run char-bg-early  $J/stock/bin/java $R "2" -XX:Tier3InvocationThreshold=20 -XX:Tier3MinInvocationThreshold=10 -XX:Tier3CompileThreshold=200 -XX:Tier3BackEdgeThreshold=6000 -- $BG $M
run char-bg-late   $J/stock/bin/java $R "2" -XX:Tier3InvocationThreshold=2000 -XX:Tier3MinInvocationThreshold=1000 -XX:Tier3CompileThreshold=20000 -XX:Tier3BackEdgeThreshold=600000 -- $BG $M

# --- prototypes
run p1off-bg-w2    $J/row-replace/bin/java $R "2" -- $BG $M
run p1-busy-w2     $J/row-replace/bin/java $R "1 2 4" -XX:TypeProfileReplaceShift=3 -- $M
run p1-bg-w2       $J/row-replace/bin/java $R "1 2 4 8" -XX:TypeProfileReplaceShift=3 -- $BG $M
run p3a-busy-w2    $J/trap-decay/bin/java $R "2" -XX:TypeProfileTrapDecayShift=32 -- $M
run p3b-busy-w2    $J/trap-decay/bin/java $R "1 2 4" -XX:TypeProfileTrapDecayShift=32 -XX:+TypeProfileTrapDecayAllSites -- $M
run p3b-bg-w2      $J/trap-decay/bin/java $R "2 4" -XX:TypeProfileTrapDecayShift=32 -XX:+TypeProfileTrapDecayAllSites -- $BG $M
run p3c-tag-w2     $J/trap-decay/bin/java $R "3" -XX:TypeProfileTrapDecayShift=32 -XX:+TypeProfileTrapDecayAnyReason -- tag=1 $M
C="-XX:TypeProfileReplaceShift=3 -XX:TypeProfileTrapDecayShift=8 -XX:+TypeProfileTrapDecayAllSites -XX:+TypeProfileTrapDecayAnyReason"
run comb-busy-w2   $J/combined/bin/java $R "$KS" $C -- $M
run comb-bg-w2     $J/combined/bin/java $R "1 2 4 8" $C -- $BG $M
run comb-tag-w2    $J/combined/bin/java $R "3" $C -- tag=1 $M
