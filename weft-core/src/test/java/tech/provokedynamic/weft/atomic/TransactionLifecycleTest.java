package tech.provokedynamic.weft.atomic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import tech.provokedynamic.weft.TransactionHints;

import java.util.ConcurrentModificationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class TransactionLifecycleTest {

    private enum Mode {
        OPTIMISTIC(new TransactionHints(9, 1)), PESSIMISTIC(new TransactionHints(1, 9));

        private final TransactionHints metrics;

        Mode(TransactionHints metrics) {
            this.metrics = metrics;
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void bodyFailureDiscardsWritesAndReleasesLocks(Mode mode) throws Exception {
        var variable = new TVar<>(0);
        var failure = new IllegalStateException("body failed");
        assertThatThrownBy(() -> Weft.atomic(() -> {
            Weft.write(variable, 1);
            throw failure;
        }, mode.metrics)).isSameAs(failure);

        assertThat(variable.value).isZero();
        assertThat(variable.lock.isLocked()).isFalse();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> Weft.atomic(() -> Weft.write(variable, 2), mode.metrics)).get();
        }
        assertThat(variable.value).isEqualTo(2);
    }

    @Test
    void errorAlsoReleasesPessimisticLocks() {
        var variable = new TVar<>(0);
        var failure = new AssertionError("body failed");
        assertThatThrownBy(() -> Weft.atomic(() -> {
            Weft.write(variable, 1);
            throw failure;
        }, Mode.PESSIMISTIC.metrics)).isSameAs(failure);
        assertThat(variable.lock.isLocked()).isFalse();
        assertThat(variable.value).isZero();
    }

    @Test
    void retryCleansUpBeforeStartingTheNextAttempt() {
        var variable = new TVar<>(0);
        var attempts = new AtomicInteger();
        Weft.atomic(() -> {
            Weft.write(variable, 1);
            assertThat(variable.lock.getHoldCount()).isEqualTo(1);
            if (attempts.getAndIncrement() == 0) throw new TransactionConflictException();
        }, Mode.PESSIMISTIC.metrics);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(variable.lock.isLocked()).isFalse();
        assertThat(variable.value).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void nestedStartReadsOuterWritesAndDefersPublication(Mode mode) {
        var variable = new TVar<>(0);
        Weft.atomic(() -> {
            Weft.write(variable, 1);
            Weft.atomic(() -> {
                assertThat(Weft.read(variable)).isEqualTo(1);
                Weft.write(variable, 2);
            }, mode == Mode.OPTIMISTIC ? Mode.PESSIMISTIC.metrics : Mode.OPTIMISTIC.metrics);
            assertThat(variable.value).isZero();
            assertThat(Weft.read(variable)).isEqualTo(2);
            assertThat(variable.lock.isHeldByCurrentThread()).isEqualTo(mode == Mode.PESSIMISTIC);
        }, mode.metrics);
        assertThat(variable.value).isEqualTo(2);
        assertThat(variable.lock.isLocked()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void outerFailureRollsBackNestedWrites(Mode mode) {
        var outer = new TVar<>(0);
        var inner = new TVar<>(0);
        assertThatThrownBy(() -> Weft.atomic(() -> {
            Weft.write(outer, 1);
            Weft.atomic(() -> Weft.write(inner, 2));
            throw new IllegalStateException("abort outer");
        }, mode.metrics)).hasMessage("abort outer");
        assertThat(outer.value).isZero();
        assertThat(inner.value).isZero();
        assertThat(outer.lock.isLocked()).isFalse();
        assertThat(inner.lock.isLocked()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void bufferedNullIsReadable(Mode mode) {
        var variable = new TVar<>("initial");
        Weft.atomic(() -> {
            Weft.write(variable, null);
            assertThat(Weft.read(variable)).isNull();
        }, mode.metrics);
        Weft.atomic(() -> assertThat(Weft.read(variable)).isNull());
    }

    @Test
    void applicationConcurrentModificationExceptionIsNotRetried() {
        var attempts = new AtomicInteger();
        var failure = new ConcurrentModificationException("application failure");
        assertThatThrownBy(() -> Weft.atomic(() -> {
            attempts.incrementAndGet();
            throw failure;
        })).isSameAs(failure);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"8, 2, false", "4, 6, true", "5, 5, false", "0, 0, false"})
    void metricsSelectLockTiming(int reads, int writes, boolean eagerLock) {
        var variable = new TVar<>(0);
        Weft.atomic(() -> {
            Weft.write(variable, 1);
            assertThat(variable.lock.isHeldByCurrentThread()).isEqualTo(eagerLock);
        }, new TransactionHints(reads, writes));
        assertThat(variable.lock.isLocked()).isFalse();
    }

    @Test
    void readOnlyCommitValidatesItsReadSet() {
        var variable = new TVar<>(0);
        try (var tx = new Transaction(ConcurrencyProtocol.Optimistic.INSTANCE)) {
            assertThat(tx.read(variable)).isZero();
            Weft.atomic(() -> Weft.write(variable, 1));
            assertThatThrownBy(tx::commit).isInstanceOf(TransactionConflictException.class);
        }
    }

    @Test
    void pessimisticReadRejectsVersionsNewerThanItsSnapshot() {
        var variable = new TVar<>(0);
        try (var tx = new Transaction(ConcurrencyProtocol.PessimisticWrite.INSTANCE)) {
            Weft.atomic(() -> Weft.write(variable, 1));
            assertThatThrownBy(() -> tx.read(variable)).isInstanceOf(TransactionConflictException.class);
        }
    }

    @Test
    void catchingAReadConflictCannotMakeTheAttemptCommittable() {
        var read = new TVar<>(0);
        var write = new TVar<>(0);
        try (var tx = new Transaction(ConcurrencyProtocol.Optimistic.INSTANCE)) {
            tx.write(write, 1);
            Weft.atomic(() -> Weft.write(read, 2));
            assertThatThrownBy(() -> tx.read(read)).isInstanceOf(TransactionConflictException.class);
            assertThatThrownBy(tx::commit).isInstanceOf(TransactionConflictException.class);
        }
        assertThat(write.value).isZero();
    }

    @Test
    void conflictReleasesPartiallyAcquiredCommitLocks() throws Exception {
        var first = new TVar<>(0);
        var second = new TVar<>(0);
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> {
                second.lock.lock();
                try {
                    acquired.countDown();
                    release.await();
                } finally {
                    second.lock.unlock();
                }
                return null;
            });
            try {
                acquired.await();
                try (var tx = new Transaction(ConcurrencyProtocol.Optimistic.INSTANCE)) {
                    tx.write(second, 2);
                    tx.write(first, 1);
                    assertThatThrownBy(tx::commit).isInstanceOf(TransactionConflictException.class);
                }
                assertThat(first.lock.isLocked()).isFalse();
                assertThat(first.value).isZero();
                assertThat(second.value).isZero();
                try (var tx = new Transaction(ConcurrencyProtocol.PessimisticWrite.INSTANCE)) {
                    assertThatThrownBy(() -> tx.read(second)).isInstanceOf(TransactionConflictException.class);
                }
            } finally {
                release.countDown();
            }
            holder.get();
        }
    }
}
