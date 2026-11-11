package tech.provokedynamic.weft.atomic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
        TVar<String> v = new TVar<>("hello");
        AtomicReference<String> result = new AtomicReference<>();

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
    @DisplayName("tryAcquireWriteLock returns false when another thread holds the lock")
    void tryAcquireWriteLockReturnsFalseWhenLocked() throws InterruptedException {
        TVar<Integer> v = new TVar<>(0);
        v.lock.lock();
        try {
            AtomicReference<Boolean> stole = new AtomicReference<>();
            Thread t = Thread.ofVirtual().start(() -> stole.set(v.tryAcquireWriteLock()));
            t.join(1_000);
            assertThat(stole.get()).isFalse();
        } finally {
            v.lock.unlock();
        }
    }

    @Test
    @DisplayName("releaseWriteLock is idempotent when lock not held")
    void releaseWriteLockIdempotent() {
        TVar<Integer> v = new TVar<>(0);
        assertThatCode(v::releaseWriteLock).doesNotThrowAnyException();
    }
}
