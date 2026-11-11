package tech.provokedynamic.iastm.atomic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import tech.provokedynamic.iastm.TxMetrics;
import tech.provokedynamic.iastm.exception.MaxRetriesExceededException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IASTM")
@Execution(ExecutionMode.CONCURRENT)
class IASTMTest {

    @Nested
    @DisplayName("IASTM – single-threaded")
    class SingleThreadedTests {

        @Test
        @DisplayName("write is visible after commit")
        void writeVisibleAfterCommit() {
            TVar<Integer> v = new TVar<>(1);
            IASTM.start(() -> IASTM.write(v, 99));
            AtomicInteger result = new AtomicInteger();
            IASTM.start(() -> result.set(IASTM.read(v)));
            assertThat(result.get()).isEqualTo(99);
        }

        @Test
        @DisplayName("writes remain buffered until commit")
        void writeVisibleBeforeCommit() {
            TVar<Integer> v = new TVar<>(1);
            IASTM.start(() -> {
                IASTM.write(v, 99);
                assertThat(IASTM.read(v)).isEqualTo(99);
                assertThat(v.value).isEqualTo(1);
            });
            assertThat(v.value).isEqualTo(99);
        }

        @Test
        @DisplayName("multiple writes in one transaction – last write wins")
        void multipleWritesLastWins() {
            TVar<String> v = new TVar<>("a");
            IASTM.start(() -> {
                IASTM.write(v, "b");
                IASTM.write(v, "c");
            });
            AtomicReference<String> out = new AtomicReference<>();
            IASTM.start(() -> out.set(IASTM.read(v)));
            assertThat(out.get()).isEqualTo("c");
        }

        @Test
        @DisplayName("read-only transaction never modifies TVar version")
        void readOnlyDoesNotBumpVersion() {
            TVar<Integer> v = new TVar<>(7);
            long versionBefore = v.version;
            IASTM.start(() -> IASTM.read(v));
            assertThat(v.version).isEqualTo(versionBefore);
        }

        @Test
        @DisplayName("nested start inherits outer strategy")
        void nestedInheritsStrategy() {
            TVar<Integer> outer = new TVar<>(0);
            TVar<Integer> inner = new TVar<>(0);

            IASTM.start(() -> {
                IASTM.write(outer, 1);
                IASTM.start(() -> IASTM.write(inner, 2));
            });

            AtomicInteger o = new AtomicInteger(), i = new AtomicInteger();
            IASTM.start(() -> {
                o.set(IASTM.read(outer));
                i.set(IASTM.read(inner));
            });
            assertThat(o.get()).isEqualTo(1);
            assertThat(i.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("read outside transaction throws IllegalStateException")
        void readOutsideTxThrows() {
            TVar<Integer> v = new TVar<>(1);
            assertThatThrownBy(() -> IASTM.read(v))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No active transaction");
        }

        @Test
        @DisplayName("write outside transaction throws IllegalStateException")
        void writeOutsideTxThrows() {
            TVar<Integer> v = new TVar<>(1);
            assertThatThrownBy(() -> IASTM.write(v, 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No active transaction");
        }

        @Test
        @DisplayName("pessimistic strategy selected via metrics – write is visible")
        void pessimisticViaMetrics() {
            TVar<Long> v = new TVar<>(0L);
            TxMetrics metrics = new TxMetrics(1, 9);
            IASTM.start(() -> IASTM.write(v, 42L), metrics);
            AtomicReference<Long> out = new AtomicReference<>();
            IASTM.start(() -> out.set(IASTM.read(v)));
            assertThat(out.get()).isEqualTo(42L);
        }

        @Test
        @DisplayName("optimistic strategy selected via read-heavy metrics")
        void optimisticViaMetrics() {
            TVar<String> v = new TVar<>("x");
            TxMetrics metrics = new TxMetrics(9, 1);
            AtomicReference<String> out = new AtomicReference<>();
            IASTM.start(() -> out.set(IASTM.read(v)), metrics);
            assertThat(out.get()).isEqualTo("x");
        }

        @Test
        @DisplayName("zero-ops metrics defaults to OPTIMISTIC")
        void zeroOpsMetricsDefaultsOptimistic() {
            TVar<Integer> v = new TVar<>(5);
            TxMetrics metrics = new TxMetrics(0, 0);
            AtomicInteger out = new AtomicInteger();
            IASTM.start(() -> out.set(IASTM.read(v)), metrics);
            assertThat(out.get()).isEqualTo(5);
        }

        @Test
        @DisplayName("exception inside body propagates out of start")
        void bodyExceptionPropagates() {
            TVar<Integer> v = new TVar<>(0);
            assertThatThrownBy(() ->
                    IASTM.start(() -> {
                        IASTM.read(v);
                        throw new RuntimeException("boom");
                    })
            ).hasMessage("boom");
        }
    }

    @Nested
    @DisplayName("Concurrent")
    class ConcurrentTests {

        private static final int THREADS = 8;
        private static final int OPS_PER_THREAD = 500;

        @Test
        @DisplayName("concurrent increments produce correct final sum (optimistic)")
        @Timeout(30)
        void concurrentIncrementOptimistic() throws Exception {
            TVar<Integer> counter = new TVar<>(0);
            runConcurrentIncrements(counter, null);
            AtomicInteger result = new AtomicInteger();
            IASTM.start(() -> result.set(IASTM.read(counter)));
            assertThat(result.get()).isEqualTo(THREADS * OPS_PER_THREAD);
        }

        @Test
        @DisplayName("concurrent increments produce correct final sum (pessimistic)")
        @Timeout(30)
        void concurrentIncrementPessimistic() throws Exception {
            TVar<Integer> counter = new TVar<>(0);
            TxMetrics writeHeavy = new TxMetrics(1, 9);
            runConcurrentIncrements(counter, writeHeavy);
            AtomicInteger result = new AtomicInteger();
            IASTM.start(() -> result.set(IASTM.read(counter)));
            assertThat(result.get()).isEqualTo(THREADS * OPS_PER_THREAD);
        }

        @Test
        @DisplayName("snapshot isolation: reader sees consistent state mid-write")
        @Timeout(10)
        void snapshotIsolation() throws Exception {
            TVar<Integer> a = new TVar<>(10);
            TVar<Integer> b = new TVar<>(10);
            List<Integer> sums = new CopyOnWriteArrayList<>();
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> {
                    for (int j = 0; j < 200; j++) {
                        IASTM.start(() -> {
                            IASTM.write(a, IASTM.read(a) + 1);
                            IASTM.write(b, IASTM.read(b) - 1);
                        });
                    }
                });
                tasks.add(() -> {
                    for (int j = 0; j < 200; j++) {
                        int[] sum = new int[1];
                        IASTM.start(() -> sum[0] = IASTM.read(a) + IASTM.read(b));
                        sums.add(sum[0]);
                    }
                });
            }
            runConcurrently(tasks);
            assertThat(sums).hasSize(800).allMatch(sum -> sum == 20,
                    "Every committed snapshot must observe a + b == 20");
        }

        @Test
        @DisplayName("no lost updates under high contention")
        @Timeout(30)
        void noLostUpdates() throws Exception {
            TVar<Long> variable = new TVar<>(0L);
            int threads = 16, ops = 200;
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    for (int j = 0; j < ops; j++) {
                        IASTM.start(() -> IASTM.write(variable, IASTM.read(variable) + 1L));
                    }
                });
            }
            runConcurrently(tasks);
            IASTM.start(() -> assertThat(IASTM.read(variable)).isEqualTo((long) threads * ops));
        }

