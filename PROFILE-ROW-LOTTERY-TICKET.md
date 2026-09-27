# DRAFT: Receiver type profile rows are first-come and never age; a hot receiver that arrives late is never inlined, and whether it arrives "late" depends on JIT timing

- **Component:** hotspot / compiler
- **Labels:** c2, runtime (profiling: `MethodData` / `ReceiverTypeData`), performance
- **Affects:** jdk-25-ga (verified). Mainline: JDK-8357258 (x86, January 2026) rewrote the x86 receiver profiling into one `MacroAssembler::profile_receiver_type` with CAS-claimed rows, but kept the policy (first-come rows; a full table increments the overflow count), so the behaviour described here is unchanged; not measured on mainline
- **Type:** Enhancement (performance; nondeterministic steady state)
- **Status of this draft:** reproducer, characterization and prototype data complete; aarch64 parts of the prototype not written (x86 only)

## Summary

At an `invokevirtual`/`invokeinterface` site, the interpreter and C1 fill `TypeProfileWidth` receiver rows in first-seen order and never give a row back. A receiver that misses when all rows are held only increments the site's overflow count. C2 then inlines the hot receiver only if it holds a row and ≥ `TypeProfileMajorReceiverPercent` (90%) of the site count, or the site is mono- or bimorphic with no overflow.

