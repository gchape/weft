package tech.provokedynamic.weft.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Control;
import tech.provokedynamic.weft.TransactionHints;
import tech.provokedynamic.weft.atomic.TVar;
import tech.provokedynamic.weft.atomic.Weft;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Equivalent single-value operations, including JDK atomic and lock baselines. */
@State(Scope.Benchmark)
public class CounterBenchmark extends BenchmarkDefaults {
    /** Implementation used for a shared single-value operation. */
    public enum Engine {
        /** Weft transactions with write locks deferred until commit. */
        OPTIMISTIC,
        /** Weft transactions with eager write locking. */
        PESSIMISTIC,
        /** A plain value protected by a reentrant lock. */
        LOCKED,
        /** A JDK atomic counter. */
        ATOMIC
    }

    /** Counter implementation selected for this benchmark run. */
    @Param({"OPTIMISTIC", "PESSIMISTIC", "LOCKED", "ATOMIC"}) public Engine engine;
    private TVar<Long> value;
    private AtomicLong atomic;
    private long locked;
    private final ReentrantLock lock = new ReentrantLock();
    private TransactionHints hints;
    private final List<CounterWorker> workers = new CopyOnWriteArrayList<>();

    /** Creates a counter benchmark for JMH. */
    public CounterBenchmark() {
    }

    /** Resets the counters and worker registry and chooses hints for the selected engine. */
    @Setup(Level.Iteration)
    public void setup() {
        value = new TVar<>(0L);
        atomic = new AtomicLong();
        locked = 0;
        workers.clear();
        hints = engine == Engine.PESSIMISTIC ? new TransactionHints(0, 1) : new TransactionHints(1, 0);
    }

    void register(CounterWorker worker) { workers.add(worker); }

    /** Per-worker completed-increment count and storage for observed values. */
    @State(Scope.Thread)
    public static class CounterWorker {
        long increments;
        long result;

        /** Creates counter state for one JMH worker. */
        public CounterWorker() {
        }

        /**
         * Resets this worker and registers its increment count for iteration verification.
         *
         * @param state shared counter benchmark
         */
        @Setup(Level.Iteration)
        public void setup(CounterBenchmark state) {
            increments = result = 0;
            state.register(this);
        }
    }

    /**
     * Increments the shared value once and records a successfully completed update.
     *
     * @param worker per-worker completion count
     * @param control JMH iteration window controlling admission of new operations
     */
    @Benchmark
    public void increment(CounterWorker worker, Control control) {
        if (inactive(control)) return;
        switch (engine) {
            case ATOMIC -> atomic.incrementAndGet();
            case LOCKED -> {
                lock.lock();
                try { locked++; } finally { lock.unlock(); }
            }
            default -> Weft.atomic(() -> Weft.write(value, Weft.read(value) + 1), hints);
        }
        worker.increments++;
    }

    /**
     * Reads the shared value using the selected implementation.
     *
     * @param worker per-worker result storage
     * @param control JMH iteration window controlling admission of new operations
     * @return the observed value, or zero outside the active iteration window
     */
    @Benchmark
    public long read(CounterWorker worker, Control control) {
        if (inactive(control)) return 0;
        switch (engine) {
            case ATOMIC -> worker.result = atomic.get();
            case LOCKED -> {
                lock.lock();
                try { worker.result = locked; } finally { lock.unlock(); }
            }
            default -> Weft.atomic(() -> worker.result = Weft.read(value), hints);
        }
        return worker.result;
    }

    /**
     * Compares the final value with the sum of all completed worker increments.
     *
     * @throws AssertionError if any completed increment was lost
     */
    @TearDown(Level.Iteration)
    public void verify() {
        long expected = workers.stream().mapToLong(w -> w.increments).sum();
        long[] actual = new long[1];
        switch (engine) {
            case ATOMIC -> actual[0] = atomic.get();
            case LOCKED -> actual[0] = locked;
            default -> Weft.atomic(() -> actual[0] = Weft.read(value));
        }
        if (actual[0] != expected) throw new AssertionError("Lost increments: " + actual[0] + " != " + expected);
    }
}
