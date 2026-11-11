package tech.provokedynamic.weft.atomic;

import org.jspecify.annotations.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.provokedynamic.weft.TransactionHints;
import tech.provokedynamic.weft.exception.VersionEvictedException;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class TransactionLifecycleTest {

    private enum Mode {
        OPTIMISTIC(new TransactionHints(9, 1)), PESSIMISTIC(new TransactionHints(1, 9));

        private final TransactionHints hints;

        Mode(TransactionHints hints) {
            this.hints = hints;
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
        }, mode.hints)).isSameAs(failure);

        assertThat(variable.value).isZero();
        assertThat(variable.lock.isWriteLocked()).isFalse();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> Weft.atomic(() -> Weft.write(variable, 2), mode.hints)).get();
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
        }, Mode.PESSIMISTIC.hints)).isSameAs(failure);
        assertThat(variable.lock.isWriteLocked()).isFalse();
        assertThat(variable.value).isZero();
    }

    @Test
    void retryCleansUpBeforeStartingTheNextAttempt() {
        var variable = new TVar<>(0);
        var attempts = new AtomicInteger();
        Weft.atomic(() -> {
            Weft.write(variable, 1);
            assertThat(variable.lock.getWriteHoldCount()).isEqualTo(1);
            if (attempts.getAndIncrement() == 0) throw new TransactionConflictException();
        }, Mode.PESSIMISTIC.hints);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(variable.lock.isWriteLocked()).isFalse();
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
            }, mode == Mode.OPTIMISTIC ? Mode.PESSIMISTIC.hints : Mode.OPTIMISTIC.hints);
            assertThat(variable.value).isZero();
            assertThat(Weft.read(variable)).isEqualTo(2);
            assertThat(variable.lock.isWriteLockedByCurrentThread()).isEqualTo(mode == Mode.PESSIMISTIC);
        }, mode.hints);
        assertThat(variable.value).isEqualTo(2);
        assertThat(variable.lock.isWriteLocked()).isFalse();
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
        }, mode.hints)).hasMessage("abort outer");
        assertThat(outer.value).isZero();
        assertThat(inner.value).isZero();
        assertThat(outer.lock.isWriteLocked()).isFalse();
        assertThat(inner.lock.isWriteLocked()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void bufferedNullIsReadable(Mode mode) {
        var variable = new TVar<@Nullable String>("initial");
        Weft.atomic(() -> {
            Weft.write(variable, null);
            assertNull(Weft.read(variable));
        }, mode.hints);
        Weft.atomic(() -> assertNull(Weft.read(variable)));
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
    @EnumSource(Mode.class)
    void wrappedConflictsRetryAfterReleasingOwnedLocks(Mode mode) throws Exception {
        var read = new TVar<>(0);
        var write = new TVar<>(0);
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                read.lock.writeLock().lock();
                try {
                    acquired.countDown();
                    release.await();
                } finally {
                    read.lock.writeLock().unlock();
                }
                return null;
            });
            try {
                acquired.await();
                Weft.atomic(() -> {
                    attempts.incrementAndGet();
                    Weft.write(write, 1);
                    try {
                        Weft.read(read);
                    } catch (RuntimeException conflict) {
                        release.countDown();
                        try {
                            writer.get();
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        throw new RuntimeException(new RuntimeException(conflict));
                    }
                }, mode.hints);
            } finally {
                release.countDown();
            }
            writer.get();
        }
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(write.value).isEqualTo(1);
        assertThat(write.lock.isWriteLocked()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedFailuresStillEscapeInvalidatedAttempts(boolean cyclicCause) {
        var variable = new TVar<>(0);
        var cause = cyclicCause ? new IllegalArgumentException("cycle") : null;
        var failure = new IllegalStateException("application failed", cause);
        if (cause != null) cause.initCause(failure);
        var attempts = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThatThrownBy(() -> Weft.atomic(() -> {
                attempts.incrementAndGet();
                try {
                    executor.submit(() -> {
                        for (int i = 0; i < 32; i++) Weft.atomic(() -> Weft.write(variable, 1));
                    }).get();
                } catch (Exception unexpected) {
                    throw new AssertionError(unexpected);
                }
                assertThatThrownBy(() -> Weft.read(variable)).isInstanceOf(VersionEvictedException.class);
                throw failure;
            })).isSameAs(failure);
        }
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void wrappedEvictionRetriesWithAFreshSnapshot() {
        var variable = new TVar<>(0);
        var attempts = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Weft.atomic(() -> {
                if (attempts.getAndIncrement() == 0) {
                    try {
                        executor.submit(() -> {
                            for (int i = 0; i < 32; i++) Weft.atomic(() -> Weft.write(variable, 1));
                        }).get();
                    } catch (Exception unexpected) {
                        throw new AssertionError(unexpected);
                    }
                }
                try {
                    Weft.read(variable);
                } catch (VersionEvictedException evicted) {
                    throw new IllegalStateException(evicted);
                }
            });
        }
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void conflictSignalsDoNotCaptureStackTraces() {
        assertThat(new TransactionConflictException().getStackTrace()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"8, 2, false", "4, 6, true", "5, 5, false", "0, 0, false"})
    void hintsSelectLockTiming(int reads, int writes, boolean eagerLock) {
        var variable = new TVar<>(0);
        Weft.atomic(() -> {
            Weft.write(variable, 1);
            assertThat(variable.lock.isWriteLockedByCurrentThread()).isEqualTo(eagerLock);
        }, new TransactionHints(reads, writes));
        assertThat(variable.lock.isWriteLocked()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void readOnlyCommitKeepsItsValidatedSnapshot(Mode mode) {
        var variable = new TVar<>(0);
        var protocol = mode == Mode.OPTIMISTIC
                ? ConcurrencyProtocol.OPTIMISTIC : ConcurrencyProtocol.PESSIMISTIC_WRITE;
        try (var transaction = new Transaction(protocol)) {
            assertThat(transaction.read(variable)).isZero();
            Weft.atomic(() -> Weft.write(variable, 1));
            transaction.commit();
        }
    }

    @Test
    void optimisticReadsRetainOneSnapshotAcrossLaterCommits() {
        var first = new TVar<>(0);
        var second = new TVar<>(0);
        try (var transaction = new Transaction(ConcurrencyProtocol.OPTIMISTIC)) {
            assertThat(transaction.read(first)).isZero();
            Weft.atomic(() -> {
                Weft.write(first, 1);
                Weft.write(second, 1);
            });
            assertThat(transaction.read(first)).isZero();
            assertThat(transaction.read(second)).isZero();
            Weft.atomic(() -> Weft.write(first, 2));
            assertThat(transaction.read(first)).isZero();
            transaction.commit();
        }
    }

    @Test
    void historicalReadCannotCommitAnUpdateAgainstANewerVersion() {
        var read = new TVar<>(0);
        var write = new TVar<>(0);
        try (var transaction = new Transaction(ConcurrencyProtocol.OPTIMISTIC)) {
            Weft.atomic(() -> Weft.write(read, 1));
            assertThat(transaction.read(read)).isZero();
            transaction.write(write, 1);
            assertThatThrownBy(transaction::commit).isInstanceOf(TransactionConflictException.class);
        }
        assertThat(write.value).isZero();
        assertThat(write.lock.isWriteLocked()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void updatingSnapshotsCannotWriteSkew(Mode mode) throws Exception {
        var first = new TVar<>(1);
        var second = new TVar<>(1);
        var snapshotsRead = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (var variable : List.of(first, second)) {
                tasks.add(executor.submit(() -> {
                    var attempts = new AtomicInteger();
                    Weft.atomic(() -> {
                        int sum = Weft.read(first) + Weft.read(second);
                        if (attempts.getAndIncrement() == 0) {
                            snapshotsRead.countDown();
                            try {
                                snapshotsRead.await();
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(failure);
                            }
                        }
                        if (sum > 1) Weft.write(variable, 0);
                    }, mode.hints);
                }));
            }
            for (var task : tasks) task.get();
        }
        Weft.atomic(() -> assertThat(Weft.read(first) + Weft.read(second)).isEqualTo(1));
    }

    @Test
    void readOnlyAtomicDoesNotRetryWhenAWriterCommitsAfterItsRead() {
        var variable = new TVar<>(0);
        var attempts = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Weft.atomic(() -> {
                attempts.incrementAndGet();
                assertThat(Weft.read(variable)).isZero();
                try {
                    executor.submit(() -> Weft.atomic(() -> Weft.write(variable, 1))).get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                assertThat(Weft.read(variable)).isZero();
            });
        }
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(variable.value).isEqualTo(1);
    }

    @Test
    void caughtEvictionStillInvalidatesAReadOnlyAttempt() {
        var variable = new TVar<>(0);
        try (var transaction = new Transaction(ConcurrencyProtocol.OPTIMISTIC)) {
            for (int i = 1; i <= 32; i++) {
                int value = i;
                Weft.atomic(() -> Weft.write(variable, value));
            }
            assertThatThrownBy(() -> transaction.read(variable)).isInstanceOf(VersionEvictedException.class);
            assertThatThrownBy(transaction::commit).isInstanceOf(TransactionConflictException.class);
        }
        assertThat(variable.lock.isWriteLocked()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void anotherThreadCannotUseOrCloseAnAttempt(Mode mode) throws Exception {
        var variable = new TVar<>(0);
        var protocol = mode == Mode.OPTIMISTIC
                ? ConcurrencyProtocol.OPTIMISTIC : ConcurrencyProtocol.PESSIMISTIC_WRITE;
        try (var transaction = new Transaction(protocol);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            transaction.write(variable, 1);
            executor.submit(() -> {
                assertForeignAccess(transaction, variable);
                assertThatThrownBy(transaction::commit).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(transaction::close).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> protocol.read(variable, transaction)).isInstanceOf(IllegalStateException.class);
            }).get();
            assertThat(transaction.read(variable)).isEqualTo(1);
            transaction.commit();
        }
        assertThat(variable.value).isEqualTo(1);
        assertThat(variable.lock.isWriteLocked()).isFalse();
    }

    /** Checks borrowed operations reject a thread other than the attempt's owner. */
    private static void assertForeignAccess(TransactionContext context, TVar<Integer> variable) {
        assertThatThrownBy(() -> context.read(variable)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> context.write(variable, 2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(context::requireOwnerThread).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Verifies ownership checks for inherited scoped bindings using reflective access to
     * the structured concurrency API, allowing the test suite to run without preview flags.
     *
     * @param mode protocol exercised by the parent attempt
     * @throws Exception if the structured concurrency API cannot be loaded
     */
    @ParameterizedTest
    @EnumSource(Mode.class)
    void inheritedStructuredScopeCannotReadWriteOrJoinTheParentAttempt(Mode mode) throws Exception {
        var scopeApi = Class.forName("java.util.concurrent.StructuredTaskScope");
        var variable = new TVar<>(0);
        var nestedBodies = new AtomicInteger();
        Weft.atomic(() -> {
            Weft.write(variable, 1);
            try (var scope = (AutoCloseable) scopeApi.getMethod("open").invoke(null)) {
                scopeApi.getMethod("fork", Callable.class).invoke(scope, (Callable<Void>) () -> {
                    assertThatThrownBy(() -> Weft.read(variable))
                            .isInstanceOf(IllegalStateException.class).hasMessageContaining("another thread");
                    assertThatThrownBy(() -> Weft.write(variable, 2))
                            .isInstanceOf(IllegalStateException.class).hasMessageContaining("another thread");
                    assertThatThrownBy(() -> Weft.atomic(nestedBodies::incrementAndGet))
                            .isInstanceOf(IllegalStateException.class).hasMessageContaining("another thread");
                    return null;
                });
                scopeApi.getMethod("join").invoke(scope);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            assertThat(Weft.read(variable)).isEqualTo(1);
        }, mode.hints);
        assertThat(nestedBodies.get()).isZero();
        assertThat(variable.value).isEqualTo(1);
    }

    @Test
    void pessimisticReadRejectsVersionsNewerThanItsSnapshot() {
        var variable = new TVar<>(0);
        try (var transaction = new Transaction(ConcurrencyProtocol.PESSIMISTIC_WRITE)) {
            Weft.atomic(() -> Weft.write(variable, 1));
            assertThatThrownBy(() -> transaction.read(variable)).isInstanceOf(TransactionConflictException.class);
        }
    }

    @Test
    void catchingAReadConflictCannotMakeTheAttemptCommittable() {
        var read = new TVar<>(0);
        var write = new TVar<>(0);
        try (var transaction = new Transaction(ConcurrencyProtocol.PESSIMISTIC_WRITE)) {
            transaction.write(write, 1);
            Weft.atomic(() -> Weft.write(read, 2));
            assertThatThrownBy(() -> transaction.read(read)).isInstanceOf(TransactionConflictException.class);
            assertThatThrownBy(transaction::commit).isInstanceOf(TransactionConflictException.class);
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
                second.lock.writeLock().lock();
                try {
                    acquired.countDown();
                    release.await();
                } finally {
                    second.lock.writeLock().unlock();
                }
                return null;
            });
            try {
                acquired.await();
                try (var transaction = new Transaction(ConcurrencyProtocol.OPTIMISTIC)) {
                    transaction.write(second, 2);
                    transaction.write(first, 1);
                    assertThatThrownBy(transaction::commit).isInstanceOf(TransactionConflictException.class);
                }
                assertThat(first.lock.isWriteLocked()).isFalse();
                assertThat(first.value).isZero();
                assertThat(second.value).isZero();
                try (var transaction = new Transaction(ConcurrencyProtocol.PESSIMISTIC_WRITE)) {
                    assertThatThrownBy(() -> transaction.read(second)).isInstanceOf(TransactionConflictException.class);
                }
            } finally {
                release.countDown();
            }
            holder.get();
        }
    }
}
