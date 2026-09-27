package lottery;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Cases chosen to hurt the prototypes, not to help them. Each runs one hot
 * method, work(), for durationMs and prints throughput per 250 ms window;
 * recompilations and traps are counted from -XX:+LogCompilation afterwards.
 *
 *   mega     : one site, 8 receiver classes in rotation (uniform megamorphic).
 *              Risk for row replacement: row churn, transient fake bimorphism.
 *   rare3    : one site, classes A/B 50/50 plus C once per 1024 calls.
 *              A healthy polymorphic site; should be unchanged by every prototype.
 *   phase    : one site whose receiver alternates between A and B every 200 ms.
 *              Risk for trap decay: forgetting A when B arrives -> recompile cycles.
 *   trapflip : two stable monomorphic sites plus a branch that flips every
 *              300 ms. Risk for decay-on-any-trap: every flip ages stable sites.
 *
 * Usage: java lottery.Adversarial case=mega durationMs=8000
 */
public final class Adversarial {
    static final IntUnaryOperator[] OPS = {
        x -> x + 1, x -> x ^ 0x55, x -> x * 3, x -> x - 7,
        x -> x << 1, x -> x >>> 1, x -> ~x, x -> x | 9,
    };
    static final IntUnaryOperator A = x -> x * 31 + 7;
    static final IntUnaryOperator B = x -> (x ^ 0x2545F491) + 3;
    static final IntUnaryOperator C = x -> x - 1;
    static final IntUnaryOperator S1 = x -> x + 17;
    static final IntUnaryOperator S2 = x -> x * 5;

    static volatile int phase;     // phase / trapflip selector, flipped by a timer thread
    static int sink;

    /** The measured method: the call site under test is inside this loop. */
    static int work(String kase, int n, int seed) {
        int acc = seed;
        switch (kase) {
            case "mega":
                for (int i = 0; i < n; i++) acc = OPS[i & 7].applyAsInt(acc);
                break;
            case "rare3":
                for (int i = 0; i < n; i++) {
                    IntUnaryOperator f = (i & 1023) == 0 ? C : ((i & 1) == 0 ? A : B);
                    acc = f.applyAsInt(acc);
                }
                break;
            case "phase": {
                IntUnaryOperator f = phase == 0 ? A : B;
                for (int i = 0; i < n; i++) acc = f.applyAsInt(acc);
                break;
            }
            case "trapflip": {
                boolean flip = phase != 0;
                for (int i = 0; i < n; i++) {
                    acc = S1.applyAsInt(acc);
                    if (flip) {            // taken only in odd phases: unstable_if on every flip
                        acc = Integer.rotateLeft(acc, 3) ^ i;
                    }
                    acc = S2.applyAsInt(acc);
                }
                break;
            }
            default:
                throw new IllegalArgumentException(kase);
        }
        return acc;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (String s : args) { int e = s.indexOf('='); a.put(s.substring(0, e), s.substring(e + 1)); }
        String kase = a.getOrDefault("case", "mega");
        long durationMs = Long.parseLong(a.getOrDefault("durationMs", "8000"));
        long flipMs = kase.equals("phase") ? 200 : 300;
        if (kase.equals("phase") || kase.equals("trapflip")) {
            Thread t = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(flipMs); } catch (InterruptedException e) { return; }
                    phase ^= 1;
                }
            });
            t.setDaemon(true);
            t.start();
        }
        final int n = 1000;
        long end = System.nanoTime() + durationMs * 1_000_000L;
        StringBuilder out = new StringBuilder("windows(calls/ns*1000):");
        double sum = 0; int windows = 0; double last = 0;
        int acc = 0;
        long startMeasure = System.nanoTime() + durationMs * 1_000_000L / 2;   // average the second half
        while (System.nanoTime() < end) {
            long w0 = System.nanoTime(), wEnd = w0 + 250_000_000L, calls = 0;
            while (System.nanoTime() < wEnd) { acc = work(kase, n, acc); calls += n; }
            last = calls / ((System.nanoTime() - w0) / 1000.0);
            out.append(String.format(" %.0f", last));
            if (w0 >= startMeasure) { sum += last; windows++; }
        }
        sink = acc;
        System.out.println(out);
        System.out.printf("case=%s secondHalfMean=%.1f%n", kase, windows == 0 ? last : sum / windows);
        System.exit(0);
    }
}
