package lottery;

import java.util.function.Function;
import java.util.function.Supplier;
import lottery.Interp.*;

/**
 * Programs for the interpreter. Each lambda expression below is its own
 * hidden class, so {@code k} distinct startup classes means k distinct
 * receiver types at site A (Supplier) and k at site B (Function).
 */
public final class Programs {
    public static final int MAX_K = 16;

    public static final class Step {
        int i, n, k;
        long acc;
        public Step(int n, int k) { this.n = n; this.k = k; }
        Step bump(int j) { acc = acc * 31 + j + i; i++; return this; }
        Step hot() { acc = acc * 31 + i; i++; return this; }
    }

    // ---- startup: k distinct Supplier classes and k distinct Function classes
    static Supplier<Object> startupSupplier(int j, Step st) {
        switch (j) {
            case 0: return () -> st.bump(0);
            case 1: return () -> st.bump(1);
            case 2: return () -> st.bump(2);
            case 3: return () -> st.bump(3);
            case 4: return () -> st.bump(4);
            case 5: return () -> st.bump(5);
            case 6: return () -> st.bump(6);
            case 7: return () -> st.bump(7);
            case 8: return () -> st.bump(8);
            case 9: return () -> st.bump(9);
            case 10: return () -> st.bump(10);
            case 11: return () -> st.bump(11);
            case 12: return () -> st.bump(12);
            case 13: return () -> st.bump(13);
            case 14: return () -> st.bump(14);
            case 15: return () -> st.bump(15);
            default: throw new IllegalArgumentException();
        }
    }

    @SuppressWarnings("unchecked")
    static final Function<Object, Node>[] STARTUP_K = new Function[] {
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v),
        v -> nextStartup((Step) v)
    };

    static Node nextStartup(Step st) {
        if (st.i >= st.n) return new Succeed(st.acc);
        int j = st.i % st.k;
        return new FlatMap(new Sync(startupSupplier(j, st)), STARTUP_K[j]);
    }

    public static Node startup(int n, int k) {
        return nextStartup(new Step(n, k));
    }

    // ---- the measured program: one Supplier class, one Function class
    static final Function<Object, Node> HOT_K = v -> nextHot((Step) v);

    static Node nextHot(Step st) {
        if (st.i >= st.n) return new Succeed(st.acc);
        return new FlatMap(new Sync(() -> st.hot()), HOT_K);
    }

    /** Hidden-class names of the hot lambdas at site A and site B, for the classifier. */
    public static String hotClassNames() {
        FlatMap fm = (FlatMap) nextHot(new Step(1, 1));
        return "hotA=" + ((Sync) fm.first).thunk.getClass().getName() + " hotB=" + fm.k.getClass().getName();
    }

    public static Node hot(int n) {
        return nextHot(new Step(n, 1));
    }
}