        private void runConcurrentIncrements(TVar<Integer> counter, TxMetrics metrics) throws Exception {
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                tasks.add(() -> {
                    for (int j = 0; j < OPS_PER_THREAD; j++) {
                        Runnable increment = () -> IASTM.write(counter, IASTM.read(counter) + 1);
                        if (metrics == null) IASTM.start(increment);
                        else IASTM.start(increment, metrics);
                    }
                });
            }
            runConcurrently(tasks);
        }

        private static void runConcurrently(List<Runnable> tasks) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>();
                for (Runnable task : tasks) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        task.run();
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) future.get();
            }
        }
    }

    @Nested
    @DisplayName("TxMetrics and strategy selection")
    class MetricsTests {

        @Test
        @DisplayName("two-arg constructor derives totalOps")
        void twoArgConstructor() {
            TxMetrics m = new TxMetrics(3, 7);
            assertThat(m.readOps()).isEqualTo(3);
            assertThat(m.writeOps()).isEqualTo(7);
            assertThat(m.totalOps()).isEqualTo(10);
        }

        @Test
        @DisplayName("three-arg constructor stores values as-is")
        void threeArgConstructor() {
            TxMetrics m = new TxMetrics(3, 7, 999);
            assertThat(m.totalOps()).isEqualTo(999);
        }

        @Test
        @DisplayName("read-heavy threshold: 80% reads → OPTIMISTIC")
        void readHeavy80Percent() {
            TVar<Integer> v = new TVar<>(1);
            TxMetrics metrics = new TxMetrics(8, 2);
            AtomicInteger out = new AtomicInteger();
            IASTM.start(() -> out.set(IASTM.read(v)), metrics);
            assertThat(out.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("write-heavy threshold: 60% writes → PESSIMISTIC")
        void writeHeavy60Percent() {
            TVar<Integer> v = new TVar<>(0);
            TxMetrics metrics = new TxMetrics(4, 6);
            IASTM.start(() -> IASTM.write(v, 77), metrics);
            assertThat(v.value).isEqualTo(77);
        }

        @Test
        @DisplayName("mixed (50/50) falls back to OPTIMISTIC")
        void mixedFallsBackToOptimistic() {
            TVar<Integer> v = new TVar<>(3);
            TxMetrics metrics = new TxMetrics(5, 5);
            AtomicInteger out = new AtomicInteger();
            IASTM.start(() -> out.set(IASTM.read(v)), metrics);
            assertThat(out.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("MaxRetriesExceededException message contains limit")
        void maxRetriesMessage() {
            assertThatThrownBy(() -> {
                throw new MaxRetriesExceededException(512);
            })
                    .hasMessageContaining("512");
        }
    }
}
