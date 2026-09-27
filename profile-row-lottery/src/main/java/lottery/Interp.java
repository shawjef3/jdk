package lottery;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A small effect interpreter in the style of a fiber run loop: a sealed node
 * hierarchy, an explicit continuation stack, and one large loop method
 * ({@link #run}) that evaluates nodes until the stack is empty.
 *
 * The loop has two hot megamorphic-prone call sites:
 *   site A: {@code sync.thunk.get()}   (invokeinterface Supplier.get)
 *   site B: {@code k.apply(value)}     (invokeinterface Function.apply)
 * Every caller of the interpreter funnels its lambdas through these two sites.
 */
public final class Interp {

    // ---------------------------------------------------------------- nodes

    public static final int T_SUCCEED = 0, T_SYNC = 1, T_FLATMAP = 2, T_FAIL = 3,
            T_FOLD = 4, T_ENSURING = 5, T_YIELD = 6, T_GETREF = 7, T_SETREF = 8, T_TAG = 9;

    public abstract static sealed class Node
            permits Succeed, Sync, FlatMap, Fail, Fold, Ensuring, Yield, GetRef, SetRef, Tag {
        final int tag;
        Node(int tag) { this.tag = tag; }
    }

    public static final class Succeed extends Node {
        final Object value;
        public Succeed(Object value) { super(T_SUCCEED); this.value = value; }
    }

    public static final class Sync extends Node {
        final Supplier<?> thunk;
        public Sync(Supplier<?> thunk) { super(T_SYNC); this.thunk = thunk; }
    }

    public static final class FlatMap extends Node {
        final Node first;
        final Function<Object, Node> k;
        public FlatMap(Node first, Function<Object, Node> k) { super(T_FLATMAP); this.first = first; this.k = k; }
    }

    public static final class Fail extends Node {
        final Throwable cause;
        public Fail(Throwable cause) { super(T_FAIL); this.cause = cause; }
    }

    public static final class Fold extends Node {
        final Node first;
        final Function<Throwable, Node> onErr;
        final Function<Object, Node> onOk;
        public Fold(Node first, Function<Throwable, Node> onErr, Function<Object, Node> onOk) {
            super(T_FOLD); this.first = first; this.onErr = onErr; this.onOk = onOk;
        }
    }

    public static final class Ensuring extends Node {
        final Node first;
        final Runnable finalizer;
        public Ensuring(Node first, Runnable finalizer) { super(T_ENSURING); this.first = first; this.finalizer = finalizer; }
    }

    public static final class Yield extends Node {
        public Yield() { super(T_YIELD); }
    }

    public static final class GetRef extends Node {
        final int key;
        public GetRef(int key) { super(T_GETREF); this.key = key; }
    }

    public static final class SetRef extends Node {
        final int key;
        final Object value;
        public SetRef(int key, Object value) { super(T_SETREF); this.key = key; this.value = value; }
    }

    public static final class Tag extends Node {
        final String name;
        final Node inner;
        public Tag(String name, Node inner) { super(T_TAG); this.name = name; this.inner = inner; }
    }

    // --------------------------------------------------------------- frames

    static final class FoldFrame {
        final Function<Throwable, Node> onErr;
        final Function<Object, Node> onOk;
        FoldFrame(Function<Throwable, Node> onErr, Function<Object, Node> onOk) { this.onErr = onErr; this.onOk = onOk; }
    }

    static final class EnsuringFrame {
        final Runnable finalizer;
        EnsuringFrame(Runnable finalizer) { this.finalizer = finalizer; }
    }

    static final class TagFrame {
        final String name;
        final long startOps;
        TagFrame(String name, long startOps) { this.name = name; this.startOps = startOps; }
    }

    static final class KFrame {
        final Function<Object, Node> k;
        KFrame(Function<Object, Node> k) { this.k = k; }
    }

    // ---------------------------------------------------------- fiber state

    public static volatile boolean TRACE = false;
    public static volatile boolean INTERRUPT = false;
    public static final int YIELD_OPS = 2048;
    public static final int MAX_DEPTH = 1 << 20;

    long totalOps;
    long yields;
    long failures;
    long finalizersRun;
    final long[] tagCounts = new long[16];
    // tiny open-addressed fiber-local map, inlined into the loop
    int[] refKeys = new int[8];
    Object[] refVals = new Object[8];
    int refSize;
    StringBuilder trace;

    /**
     * The run loop. Deliberately a single large method (see javap -c for size):
     * it is the analogue of an effect system's fiber run loop.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object run(Node start) {
        Object[] stack = new Object[32];
        int sp = 0;
        Node cur = start;
        Object value = null;
        Throwable error = null;
        int ops = 0;
        String currentTag = null;

        while (true) {
            // ---------------------------------------------------- evaluate
            if (cur != null) {
                ops++;
                if (ops >= YIELD_OPS) {
                    totalOps += ops;
                    ops = 0;
                    yields++;
                    if (INTERRUPT) {
                        error = new InterruptedException("fiber interrupted at depth " + sp);
                        cur = null;
                        continue;
                    }
                    if (TRACE) {
                        if (trace == null) trace = new StringBuilder(256);
                        trace.append("yield#").append(yields).append(" depth=").append(sp)
                             .append(" tag=").append(currentTag).append('\n');
                        if (trace.length() > 1 << 16) trace.setLength(0);
                    }
                }
                switch (cur.tag) {
                    case T_SUCCEED: {
                        value = ((Succeed) cur).value;
                        cur = null;
                        break;
                    }
                    case T_SYNC: {
                        Sync s = (Sync) cur;
                        try {
                            value = s.thunk.get();                  // site A
                        } catch (Throwable t) {
                            error = t;
                            failures++;
                        }
                        cur = null;
                        break;
                    }
                    case T_FLATMAP: {
                        FlatMap fm = (FlatMap) cur;
                        if (sp == stack.length) {
                            if (sp >= MAX_DEPTH) {
                                error = new StackOverflowError("interpreter stack depth " + sp);
                                cur = null;
                                break;
                            }
                            Object[] bigger = new Object[stack.length * 2];
                            System.arraycopy(stack, 0, bigger, 0, sp);
                            stack = bigger;
                        }
                        stack[sp++] = fm.k;
                        cur = fm.first;
                        break;
                    }
                    case T_FAIL: {
                        error = ((Fail) cur).cause;
                        if (error == null) error = new NullPointerException("Fail(null)");
                        failures++;
                        cur = null;
                        break;
                    }
                    case T_FOLD: {
                        Fold f = (Fold) cur;
                        if (sp == stack.length) {
                            Object[] bigger = new Object[stack.length * 2];
                            System.arraycopy(stack, 0, bigger, 0, sp);
                            stack = bigger;
                        }
                        stack[sp++] = new FoldFrame(f.onErr, f.onOk);
                        cur = f.first;
                        break;
                    }
                    case T_ENSURING: {
                        Ensuring e = (Ensuring) cur;
                        if (sp == stack.length) {
                            Object[] bigger = new Object[stack.length * 2];
                            System.arraycopy(stack, 0, bigger, 0, sp);
                            stack = bigger;
                        }
                        stack[sp++] = new EnsuringFrame(e.finalizer);
                        cur = e.first;
                        break;
                    }
                    case T_YIELD: {
                        totalOps += ops;
                        ops = 0;
                        yields++;
                        Thread.onSpinWait();
                        value = null;
                        cur = null;
                        break;
                    }
                    case T_GETREF: {
                        int key = ((GetRef) cur).key;
                        int mask = refKeys.length - 1;
                        int h = (key * 0x9E3779B9) >>> 16;
                        Object found = null;
                        for (int probe = 0; probe < refKeys.length; probe++) {
                            int idx = (h + probe) & mask;
                            if (refVals[idx] == null) break;
                            if (refKeys[idx] == key) { found = refVals[idx]; break; }
                        }
                        value = found;
                        cur = null;
                        break;
                    }
                    case T_SETREF: {
                        SetRef sr = (SetRef) cur;
                        if ((refSize + 1) * 2 > refKeys.length) {
                            int[] oldK = refKeys;
                            Object[] oldV = refVals;
                            refKeys = new int[oldK.length * 2];
                            refVals = new Object[oldV.length * 2];
                            refSize = 0;
                            int m = refKeys.length - 1;
                            for (int i = 0; i < oldK.length; i++) {
                                if (oldV[i] == null) continue;
                                int hh = (oldK[i] * 0x9E3779B9) >>> 16;
                                for (int p = 0; ; p++) {
                                    int idx = (hh + p) & m;
                                    if (refVals[idx] == null) { refKeys[idx] = oldK[i]; refVals[idx] = oldV[i]; refSize++; break; }
                                }
                            }
                        }
                        int mask = refKeys.length - 1;
                        int h = (sr.key * 0x9E3779B9) >>> 16;
                        for (int probe = 0; ; probe++) {
                            int idx = (h + probe) & mask;
                            if (refVals[idx] == null) {
                                refKeys[idx] = sr.key; refVals[idx] = sr.value; refSize++; break;
                            }
                            if (refKeys[idx] == sr.key) { refVals[idx] = sr.value; break; }
                        }
                        value = sr.value;
                        cur = null;
                        break;
                    }
                    case T_TAG: {
                        Tag t = (Tag) cur;
                        if (sp == stack.length) {
                            Object[] bigger = new Object[stack.length * 2];
                            System.arraycopy(stack, 0, bigger, 0, sp);
                            stack = bigger;
                        }
                        stack[sp++] = new TagFrame(currentTag, totalOps + ops);
                        currentTag = t.name;
                        tagCounts[t.name.hashCode() & 15]++;
                        if (TRACE) {
                            if (trace == null) trace = new StringBuilder(256);
                            trace.append("enter ").append(t.name).append(" @").append(totalOps + ops).append('\n');
                        }
                        cur = t.inner;
                        break;
                    }
                    default:
                        throw new IllegalStateException("unknown node tag " + cur.tag + " " + cur.getClass());
                }
                if (cur != null) continue;
            }

            // ------------------------------------------------------ unwind
            if (error == null) {
                // success: pop to the next success continuation
                Function<Object, Node> k = null;
                while (sp > 0) {
                    Object fr = stack[--sp];
                    stack[sp] = null;
                    if (fr instanceof FoldFrame ff) {
                        k = ff.onOk;
                        break;
                    } else if (fr instanceof EnsuringFrame ef) {
                        try {
                            ef.finalizer.run();
                            finalizersRun++;
                        } catch (Throwable t) {
                            error = t;
                            failures++;
                            break;
                        }
                    } else if (fr instanceof TagFrame tf) {
                        if (TRACE && trace != null) {
                            trace.append("exit ").append(currentTag).append(" ops=")
                                 .append(totalOps + ops - tf.startOps).append('\n');
                        }
                        currentTag = tf.name;
                    } else if (fr instanceof KFrame kf) {
                        k = kf.k;
                        break;
                    } else {
                        k = (Function<Object, Node>) fr;
                        break;
                    }
                }
                if (error != null) continue;
                if (k == null) {
                    totalOps += ops;
                    return value;
                }
                try {
                    cur = k.apply(value);                            // site B
                } catch (Throwable t) {
                    error = t;
                    failures++;
                    cur = null;
                    continue;
                }
                if (cur == null) {
                    error = new NullPointerException("continuation returned null");
                    failures++;
                }
            } else {
                // failure: pop to the next error handler, running finalizers
                Function<Throwable, Node> h = null;
                Throwable suppressedFrom = null;
                while (sp > 0) {
                    Object fr = stack[--sp];
                    stack[sp] = null;
                    if (fr instanceof FoldFrame ff) {
                        h = ff.onErr;
                        break;
                    } else if (fr instanceof EnsuringFrame ef) {
                        try {
                            ef.finalizer.run();
                            finalizersRun++;
                        } catch (Throwable t) {
                            if (suppressedFrom == null) suppressedFrom = t;
                            else suppressedFrom.addSuppressed(t);
                        }
                    } else if (fr instanceof TagFrame tf) {
                        if (TRACE && trace != null) {
                            trace.append("fail ").append(currentTag).append(": ").append(error).append('\n');
                        }
                        currentTag = tf.name;
                    }
                }
                if (suppressedFrom != null) error.addSuppressed(suppressedFrom);
                if (h == null) {
                    totalOps += ops;
                    Throwable e = error;
                    if (e instanceof RuntimeException re) throw re;
                    if (e instanceof Error er) throw er;
                    throw new RuntimeException(e);
                }
                Throwable e = error;
                error = null;
                try {
                    cur = h.apply(e);                                // site C (error path)
                } catch (Throwable t) {
                    error = t;
                    failures++;
                    cur = null;
                }
            }
        }
    }
}
