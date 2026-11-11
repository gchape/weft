package tech.provokedynamic.weft.atomic;

import org.jspecify.annotations.Nullable;

import lombok.EqualsAndHashCode;
import tech.provokedynamic.weft.mvcc.CircularVersionHistory;
import tech.provokedynamic.weft.mvcc.VersionHistory;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A versioned transactional reference with bounded history and a read/write lock.
 * Historical readers share the read lock; publication and resizing take the write lock.
 *
 * <p>Use {@link Weft#read(TVar)} and {@link Weft#write(TVar, Object)} inside a transaction.
 * Values should be immutable or replaced rather than mutated in place, because changes
 * made directly to a referenced object cannot be buffered or rolled back.
 *
 * <p>Payload nullability is part of the type argument. Use, for example,
 * {@code TVar<@Nullable String>} when the stored string may be null.
 *
 * @param <T> value type, including its nullability
 */
@EqualsAndHashCode(of = "id")
public final class TVar<T extends @Nullable Object> implements Comparable<TVar<?>> {

    private static final Clock ID_CLOCK = new Clock();

    final VersionHistory<T> history;

    final long id = ID_CLOCK.advance();

    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    volatile T value;
    volatile long version;

    /**
     * Creates a variable with its initial value at version zero and a unique lock-order identity.
     *
     * @param initial initial value, which may be null when {@code T} is nullable
     */
    public TVar(T initial) {
        this.version = 0L;
        this.value = initial;
        this.history = new CircularVersionHistory<>(initial);
    }

    /**
     * Expands the retained history, up to 2,048 entries, without restoring evicted versions.
     * Resizing takes the variable's lock and is immediate, even inside a transaction.
     *
     * @param factor finite multiplier greater than or equal to one
     * @throws IllegalArgumentException if the factor is invalid
     * @throws IllegalStateException if another thread holds the variable's lock
     */
    public void expandHistoryByFactor(float factor) {
        if (!tryWriteLock()) throw new IllegalStateException("Cannot resize a locked variable's history");
        try {
            history.expandByFactor(factor);
        } finally {
            unlockWriteLock();
        }
    }

    /**
     * Shrinks the retained history, down to 32 entries, keeping its newest versions.
     * Resizing takes the variable's lock and is immediate, even inside a transaction.
     *
     * @param factor finite multiplier in the interval (0, 1]
     * @throws IllegalArgumentException if the factor is invalid
     * @throws IllegalStateException if another thread holds the variable's lock
     */
    public void shrinkHistoryByFactor(float factor) {
        if (!tryWriteLock()) throw new IllegalStateException("Cannot resize a locked variable's history");
        try {
            history.shrinkByFactor(factor);
        } finally {
            unlockWriteLock();
        }
    }

    /** Publishes history and value before the volatile version; the caller must hold the lock. */
    void publish(T newValue, long commitVersion) {
        history.append(newValue, commitVersion);
        this.value = newValue;
        this.version = commitVersion;
    }

    boolean isLockedByOtherThread() {
        return lock.isWriteLocked() && !lock.isWriteLockedByCurrentThread();
    }

    boolean tryWriteLock() {
        return lock.writeLock().tryLock();
    }

    void unlockWriteLock() {
        if (lock.isWriteLockedByCurrentThread()) lock.writeLock().unlock();
    }

    /** Tries to share history access with other readers while excluding mutation. */
    boolean tryReadLock() {
        return lock.readLock().tryLock();
    }

    /** Releases the current thread's temporary history read lock. */
    void unlockReadLock() {
        lock.readLock().unlock();
    }

    /**
     * Orders variables by their unique identity for deterministic commit locking.
     *
     * @param other variable to compare
     * @return a negative value, zero, or a positive value according to identity order
     */
    @Override
    public int compareTo(TVar<?> other) {
        return Long.compare(this.id, other.id);
    }
}