So for a call site shared by many callers (an interpreter's "call the user's callback" site, a fiber run loop, a stream pipeline stage), what C2 compiles is decided by which classes happened to reach the site first after the `MethodData` was created. That point itself depends on compile-queue timing. The result is bimodal steady-state throughput across identical JVM starts: 2 to 5 of 24 JVMs land in the virtual-call shape with nothing else different. On this host the inlined shape is 1.36× faster on the reproducer's loop. Once C2 has compiled the site as a plain virtual call, nothing ever revisits it: a virtual call does not trap, and profiled code does not run again, so the startup-era rows stay in charge for the life of the JVM, across later recompiles of the same method.

The OpenJDK wiki names this failure mode without a remedy (https://wiki.openjdk.org/spaces/HotSpot/pages/13729943/TypeProfile):

> A polluted profile contains the first few (2) encountered types with low counts, and a high total count that signals that the ReceiverTypeData structure was too small.

This report adds two things: (1) the pollution does not need to be large or even concurrent. A handful of calls from a background thread, or a single-threaded program whose first ~130 invocations use other classes, is enough, and which JVMs are affected is decided by JIT timing. (2) A prototype that fixes the reproducible cases on x86 with changes confined to the profile miss path and the uncommon-trap handler.

## Field report

Found in ZIO's `FiberRuntime.runLoop` (a 1857-bytecode loop with two such sites), Intel Cascade Lake, Temurin 25.0.4+7, 24 JMH forks per configuration:

| condition | outcome across 24 forks |
| --- | --- |
| default width 2, quiet startup | 3 forks in a cluster 16% below the other 21 |
| `-XX:TypeProfileWidth=8`, quiet startup | no cluster |
| width 2, startup that exercises failure paths first | all 24 forks slow |
| width 8, same startup | 17 fast, 7 slow |

Per-fork `LogCompilation` receiver rows at the two sites predicted each fork's mode in 24 of 24 diagnosed forks on five microarchitectures. Library-side workarounds exist (one call site per receiver class through a `tableswitch`), but the profile policy is the root cause. The reproducer below is plain Java and does not depend on ZIO.

## Reproducer

`profile-row-lottery/` (plain Java 21+, JMH 1.37 for throughput only):

- `Interp.run(Node)`: a 1826-bytecode `while (true)` loop over a sealed node hierarchy (`Succeed`, `Sync`, `FlatMap`, `Fail`, `Fold`, `Ensuring`, `Yield`, `GetRef`, `SetRef`, `Tag`), dispatching on an int tag, with an explicit continuation stack. Two hot sites: **A** = `Supplier.get` at bci 268, **B** = `Function.apply` at bci 1458.
- `Programs`: 16 distinct `Supplier` and 16 distinct `Function` lambda expressions for "other" traffic (k classes at each site), and one hot `Supplier`/`Function` pair for the measured program.
- `Main` (quick per-JVM harness), with these scenarios:
  - `busy`: 500 ms of flat-out startup programs over k classes on one thread, then the hot program.
  - `bg` (`startupMs=0 bgThreads=1`): the hot program from t=0, plus a daemon thread running one 16-step program over the k classes every 1 ms.
  - `pre=X` (single-threaded): X programs over the k classes, then only the hot program.
  - `tag=1`: the hot program also enters a `Tag` node, a run-loop path the startup phase never took.
- `classify.py`: parses `-XX:+LogCompilation`. It takes the last surviving C2 compile of `Interp.run` and reports, at sites A and B, the receiver rows C2 saw and whether it emitted a guarded inline of the hot class (`<predicted_call>` + `<inline_success>`) or a virtual call. `sweep.sh`/`experiments.sh` run 24 JVMs per cell and `summarize.py` prints shape counts.
- `jmh/RunLoopBench`: the same scenarios under JMH (`@Setup(Level.Trial)` runs the startup phase; `@Param k, mode`).

Run:

```
javac -d out src/main/java/lottery/*.java
./sweep.sh demo $JAVA_HOME/bin/java 24 "0 1 2 4" -- measureMs=1000                    # busy
./sweep.sh demo-bg $JAVA_HOME/bin/java 24 "2 4" -- startupMs=0 bgThreads=1 measureMs=1000
python3 summarize.py results/demo*.jsonl
mvn -q package && java -jar target/benchmarks.jar RunLoopBench -p k=0,3 -p mode=busy -f 24 -wi 5 -i 3 -w 1 -r 1
```

A JVM in the losing shape shows, at both sites, rows held by two classes that stopped calling long ago, while the hot class (hundreds of thousands of calls) has no row:

```
<call method='1411' count='233184' virtual='1' receiver='1392' receiver_count='77884' receiver2='1393' receiver2_count='77650'/>
<inline_fail reason='virtual call'/>
```

In the `bg` scenario the stale rows can hold as few as 296 calls each: `Arows=[other 296, other 296]` against ~300k hot calls in the overflow count.

## Host and build

- KVM guest, 4 vCPUs (1 thread per core), CPU reported as "DO-Regular", family 6 model 79 (Broadwell-EP class). No PMU exposed to the guest. Not pinned; CPU steal measured at ~0.6%.
- Debian 13, kernel 6.12.94. JDK built from `jdk-25-ga` on this host (`25-internal-adhoc`), release, gcc 14. Boot JDK: Debian OpenJDK 25.0.4.1.
- Prototype JDKs: the same image with the prototype's `libjvm.so` and a regenerated CDS archive. Every comparison below is same host, same session, one JVM at a time.

## Data: baseline (jdk-25-ga, stock)

Shape of the surviving C2 compile of `Interp.run`, 24 JVMs per cell (inl = hot lambda inlined at both sites, virt = virtual call at both):

| scenario | width | k=0 | k=1 | k=2 | k=4 | k=8 | k=16 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| busy startup | 2 | 24 inl | 24 inl | 24 virt | 24 virt | 24 virt | 24 virt |
| busy startup | 8 | 24 inl | 24 inl | 24 virt | 24 virt | 24 virt | 24 virt |
| background traffic | 2 | | 24 inl | **22 inl / 2 virt** | **21 inl / 3 virt** | 24 inl | |
| background traffic | 8 | | 24 inl | 24 inl | 24 inl | **23 inl / 1 virt** | |
| busy, then new path (`tag=1`) | 2 | | | | k=3: 24 virt | | |

- **Busy startup** is deterministic and wrong: the startup classes hold the rows with ~300k calls each. At k=2 the first C2 compile is bimorphic, the hot class traps it, and the recompile sees the same stale rows plus a large overflow count and emits a virtual call. At k≥3 the first compile is already a virtual call. Width 8 does not help: even with a free row for the hot class, the startup rows' counts (~395k each at k=2) keep its share far below the 90% the major-receiver test needs, and `ciCallProfile` passes C2 only the top 2 rows anyway (`MorphismLimit`).
- **Background traffic** is the lottery. Whether the hot class holds a row depends on whether two background classes reach the site before it does, counted from the moment the MDO exists.
- **`tag=1`**: the new path traps and C2 compiles the loop 4 times in total (24/24 JVMs; 2 in the plain busy case), but every recompile reads the same stale receiver rows.

## Data: what is nondeterministic

Two candidates were tested: (a) when profiling starts relative to the traffic, and (b) interleaving of first arrivals between threads.

Single-threaded `pre=X` (k=2, no other threads touch the loop):

| X (programs over the k classes before the hot program) | 25 | 50 | 100 | 110 | 120 | 130 | 140 | 150 | 200 | 300 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `-XX:-BackgroundCompilation` | inl | inl | inl | inl | inl | **virt** | virt | virt | virt | virt |
| default (background compilation) | inl | inl | inl | inl | inl | inl | inl | **7 inl / 5 virt** | **1 inl / 11 virt** | virt |

(12 JVMs per cell for X ∈ {25, 50, 100, 150, 200, 300}, 6 for the rest; every cell not marked with a split was unanimous.)

With synchronous compilation the outcome is a step function of X: the MDO is created at a fixed invocation count and the result is deterministic. With background compilation the same single-threaded program becomes a lottery over a window of X. The MDO is created when the queued tier-3 compile actually starts, or by the interpreter at `Tier0ProfilingStartPercentage` of the tier-3 thresholds only if `CompileBroker::queue_size(CompLevel_full_optimization) <= Tier0Delay * compiler_count` (`compilationPolicy.cpp`, `should_create_mdo`). Both depend on compiler-thread progress.

**So (a) alone is sufficient, and application threads are not needed.** Thread interleaving (b) matters only through the same timing. With a background thread and `-XX:-BackgroundCompilation`, the outcome becomes deterministic again (24/24 virtual in that configuration), and moving the tier-3 thresholds moves the odds (early thresholds: 1/24 virtual; defaults: 2/24; late: 4/24).

## Size of the prize

JMH, `busy` mode, 24 forks, `-wi 5 -i 3 -w 1 -r 1`, ops/ms (1 op = one Sync + one continuation step). Per-fork means, sorted:

```
stock  k=0 (inlined): 24929 25885 26702 27245 29527 29747 29945 31867 33244 34909 35277 35437 36102 36364 36441 36645 36700 36835 38321 38637 39222 39309 39941 40181   median 35770
stock  k=3 (virtual): 18249 20353 20445 20711 22410 23739 24299 24377 24607 25207 26034 26196 26305 26411 26439 26471 26717 27512 27781 27793 28103 28419 28463 29449   median 26250
```

Inlined / virtual = **1.36×** on this host. The fork-to-fork spread within one shape is wide on this VPS (the lowest fork is ~30% below the median), which is why shapes are classified from compile logs rather than inferred from throughput.

JMH `bg` mode, 24 forks (the lottery, in throughput): see "JMH background mode" below.

## Prototypes

Diffs against jdk-25-ga are in `profile-row-lottery/patches/`. All changes are behind product flags that default to off. Flags-off behaviour was checked equal to stock (`p1off-bg-w2`: 22 inl / 2 virt, same as stock).

### P1: replace an out-counted row (interpreter + C1, x86)

`TypeProfileReplaceShift=N`. On the existing "all rows taken" path, after incrementing the overflow count: if some row's count is ≤ `overflow >> N`, give that row to the missing receiver with count 1 and reset the overflow count to 1 (not 0, so the site still reads as polymorphic to `call_profile_at_bci`). The change is in `InterpreterMacroAssembler::record_item_in_profile_helper` (virtual calls only, not type checks or `ret`) and `LIR_Assembler::emit_profile_call`, 71 lines. The C2 side is unchanged.

The reset-to-1 is deliberate. Resetting only the evicted row would let the next miss evict the new row immediately. Moving the overflow into the new row would invent a major receiver at megamorphic sites. After a replacement the next one needs the overflow to regrow to 2^N × the smallest row, so a site that really is megamorphic churns rows but never shows a majority.

### P3: age a call site's rows when compiled code built from them is discarded (shared, `deoptimization.cpp`)

`TypeProfileTrapDecayShift=S`: in `Deoptimization::uncommon_trap_inner`, when a `class_check`/`bimorphic` trap at a virtual call site makes the nmethod not entrant, shift that site's row counts and overflow count right by S, freeing rows that reach 0, and request `CompilationPolicy::reprofile` so the method runs profiled again before C2 reads the aged rows. This follows the precedent in `ReceiverTypeData::clear_row`, which already clears the overflow count on class unloading because "there was execution phase change". Two options widen the scope:

- `TypeProfileTrapDecayAllSites`: age every virtual call site in the trapping method, not only the one that trapped. This is needed because the site that traps first is only one of the stale ones, and a stale site compiled as a virtual call never traps to report itself.
- `TypeProfileTrapDecayAnyReason`: also age them when the code is discarded for any other reason (for example an `unstable_if` on a path the old phase never took). This is the only signal available when C2's first compile was already a virtual call.

The reprofile request was added after the first measurement round. Without it, P3 with S=32 regressed 1 of 24 JVMs at busy k=1, which baseline gets right in 24/24: C2 recompiled 0.4 s after the rows were cleared, saw an empty profile and emitted a virtual call. With it, 24/24.

### Not prototyped

- **Per-site width** (Kozlov, hotspot-compiler-dev, 31 March 2020, https://mail.openjdk.org/pipermail/hotspot-compiler-dev/2020-March/037737.html: "ideally TypeProfileWidth should be per call site and not per method - and it will require more complicated implementation"). Global width 8 bounds what it could do here. It fixes most of the light-pollution lottery but none of the busy-startup cases, because a row for the hot class is not enough when startup classes out-count it. Kozlov's follow-up in the same thread (037740) reports Renaissance regressions at width 8 and slower tier-3 profiling code.
- **Recency-weighted counts at a site-count threshold** (halving rows periodically): P3 is the event-driven form of this, and P1 covers the case where no event occurs. Periodic ageing would add a counter check to every profiled call.
- **Deterministic tie-break**: it makes the result repeatable but not right, and it cannot help because there is no tie. The losing rows hold ~300 calls against ~300k.

## Data: prototypes

Same scenarios, 24 JVMs per cell, width 2:

| scenario | stock | P1 (N=3) | P3 site-only (S=32) | P3 all sites (S=32) | P3 any reason (S=32) | **P1 + P3 all sites + any reason (N=3, S=8)** |
| --- | --- | --- | --- | --- | --- | --- |
| busy k=1 | 24 inl | 24 inl | | 24 inl | | **24 inl** |
| busy k=2 | 24 virt | 24 virt | 24 mixed (A inl, B virt) | 24 inl | | **24 inl** |
| busy k=4, 8, 16 | 24 virt | 24 virt | | 24 virt (k=4) | | 24 virt |
| background k=1 | 24 inl | 24 inl | | | | **24 inl** |
| background k=2 | 22 inl / 2 virt | **24 inl** | | 23 inl / 1 virt | | **24 inl** |
| background k=4 | 21 inl / 3 virt | **24 inl** | | 20 inl / 4 virt | | **24 inl** |
| background k=8 | 24 inl | 24 inl | | | | **24 inl** |
| busy k=3 then new path | 24 virt | | | | **24 inl** | **24 inl** |

- **P1 fixes the lottery.** Light pollution from background traffic goes to 24/24 at every k.
- **P3 fixes phase changes that leave evidence**: a failed receiver guard, or with `AnyReason` any code-discarding trap. It has to act on all sites, not only the trapping one. The combination fixes every scenario in the reproducer except one.
- **The remaining failure is busy startup with k≥3 and no later trap.** C2's first compile is a virtual call, which never traps, and nothing else in the method deopts. The profile has no way to learn that the traffic changed, because nothing profiles any more. Fixing this needs feedback from C2 code at polymorphic sites, for example a sampled miss counter at the virtual fallback that requests a reprofile when a class outside the profile dominates. That is a C2 change, outside this prototype.
- JMH gap check with the combination on: busy k=0 median 35567 vs k=3 median 24898 ops/ms, 1.43× (stock 1.36×). The inlined shape is not slowed, and k=3 busy is still the virtual shape, as expected.

## Data: effect on other code

### Adversarial microbenchmarks

`Adversarial.java`: 8 s per JVM, second-half mean throughput, 5 JVMs per cell. C2 compiles of the hot method, uncommon traps, and C2 nmethods made not entrant are counted per JVM from `LogCompilation`.

| case | risk it probes | stock | P1 | P3 all sites | P1 + P3 |
| --- | --- | --- | --- | --- | --- |
| `mega`: 8 classes in rotation | row churn, fake bimorphism | 152–195; 2 compiles; 3 traps | 151–192; 2; 2–3 | 158–201; 2; 2 | 174–199; 2; 2–3 |
| `rare3`: A/B 50/50 + C 1/1024 | healthy polymorphic site | 106–128; 2; 3 | 107–130; 2; 2 | 124–138; 2; 2–3 | 131–142; 2; 2–3 |
| `phase`: A, B alternate every 200 ms | forgetting A when B comes back | 1336–1410; 5; 11–12 | 1372–1430; 5; 10–11 | 1350–1412; 5; 10–12 | 1304–1430; 4; 8–9 |
| `trapflip`: stable sites, branch flips every 300 ms | ageing stable sites on unrelated traps | 1599–1624; 4; 9 | 1470–1615; 3–4; 8–9 | 1563–1635; 3–4; 9 | 1565–1612; 4; 8–9 |

No recompile or trap storms. Compile and trap counts are equal or lower in every case, and throughput is within the run-to-run spread. The one exception is `rare3`, which is ~10% faster with P3; that was not investigated further.

### Renaissance

TODO: filled from the Renaissance run (stock vs P1+P3, 15 non-Spark benchmarks, 2 rounds).

### JMH background mode

TODO: filled from `results/jmh/stock-bg.json` and `results/jmh/comb-bg.json`.

## Limits of this work

- x86 only, and against jdk-25-ga. The aarch64 twins (`interp_masm_aarch64.cpp`, `c1_LIRAssembler_aarch64.cpp`) need the same few lines. The trap-side change (P3) is shared code. On mainline, P1 goes in one place, the "table is full" case of `MacroAssembler::profile_receiver_type` (JDK-8357258). Since that code claims rows with a CAS so that a receiver is never duplicated, a replacement there should CAS the old receiver to the new one rather than store it.
- One host, a noisy 4-vCPU VPS without a PMU, so there are no counter-level explanations of the gap.
- The adversarial set is small and 5 JVMs per cell is a screen, not a proof. A real evaluation would need the full Renaissance and DaCapo suites and SPECjbb-style long runs with `TypeProfileTrapDecayAnyReason` in particular, since it widens ageing to every code-discarding trap.
- JEP 515 (AOT method profiling) freezes whatever rows the training run produced. That makes the result repeatable, but only as good as the training run's arrival order.

## Related

- Ludovic Henry, "Polymorphic Guarded Inlining in C2", hotspot-compiler-dev, February–March 2020, starting at https://mail.openjdk.org/pipermail/hotspot-compiler-dev/2020-February/036901.html. Vladimir Ivanov (036970, 11 February 2020) distinguishes the fallback shapes: "Polymorphic shape is stable (no deopts/recompiles involved), but doesn't simplify the CFG around the call site." That stability is what freezes a stale profile here.
- JDK-8380195: bimodal Vector API throughput across identical forks, closed as Not an Issue. Same symptom class, different cause.
- U. Hölzle, C. Chambers, D. Ungar, ECOOP 1991 (polymorphic inline caches). U. Hölzle, D. Ungar, PLDI 1994 (type feedback). E. Barrett et al., "Virtual Machine Warmup Blows Hot and Cold", OOPSLA 2017 (per-process bimodality).
