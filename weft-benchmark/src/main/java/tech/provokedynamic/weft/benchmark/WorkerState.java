package tech.provokedynamic.weft.benchmark;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.ThreadParams;
import java.util.SplittableRandom;

/** Worker-local access stream and optional diagnostic event counts. */
@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
public class WorkerState {
    /** Body executions, including attempts that later retry, when diagnostics are enabled. */
    public long attempts;
    /** Successfully completed workload calls when diagnostics are enabled. */
    public long completed;
    /** Completed write calls that transferred no balance when diagnostics are enabled. */
    public long noOps;
    /** Calls that exhausted their transaction retry budget during a diagnostic run. */
    public long exhausted;
    SplittableRandom random;
    int threadIndex;
    int start;
    long sum;
    boolean transferred;
    boolean reverse;

    /** Creates access and diagnostic state for one JMH worker. */
    public WorkerState() {
    }

    /**
     * Resets event counters and creates a reproducible random stream for this worker.
     *
     * @param params JMH worker identity supplying the thread index
     */
    @Setup(Level.Iteration)
    public void setup(ThreadParams params) { initialize(params.getThreadIndex()); }

    void initialize(int index) {
        threadIndex = index;
        random = new SplittableRandom(0x5EEDL + index);
        attempts = completed = noOps = exhausted = 0;
    }
}
