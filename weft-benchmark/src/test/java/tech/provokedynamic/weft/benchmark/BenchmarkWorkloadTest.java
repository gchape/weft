package tech.provokedynamic.weft.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class BenchmarkWorkloadTest {
    private org.openjdk.jmh.infra.Control activeControl() {
        var control = new org.openjdk.jmh.infra.Control();
        control.startMeasurement = true;
        return control;
    }

    @Test
    void synchronizationPhasesDoNotIssueTransactionsOrCountEvents() {
        var state = state(WorkloadState.Engine.OPTIMISTIC, WorkloadState.Access.HOT, 2);
        var worker = new WorkerState();
        worker.initialize(0);
        var control = new org.openjdk.jmh.infra.Control();
        var benchmark = new WeftBenchmark();
        benchmark.transfer(state, worker, control);
        assertEquals(0, worker.completed);
        control.startMeasurement = true;
        benchmark.transfer(state, worker, control);
        assertEquals(1, worker.completed);
        control.stopMeasurement = true;
        benchmark.transfer(state, worker, control);
        assertEquals(1, worker.completed);
        state.verify();
    }

    private WorkloadState state(WorkloadState.Engine engine, WorkloadState.Access access, int size) {
        var state = new WorkloadState();
        state.engine = engine;
        state.access = access;
        state.diagnostics = true;
        state.initialize(64, size, 4);
        return state;
    }

    @ParameterizedTest
    @CsvSource({"1, 2", "64, 0", "64, 3", "2, 4"})
    void rejectsInvalidWorkloadSizes(int accounts, int size) {
        var state = new WorkloadState();
        state.engine = WorkloadState.Engine.OPTIMISTIC;
        state.access = WorkloadState.Access.UNIFORM;
        assertThrows(IllegalArgumentException.class, () -> state.initialize(accounts, size, 4));
    }

    @ParameterizedTest
    @EnumSource(WorkloadState.Access.class)
    void identicalAccessStreamsProduceEquivalentResults(WorkloadState.Access access) {
        for (int size : new int[]{2, 16}) {
            List<Long> expected = null;
            for (var engine : WorkloadState.Engine.values()) {
                var state = state(engine, access, size);
                var worker = new WorkerState();
                worker.initialize(2);
                var observed = new ArrayList<Long>();
                for (int i = 0; i < 200; i++) {
                    observed.add(state.run(worker, i % 3 != 0, i % 3 != 1));
                }
                state.verify();
                assertEquals(200, worker.completed);
                assertEquals(200, worker.attempts);
                assertEquals(0, worker.exhausted);
                if (expected == null) expected = observed;
                else assertEquals(expected, observed);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(WorkloadState.Engine.class)
    void concurrentTransfersConserveBalances(WorkloadState.Engine engine) throws Exception {
        var state = state(engine, WorkloadState.Access.HOT, 16);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) {
                int index = i;
                tasks.add(pool.submit(() -> {
                    var worker = new WorkerState();
                    worker.initialize(index);
                    for (int j = 0; j < 100; j++) state.run(worker, true, j % 2 == 0);
                    assertEquals(100, worker.completed);
                    assertEquals(0, worker.exhausted);
                }));
            }
            for (var task : tasks) task.get();
        }
        state.verify();
    }

    @Test
    void disjointAccessNeverLeavesWorkerPartition() {
        var state = state(WorkloadState.Engine.OPTIMISTIC, WorkloadState.Access.DISJOINT, 16);
        for (int thread = 0; thread < 4; thread++) {
            var worker = new WorkerState();
            worker.initialize(thread);
            for (int sample = 0; sample < 100; sample++) {
                state.choose(worker);
                for (int offset = 0; offset < state.size; offset++) {
                    int index = state.index(worker, offset);
                    assertTrue(index >= thread * 16 && index < (thread + 1) * 16);
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> state.initialize(2, 16, 4));
    }

    @ParameterizedTest
    @EnumSource(CounterBenchmark.Engine.class)
    void counterValidationDetectsMissingIncrement(CounterBenchmark.Engine engine) {
        var state = new CounterBenchmark();
        state.engine = engine;
        state.setup();
        var worker = new CounterBenchmark.CounterWorker();
        worker.setup(state);
        for (int i = 0; i < 50; i++) state.increment(worker, activeControl());
        assertEquals(50, state.read(worker, activeControl()));
        state.verify();
        worker.increments++;
        assertThrows(AssertionError.class, state::verify);
    }

    @Test
    void agentProbeRejectsMissingInstrumentation() throws Exception {
        var state = new AgentBenchmark();
        state.selection = AgentBenchmark.Selection.DEFAULT;
        state.expectAgent = false;
        state.setup();
        state.writeTriple(activeControl());
        state.selection = AgentBenchmark.Selection.EXPLICIT;
        state.writeTriple(activeControl());
        state.verify();
        state.expectAgent = true;
        assertThrows(AssertionError.class, state::setup);
    }
}
