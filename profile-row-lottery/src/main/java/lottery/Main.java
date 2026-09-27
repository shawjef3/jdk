package lottery;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Quick per-JVM harness (no JMH): runs a startup phase that pushes k distinct
 * lambda classes through the interpreter's call sites, then the measured
 * program, printing throughput. Classify the JVM's compiled shape from
 * -XX:+LogCompilation (see classify.py).
 *
 * Usage: java lottery.Main k=4 startupMs=500 measureMs=3000 threads=1 n=1000
 */
public final class Main {
    static volatile long sink;

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (String s : args) { int e = s.indexOf('='); a.put(s.substring(0, e), s.substring(e + 1)); }
        int k = Integer.parseInt(a.getOrDefault("k", "0"));
        long startupMs = Long.parseLong(a.getOrDefault("startupMs", "500"));
        long measureMs = Long.parseLong(a.getOrDefault("measureMs", "3000"));
        int threads = Integer.parseInt(a.getOrDefault("threads", "1"));
        int n = Integer.parseInt(a.getOrDefault("n", "1000"));
        // background traffic: bgThreads daemon threads each run a bgN-step program over the
        // k startup classes every bgPeriodUs, concurrently with the measured program
        int bgThreads = Integer.parseInt(a.getOrDefault("bgThreads", "0"));
        long bgPeriodUs = Long.parseLong(a.getOrDefault("bgPeriodUs", "1000"));
        int bgN = Integer.parseInt(a.getOrDefault("bgN", "16"));
        // single-threaded alternative to bgThreads: the measuring thread itself runs
        // one bgN-step program over the k classes after every 'mix' hot programs
        int mix = Integer.parseInt(a.getOrDefault("mix", "0"));
        // hot program count before the first mixed-in program (to shift the schedule)
        int mixOffset = Integer.parseInt(a.getOrDefault("mixOffset", "0"));
        // tag=1: the measured program is wrapped in a Tag node, a path of the run loop
        // that the startup phase never takes (a phase change that also changes control flow)
        boolean tag = a.getOrDefault("tag", "0").equals("1");
        // pre=X: the measuring thread first runs X bgN-step programs over the k classes,
        // then only the hot program (single-threaded; no startup or bg threads needed)
        int pre = Integer.parseInt(a.getOrDefault("pre", "0"));
        if (k > Programs.MAX_K) throw new IllegalArgumentException("k <= " + Programs.MAX_K);
        for (int t = 0; k > 0 && t < bgThreads; t++) {
            Thread th = new Thread(() -> {
                Interp in = new Interp();
                long acc = 0;
                while (true) {
                    acc += (Long) in.run(Programs.startup(bgN, k));
                    sink = acc;
                    java.util.concurrent.locks.LockSupport.parkNanos(bgPeriodUs * 1000);
                }
            }, "bg-" + t);
            th.setDaemon(true);
            th.start();
        }

        // ---- startup phase
        long t0 = System.nanoTime();
        long deadline = t0 + startupMs * 1_000_000L;
        int startupThreads = startupMs > 0 ? threads : 0;
        CountDownLatch done = new CountDownLatch(startupThreads);
        for (int t = 0; t < startupThreads; t++) {
            Thread th = new Thread(() -> {
                Interp in = new Interp();
                long acc = 0;
                while (System.nanoTime() < deadline) {
                    Object r = in.run(k == 0 ? Programs.hot(n) : Programs.startup(n, k));
                    acc += (Long) r;
                }
                sink += acc;
                done.countDown();
            }, "startup-" + t);
            th.start();
        }
        done.await();

        // ---- measured phase (main thread), throughput in 250 ms windows
        Interp in = new Interp();
        for (int i = 0; k > 0 && i < pre; i++) {
            sink += (Long) in.run(Programs.startup(bgN, k));
        }
        long end = System.nanoTime() + measureMs * 1_000_000L;
        long acc = 0;
        long programs = -mixOffset;
        StringBuilder out = new StringBuilder("windows(steps/us):");
        double last = 0;
        while (System.nanoTime() < end) {
            long w0 = System.nanoTime(), wEnd = w0 + 250_000_000L, steps = 0;
            while (System.nanoTime() < wEnd) {
                acc += (Long) in.run(tag ? new Interp.Tag("hot", Programs.hot(n)) : Programs.hot(n));
                steps += n;
                if (mix > 0 && k > 0 && ++programs > 0 && programs % mix == 0) {
                    acc += (Long) in.run(Programs.startup(bgN, k));
                }
            }
            last = steps / ((System.nanoTime() - w0) / 1000.0);
            out.append(String.format(" %.1f", last));
        }
        sink += acc;
        System.out.println(out);
        System.out.printf("k=%d threads=%d final=%.2f %s%n", k, threads, last, Programs.hotClassNames());
        System.exit(0);
    }
}
