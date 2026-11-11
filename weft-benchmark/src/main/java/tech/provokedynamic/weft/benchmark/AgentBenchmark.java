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
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Direct lambda sites eligible for agent analysis, with an untimed activation check. */
@State(Scope.Benchmark)
public class AgentBenchmark extends BenchmarkDefaults {
    /** Source of protocol-selection hints for the measured transaction. */
    public enum Selection {
        /** Uses the plain atomic overload, allowing the agent to supply hints. */
        DEFAULT,
        /** Passes operation counts explicitly, independently of instrumentation. */
        EXPLICIT
    }

    /** Hint source selected for this benchmark run. */
    @Param({"DEFAULT", "EXPLICIT"}) public Selection selection;
    /** Whether setup must observe instrumentation selecting eager write locking. */
    @Param({"false"}) public boolean expectAgent;
    private static final TransactionHints HINTS = new TransactionHints(1, 3);
    private TVar<Long> first, second, third;

    /** Creates an instrumentation benchmark for JMH. */
    public AgentBenchmark() {
    }

    /**
     * Resets the references and verifies instrumentation through observed lock ownership.
     *
     * <p>The untimed check requires transformed behavior to match {@link #expectAgent},
     * so passing a JVM agent argument alone cannot satisfy the activation check.
     *
     * @throws ReflectiveOperationException if the TVar lock cannot be inspected
     * @throws AssertionError if observed instrumentation differs from the requested state
     */
    @Setup(Level.Iteration)
    public void setup() throws ReflectiveOperationException {
        first = new TVar<>(0L);
        second = new TVar<>(0L);
        third = new TVar<>(0L);
        var field = TVar.class.getDeclaredField("lock");
        field.setAccessible(true);
        var observedLock = (ReentrantReadWriteLock) field.get(first);
        Weft.atomic(() -> {
            long initial = Weft.read(first);
            Weft.write(first, initial);
            Weft.write(second, initial);
            Weft.write(third, initial);
            if (observedLock.isWriteLockedByCurrentThread() != expectAgent)
                throw new AssertionError("Agent activation does not match expectAgent");
        });
    }

    /**
     * Increments one reference and publishes the same value to all three references atomically.
     *
     * @param control JMH iteration window controlling admission of new operations
     */
    @Benchmark
    public void writeTriple(Control control) {
        if (inactive(control)) return;
        if (selection == Selection.EXPLICIT) {
            Weft.atomic(() -> {
                long next = Weft.read(first) + 1;
                Weft.write(first, next);
                Weft.write(second, next);
                Weft.write(third, next);
            }, HINTS);
        } else {
            Weft.atomic(() -> {
                long next = Weft.read(first) + 1;
                Weft.write(first, next);
                Weft.write(second, next);
                Weft.write(third, next);
            });
        }
    }

    /**
     * Checks that the three references agree after all workers finish the iteration.
     *
     * @throws AssertionError if an update was only partially observed
     */
    @TearDown(Level.Iteration)
    public void verify() {
        Weft.atomic(() -> {
            long actual = Weft.read(first);
            if (actual != Weft.read(second) || actual != Weft.read(third))
                throw new AssertionError("Partial triple update");
        });
    }
}
