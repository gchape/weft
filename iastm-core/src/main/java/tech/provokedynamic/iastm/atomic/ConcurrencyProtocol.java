package tech.provokedynamic.iastm.atomic;

/**
 * Stateless strategies for validated reads and write-lock timing.
 * {@link Tx} owns all attempt state, publication, and lock cleanup.
 */
public sealed interface ConcurrencyProtocol {

    /**
     * Reads a committed value while checking snapshot visibility and writer ownership.
     *
     * @param variable variable to read
     * @param tx owning transaction attempt
     * @param <T> value type
     * @return the validated value, which may be null
     */
    <T> T read(TVar<T> variable, Tx tx);

    /**
     * Prepares a variable for buffering; the default requires no eager lock.
     *
     * @param variable variable being written
     * @param tx owning transaction attempt
     */
    default void beforeWrite(TVar<?> variable, Tx tx) {
    }

    /**
     * Acquires any locks deferred until commit; the default already holds the needed locks.
     *
     * @param tx owning transaction attempt
     */
    default void beforeCommit(Tx tx) {
    }

    private static long beginRead(TVar<?> variable, Tx tx) {
        long version = variable.version;
        if (version > tx.readPoint() || variable.isLockedByOtherThread()) {
            throw tx.conflict();
        }
        return version;
    }

    private static void finishRead(TVar<?> variable, long version, Tx tx) {
        if (variable.version != version || variable.isLockedByOtherThread()) {
            throw tx.conflict();
        }
        tx.recordReadVersion(variable, version);
    }

    /** Reads version history and defers write-lock acquisition until commit. */
    enum Optimistic implements ConcurrencyProtocol {
        /** Shared stateless protocol instance. */
        INSTANCE;

        @Override
        public <T> T read(TVar<T> variable, Tx tx) {
            long version = beginRead(variable, tx);
            T value = variable.history.scan(tx.readPoint());
            finishRead(variable, version, tx);
            return value;
        }

        @Override
        public void beforeCommit(Tx tx) {
            tx.acquirePendingWriteLocks();
        }
    }

    /** Reads validated live values and acquires a write lock before buffering each variable. */
    enum PessimisticWrite implements ConcurrencyProtocol {
        /** Shared stateless protocol instance. */
        INSTANCE;

        @Override
        public <T> T read(TVar<T> variable, Tx tx) {
            long version = beginRead(variable, tx);
            T value = variable.value;
            finishRead(variable, version, tx);
            return value;
        }

        @Override
        public void beforeWrite(TVar<?> variable, Tx tx) {
            tx.acquireWriteLock(variable);
        }
    }
}
