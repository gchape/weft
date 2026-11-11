package tech.provokedynamic.weft.atomic;

import lombok.EqualsAndHashCode;
import tech.provokedynamic.weft.mvcc.CircularVersionHistory;
import tech.provokedynamic.weft.mvcc.VersionHistory;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A versioned transactional reference with a bounded history and an exclusive write lock.
 *
 * <p>Use {@link Weft#read(TVar)} and {@link Weft#write(TVar, Object)} inside a transaction.
 * Values should be immutable or replaced rather than mutated in place, because changes
 * made directly to a referenced object cannot be buffered or rolled back.
 *
 * @param <T> value type
 */
@EqualsAndHashCode(of = "id")
public final class TVar<T> implements Comparable<TVar<T>> {

    private static final Clock ID_CLOCK = new Clock();

    final VersionHistory<T> history;

    final long id = ID_CLOCK.advance();

    final ReentrantLock lock = new ReentrantLock();

    volatile T value;
    volatile long version;

    /**
     * Creates a variable with its initial value at version zero and a unique lock-order identity.
     *
     * @param initial initial value, which may be null
     */
    public TVar(T initial) {
        this.version = 0L;
        this.value = initial;
        this.history = new CircularVersionHistory<>(initial);
    }

    /** Publishes history and value before the volatile version; the caller must hold the lock. */
    void publish(T newValue, long commitVersion) {
        history.append(newValue, commitVersion);
        this.value = newValue;
        this.version = commitVersion;
    }

    boolean isLockedByOtherThread() {
        return lock.isLocked() && !lock.isHeldByCurrentThread();
    }

    boolean tryAcquireWriteLock() {
        return lock.tryLock();
    }

    void releaseWriteLock() {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }

    /**
     * Orders variables by their unique identity for deterministic commit locking.
     *
     * @param other variable to compare
     * @return a negative value, zero, or a positive value according to identity order
     */
    @Override
    public int compareTo(TVar<T> other) {
        return Long.compare(this.id, other.id);
    }
}
