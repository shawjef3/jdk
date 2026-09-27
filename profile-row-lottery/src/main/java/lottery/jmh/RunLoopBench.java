package lottery.jmh;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import lottery.Interp;
import lottery.Programs;
import org.openjdk.jmh.annotations.*;

/**
 * JMH form of the reproducer. A trial-level setup runs a "startup program"
 * that pushes k distinct lambda classes through the run loop's call sites,
 * then the benchmark measures a program whose lambdas are one class each.
 *
 * mode=busy: startup runs flat out for startupMs on one thread, then stops.
 * mode=bg:   no startup burst; a daemon thread runs a bgN-step program over
 *            the k classes every bgPeriodUs for the whole trial.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RunLoopBench {
    @Param({"0", "1", "2", "4", "8", "16"})
    public int k;

    @Param({"busy"})
    public String mode;

    @Param({"500"})
    public long startupMs;

    @Param({"1000"})
    public long bgPeriodUs;

    @Param({"16"})
    public int bgN;

    @Param({"1000"})
    public int n;

    Interp interp;
    volatile boolean stop;
    Thread bg;

    @Setup(Level.Trial)
    public void setup() {
        interp = new Interp();
        if (mode.equals("busy")) {
            long deadline = System.nanoTime() + startupMs * 1_000_000L;
            Interp s = new Interp();
            long acc = 0;
            while (System.nanoTime() < deadline) {
                acc += (Long) s.run(k == 0 ? Programs.hot(n) : Programs.startup(n, k));
            }
            if (acc == 42) System.out.println();
        } else if (mode.equals("bg") && k > 0) {
            bg = new Thread(() -> {
                Interp s = new Interp();
                long acc = 0;
                while (!stop) {
                    acc += (Long) s.run(Programs.startup(bgN, k));
                    LockSupport.parkNanos(bgPeriodUs * 1000);
                }
                if (acc == 42) System.out.println();
            }, "bg");
            bg.setDaemon(true);
            bg.start();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        stop = true;
        if (bg != null) bg.join();
    }

    /** One program of n steps (each step: one Sync + one continuation). */
    @Benchmark
    @OperationsPerInvocation(1000)
    public Object hotProgram() {
        return interp.run(Programs.hot(n));
    }
}
