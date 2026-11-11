package tech.provokedynamic.weft.atomic;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The unit of work for one thread-confined transaction attempt.
 *
 * <p>Owns observed versions, buffered writes, and acquired locks. The protocol controls read
 * behavior and lock timing; this class coordinates validation, publication, and cleanup.
 * Attempts retain object identity even when they share a snapshot timestamp.
 *
 * <p>{@link TransactionContext} exposes borrowed operations. The enclosing atomic invocation
 * owns this resource and is responsible for commit and cleanup.
 */
final class Transaction implements TransactionContext, AutoCloseable {

    private static final Clock COMMIT_CLOCK = new Clock();

    private final Thread ownerThread = Thread.currentThread();
    private final long readVersion = COMMIT_CLOCK.snapshot();
    private final ConcurrencyProtocol protocol;
    private final Map<TVar<?>, Long> readSet = new HashMap<>();
    private final Map<TVar<?>, @Nullable Object> writeSet = new TreeMap<>();
    private final List<TVar<?>> ownedWriteLocks = new ArrayList<>();
    private boolean invalidated;

    Transaction(ConcurrencyProtocol protocol) {
        this.protocol = protocol;
    }

    long readVersion() {
        return readVersion;
    }

    @Override
    public void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Transaction belongs to another thread");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends @Nullable Object> T read(TVar<T> variable) {
        requireOwnerThread();
        if (writeSet.containsKey(variable)) {
            return (T) writeSet.get(variable);
        }
        return protocol.read(variable, this);
    }

    @Override
    public <T extends @Nullable Object> void write(TVar<T> variable, T value) {
        requireOwnerThread();
        protocol.beforeWrite(variable, this);
        writeSet.put(variable, value);
    }

    /** Records the first live publication stamp for update validation, including historical reads. */
    void recordRead(TVar<?> variable, long version) {
        readSet.putIfAbsent(variable, version);
    }

    /** Acquires and records a write lock without waiting while other locks may be held. */
    void lockForWrite(TVar<?> variable) {
        requireOwnerThread();
        if (variable.lock.isWriteLockedByCurrentThread()) return;
        if (!variable.tryWriteLock()) {
            throw conflict();
        }
        ownedWriteLocks.add(variable);
    }

    /** Acquires buffered variables in their globally consistent identity order. */
    void lockWriteSet() {
        writeSet.keySet().forEach(this::lockForWrite);
    }

    /**
     * Validates every recorded read and publishes all buffered writes with one clock stamp.
     * Read-only attempts have already checked snapshot visibility and need no commit validation.
     *
     * <p>Updating attempts reserve their serialization point before validation. A concurrent
     * writer must therefore either invalidate an observation or receive a later commit stamp.
     */
    void commit() {
        requireOwnerThread();
        if (invalidated) throw new TransactionConflictException();
        if (writeSet.isEmpty()) return;
        protocol.beforeCommit(this);
        long commitVersion = COMMIT_CLOCK.advance();
        validateReadSet();
        writeSet.forEach((variable, value) -> publishWrite(variable, value, commitVersion));
    }

    /** Checks that every observed version still belongs to the attempt's snapshot. */
    private void validateReadSet() {
        readSet.forEach((variable, version) -> {
            if (version > readVersion || variable.isLockedByOtherThread() || variable.version != version) {
                throw conflict();
            }
        });
    }

    /** Marks the attempt invalid even if application code catches a retry signal. */
    void invalidate() {
        invalidated = true;
    }

    /** Returns whether an internal conflict or eviction has invalidated the attempt. */
    boolean isInvalidated() {
        return invalidated;
    }

    /** Invalidates the attempt and creates its internal retry signal. */
    TransactionConflictException conflict() {
        invalidate();
        return new TransactionConflictException();
    }

    @SuppressWarnings("unchecked")
    private static void publishWrite(TVar<?> variable, @Nullable Object value, long version) {
        ((TVar<@Nullable Object>) variable).publish(value, version);
    }

    /** Releases owned locks in reverse order; repeated cleanup is harmless. */
    @Override
    public void close() {
        requireOwnerThread();
        for (int i = ownedWriteLocks.size() - 1; i >= 0; i--) {
            ownedWriteLocks.get(i).unlockWriteLock();
        }
        ownedWriteLocks.clear();
    }
}
