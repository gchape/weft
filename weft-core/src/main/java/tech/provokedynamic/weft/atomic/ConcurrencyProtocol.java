package tech.provokedynamic.weft.atomic;

import org.jspecify.annotations.Nullable;

import tech.provokedynamic.weft.exception.VersionEvictedException;

/**
 * Stateless strategies for validated reads and write-lock timing.
 * {@link Transaction} owns all attempt state, publication, and lock cleanup.
 */
enum ConcurrencyProtocol {
    /**
     * Reads the snapshot and defers write locks until commit.
     *
     * <p>Visible latest values use validated volatile reads without scanning history or
     * acquiring a read lock. Older snapshots share the TVar read lock, which excludes
     * appends and resizing while allowing other historical and latest-value readers.
     */
    OPTIMISTIC {
        @Override
        <T extends @Nullable Object> T read(TVar<T> variable, Transaction transaction) {
            long version = beginRead(variable, transaction);
            if (version <= transaction.readVersion()) {
                T value = variable.value;
                validateRead(variable, version, transaction);
                return value;
            }

            if (!variable.tryReadLock()) throw transaction.conflict();
            try {
                T value = variable.history.scan(transaction.readVersion());
                transaction.recordRead(variable, variable.version);
                return value;
            } catch (VersionEvictedException evicted) {
                transaction.invalidate();
                throw evicted;
            } finally {
                variable.unlockRead();
            }
        }

        @Override
        void beforeCommit(Transaction transaction) {
            transaction.lockWriteSet();
        }
    },

    /** Reads validated live values and acquires a write lock before buffering each variable. */
    PESSIMISTIC_WRITE {
        @Override
        <T extends @Nullable Object> T read(TVar<T> variable, Transaction transaction) {
            long version = beginRead(variable, transaction);
            if (version > transaction.readVersion()) throw transaction.conflict();
            T value = variable.value;
            validateRead(variable, version, transaction);
            return value;
        }

        @Override
        void beforeWrite(TVar<?> variable, Transaction transaction) {
            transaction.lock(variable);
        }
    };

    /** Reads a committed value while checking snapshot visibility and writer ownership. */
    abstract <T extends @Nullable Object> T read(TVar<T> variable, Transaction transaction);

    /** Prepares a variable for buffering; optimistic writes require no eager lock. */
    void beforeWrite(TVar<?> variable, Transaction transaction) {
    }

    /** Acquires any deferred write locks; pessimistic writes already hold them. */
    void beforeCommit(Transaction transaction) {
    }

    private static long beginRead(TVar<?> variable, Transaction transaction) {
        transaction.requireOwnerThread();
        long version = variable.version;
        if (variable.isLockedByOtherThread()) {
            throw transaction.conflict();
        }
        return version;
    }

    private static void validateRead(TVar<?> variable, long version, Transaction transaction) {
        if (variable.version != version || variable.isLockedByOtherThread()) {
            throw transaction.conflict();
        }
        transaction.recordRead(variable, version);
    }
}
