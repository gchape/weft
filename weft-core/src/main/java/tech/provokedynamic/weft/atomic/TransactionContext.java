package tech.provokedynamic.weft.atomic;

import org.jspecify.annotations.Nullable;

/**
 * Borrowed access to an active transaction attempt, without commit or cleanup operations.
 *
 * <p>The enclosing atomic invocation owns the concrete {@link Transaction}. Scoped bindings
 * expose this view to reads, writes, and nested invocations. An inherited binding retains
 * the original attempt's thread ownership.
 */
interface TransactionContext {

    /**
     * Reads a buffered value or a committed value visible to the attempt's snapshot.
     *
     * @param variable transactional variable to read
     * @param <T> value type, including its nullability
     * @return the observed value, which may be null when {@code T} is nullable
     * @throws IllegalStateException if the attempt belongs to another thread
     */
    <T extends @Nullable Object> T read(TVar<T> variable);

    /**
     * Buffers a replacement value for publication by the owning atomic invocation.
     *
     * @param variable transactional variable to replace
     * @param value replacement value, which may be null when {@code T} is nullable
     * @param <T> value type, including its nullability
     * @throws IllegalStateException if the attempt belongs to another thread
     */
    <T extends @Nullable Object> void write(TVar<T> variable, T value);

    /**
     * Checks that the current thread owns the borrowed attempt.
     *
     * @throws IllegalStateException if the attempt belongs to another thread
     */
    void requireOwnerThread();
}
