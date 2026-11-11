package tech.provokedynamic.weft.benchmark;

import org.openjdk.jmh.infra.Control;

import org.openjdk.jmh.annotations.Benchmark;

/** Symmetric workloads; all workers run the same operation. Choose threads with -t. */
public class WeftBenchmark extends BenchmarkDefaults {
    /** Creates a symmetric workload benchmark for JMH. */
    public WeftBenchmark() {
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
    @Benchmark
    public long readOnly(WorkloadState state, WorkerState worker, Control control) {
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
    @Benchmark
    public long transfer(WorkloadState state, WorkerState worker, Control control) {
        if (inactive(control)) return 0;
        return state.run(worker, true, false);
    }

    /**
     * Reads the whole selected account set, then transfers one unit across its first pair.
     *
     * @param state shared accounts and workload configuration
     * @param worker per-worker access stream and optional diagnostics
     * @param control JMH iteration window controlling admission of new operations
     * @return the account sum before transfer, zero outside the active window, or
     *         {@link Long#MIN_VALUE} when a diagnostic run exhausts its retries
     */
    @Benchmark
    public long mixed(WorkloadState state, WorkerState worker, Control control) {
        if (inactive(control)) return 0;
        return state.run(worker, true, true);
    }
}
