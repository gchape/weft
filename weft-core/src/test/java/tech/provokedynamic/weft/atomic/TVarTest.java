package tech.provokedynamic.weft.atomic;

import org.jspecify.annotations.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.provokedynamic.weft.exception.VersionEvictedException;

import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TVarTest {

    @Test
    @DisplayName("ids are unique and strictly increasing")
    void idsUniqueAndMonotonic() {
        TVar<Integer> a = new TVar<>(1);
        TVar<Integer> b = new TVar<>(2);
        TVar<Integer> c = new TVar<>(3);

        assertThat(a.id).isLessThan(b.id);
        assertThat(b.id).isLessThan(c.id);
    }

    @Test
    @DisplayName("initial value is readable inside a transaction")
    void initialValueReadable() {
        TVar<@Nullable String> v = new TVar<>("hello");
        AtomicReference<@Nullable String> result = new AtomicReference<>();

        Weft.atomic(() -> result.set(Weft.read(v)));

        assertThat(result.get()).isEqualTo("hello");
    }

    @Test
    @DisplayName("compareTo is consistent with id ordering")
    void compareToOrdering() {
        TVar<String> x = new TVar<>("x");
        TVar<String> y = new TVar<>("y");

        assertThat(x.compareTo(y)).isNegative();
        assertThat(y.compareTo(x)).isPositive();
    }

    @Test
    void compareToOrdersDifferentValueTypes() {
        var first = new TVar<>(1);
        var second = new TVar<>("second");
        assertThat(first.compareTo(second)).isNegative();
        assertThat(second.compareTo(first)).isPositive();
    }

    @Test
    void historyReadersShareAccessWithoutInvalidatingLatestValueReads() throws Exception {
        var variable = new TVar<>(0);
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var snapshot = new Transaction(ConcurrencyProtocol.OPTIMISTIC);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Weft.atomic(() -> Weft.write(variable, 1));
            var reader = executor.submit(() -> {
                variable.lock.readLock().lock();
                try {
                    acquired.countDown();
                    release.await();
                } finally {
                    variable.lock.readLock().unlock();
                }
                return null;
            });
            try {
                acquired.await();
                assertThat(snapshot.read(variable)).isZero();
                Weft.atomic(() -> assertThat(Weft.read(variable)).isEqualTo(1));
                assertThat(variable.tryWriteLock()).isFalse();
                assertThatThrownBy(() -> variable.expandHistoryByFactor(2))
                        .isInstanceOf(IllegalStateException.class);
                snapshot.commit();
            } finally {
                release.countDown();
            }
            reader.get();
        }
    }

    @Test
    @DisplayName("tryWriteLock returns false when another thread holds the lock")
    void tryWriteLockReturnsFalseWhenLocked() throws InterruptedException {
        TVar<Integer> v = new TVar<>(0);
        v.lock.writeLock().lock();
        try {
            AtomicReference<@Nullable Boolean> acquired = new AtomicReference<>();
            Thread t = Thread.ofVirtual().start(() -> acquired.set(v.tryWriteLock()));
            t.join(1_000);
            assertThat(acquired.get()).isFalse();
        } finally {
            v.lock.writeLock().unlock();
        }
    }

    @Test
    @DisplayName("unlockWriteLock is idempotent when lock not held")
    void unlockWriteLockIsIdempotent() {
        TVar<Integer> v = new TVar<>(0);
        assertThatCode(v::unlockWriteLock).doesNotThrowAnyException();
    }

    @Test
    void publicHistoryExpansionRetainsOlderTransactionalSnapshots() {
        var variable = new TVar<@Nullable Integer>(null);
        variable.expandHistoryByFactor(2);
        try (var snapshot = new Transaction(ConcurrencyProtocol.OPTIMISTIC)) {
            for (int i = 1; i <= 48; i++) {
                int value = i;
                Weft.atomic(() -> Weft.write(variable, value));
            }
            assertNull(snapshot.read(variable));
            snapshot.commit();
        }
        assertThat(variable.value).isEqualTo(48);
        assertThat(variable.lock.isWriteLocked()).isFalse();
    }

    @Test
    void publicHistoryShrinkEvictsOldSnapshots() {
        var variable = new TVar<>(0);
        variable.expandHistoryByFactor(2);
        try (var snapshot = new Transaction(ConcurrencyProtocol.OPTIMISTIC)) {
            for (int i = 1; i <= 48; i++) {
                int value = i;
                Weft.atomic(() -> Weft.write(variable, value));
            }
            variable.shrinkHistoryByFactor(0.5f);
            variable.expandHistoryByFactor(2);
            assertThatThrownBy(() -> snapshot.read(variable)).isInstanceOf(VersionEvictedException.class);
        }
        assertThat(variable.value).isEqualTo(48);
        assertThat(variable.lock.isWriteLocked()).isFalse();
    }

    @Test
    void historyResizePreservesAnOwnedTransactionLockAndReleasesOnInvalidFactors() {
        var variable = new TVar<>(0);
        try (var transaction = new Transaction(ConcurrencyProtocol.PESSIMISTIC_WRITE)) {
            transaction.write(variable, 1);
            variable.expandHistoryByFactor(2);
            variable.shrinkHistoryByFactor(0.5f);
            assertThatThrownBy(() -> variable.expandHistoryByFactor(Float.NaN))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> variable.shrinkHistoryByFactor(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(variable.lock.getWriteHoldCount()).isEqualTo(1);
            transaction.commit();
        }
        assertThat(variable.lock.isWriteLocked()).isFalse();
        assertThatThrownBy(() -> variable.expandHistoryByFactor(0.5f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(variable.lock.isWriteLocked()).isFalse();
    }

    @Test
    void historyResizeRejectsConcurrentLockOwnership() throws Exception {
        var variable = new TVar<>(0);
        variable.lock.writeLock().lock();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                assertThatThrownBy(() -> variable.expandHistoryByFactor(2))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> variable.shrinkHistoryByFactor(0.5f))
                        .isInstanceOf(IllegalStateException.class);
            }).get();
            assertThat(variable.lock.getWriteHoldCount()).isEqualTo(1);
        } finally {
            variable.lock.writeLock().unlock();
        }
    }
}
