package tech.provokedynamic.weft.benchmark;

import org.openjdk.jmh.infra.Control;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;

/** Concurrent readers and writers sharing the same account pool across all groups. */
public class ReaderWriterBenchmark extends BenchmarkDefaults {
    /** Creates a grouped reader/writer benchmark for JMH. */
    public ReaderWriterBenchmark() {
    }

    /**
     * Reads and sums the selected account set in one consistent operation.
     *
     * @param state shared accounts and workload configuration
     * @param worker per-worker access stream and optional diagnostics
     * @param control JMH iteration window controlling admission of new operations
     * @return the account sum, zero outside the active window, or {@link Long#MIN_VALUE}
     *         when a diagnostic run exhausts its retries
     */
    @Benchmark @Group("readWrite") @GroupThreads(3)
    public long read(WorkloadState state, WorkerState worker, Control control) {
        if (inactive(control)) return 0;
        return state.run(worker, false, true);
    }

    /**
     * Transfers one unit across each pair in the selected account set.
     *
     * @param state shared accounts and workload configuration
     * @param worker per-worker access stream and optional diagnostics
     * @param control JMH iteration window controlling admission of new operations
     * @return zero, or {@link Long#MIN_VALUE} when a diagnostic run exhausts its retries
     */
    @Benchmark @Group("readWrite") @GroupThreads()
    public long write(WorkloadState state, WorkerState worker, Control control) {
        if (inactive(control)) return 0;
        return state.run(worker, true, false);
    }
}
