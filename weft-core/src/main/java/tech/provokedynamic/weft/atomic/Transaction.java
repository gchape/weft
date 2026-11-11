package tech.provokedynamic.weft.atomic;

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
 */
public final class Transaction implements AutoCloseable {

    private static final Clock COMMIT_CLOCK = new Clock();

    private final long readPoint = COMMIT_CLOCK.snapshot();
    private final ConcurrencyProtocol protocol;
    private final Map<TVar<?>, Long> readVersions = new HashMap<>();
    private final Map<TVar<?>, Object> pendingWrites = new TreeMap<>();
    private final List<TVar<?>> ownedLocks = new ArrayList<>();
    private boolean conflicted;

    Transaction(ConcurrencyProtocol protocol) {
        this.protocol = protocol;
    }

    long readPoint() {
        return readPoint;
    }

    @SuppressWarnings("unchecked")
    <T> T read(TVar<T> variable) {
        if (pendingWrites.containsKey(variable)) {
            return (T) pendingWrites.get(variable);
        }
        return protocol.read(variable, this);
    }

    <T> void write(TVar<T> variable, T value) {
        protocol.beforeWrite(variable, this);
        pendingWrites.put(variable, value);
    }

    /** Records a stable observation; a different repeated observation invalidates the attempt. */
    void recordReadVersion(TVar<?> variable, long version) {
        Long previous = readVersions.putIfAbsent(variable, version);
        if (previous != null && previous != version) {
            throw conflict();
        }
    }

    /** Acquires and records a write lock without waiting while other locks may be held. */
    void acquireWriteLock(TVar<?> variable) {
        if (variable.lock.isHeldByCurrentThread()) return;
        if (!variable.tryAcquireWriteLock()) {
            throw conflict();
        }
        ownedLocks.add(variable);
    }

    /** Acquires buffered variables in their globally consistent identity order. */
    void acquirePendingWriteLocks() {
        pendingWrites.keySet().forEach(this::acquireWriteLock);
    }

    /**
     * Validates every recorded read and publishes all buffered writes with one clock stamp.
     * Read-only attempts validate without advancing the commit clock.
     */
    void commit() {
        if (conflicted) throw new TransactionConflictException();
        protocol.beforeCommit(this);
        readVersions.forEach((variable, version) -> {
            if (variable.isLockedByOtherThread() || variable.version != version) {
                throw conflict();
            }
        });
        if (!pendingWrites.isEmpty()) {
            long commitVersion = COMMIT_CLOCK.advance();
            pendingWrites.forEach((variable, value) -> publishWrite(variable, value, commitVersion));
        }
    }

    /** Marks the attempt invalid even if application code catches the returned retry signal. */
    TransactionConflictException conflict() {
        conflicted = true;
        return new TransactionConflictException();
    }

    @SuppressWarnings("unchecked")
    private static void publishWrite(TVar<?> variable, Object value, long version) {
        ((TVar<Object>) variable).publish(value, version);
    }

    /** Releases owned locks in reverse order; repeated cleanup is harmless. */
    @Override
    public void close() {
        for (int i = ownedLocks.size() - 1; i >= 0; i--) {
            ownedLocks.get(i).releaseWriteLock();
        }
        ownedLocks.clear();
    }
}
