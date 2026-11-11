package tech.provokedynamic.weft.atomic;

import org.jspecify.annotations.Nullable;

import tech.provokedynamic.weft.TransactionHints;
import tech.provokedynamic.weft.exception.MaxRetriesExceededException;
import tech.provokedynamic.weft.exception.VersionEvictedException;
import tech.provokedynamic.weft.retry.AdaptiveBackoff;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Runs transactions over {@link TVar} values with bounded conflict retries.
 *
 * <p>Nested atomic calls join the current attempt and inherit its protocol. Bodies may execute
 * repeatedly, so external side effects must be performed after {@code atomic} returns.
 * Buffered writes are published only after successful validation; exceptions escaping
 * the outer body discard those writes and release all owned locks.
 *
 * <p>Each attempt is confined to its executing thread. Scoped bindings support independent
 * transactions on both platform and virtual threads without retaining thread-local state.
 */
public final class Weft {

    private static final ScopedValue<TransactionContext> CURRENT_CONTEXT = ScopedValue.newInstance();
    private static final int MAX_RETRIES = 512;
    private static final double READ_HEAVY_THRESHOLD = 0.8;
    private static final double WRITE_HEAVY_THRESHOLD = 0.6;

    private Weft() {
    }

    /**
     * Runs a transaction using the optimistic protocol, or joins the current attempt.
     *
     * @param body transaction body, which must tolerate retries
     * @throws NullPointerException if {@code body} is null
     * @throws IllegalStateException if an inherited attempt belongs to another thread
     * @throws MaxRetriesExceededException if the initial attempt and 512 retries conflict
     */
    public static void atomic(Runnable body) {
        execute(body, ConcurrencyProtocol.OPTIMISTIC);
    }

    /**
     * Runs a transaction using workload hints to select its protocol.
     * Nested invocations retain the enclosing attempt's protocol and retry budget.
     *
     * @param body transaction body, which must tolerate retries
     * @param hints static operation counts or explicitly supplied workload hints
     * @throws NullPointerException if either argument is null
     * @throws IllegalStateException if an inherited attempt belongs to another thread
     * @throws MaxRetriesExceededException if the initial attempt and 512 retries conflict
     */
    public static void atomic(Runnable body, TransactionHints hints) {
        execute(body, selectProtocol(Objects.requireNonNull(hints, "hints")));
    }

    /**
     * Reads the attempt's buffered value, if present, or a validated committed value.
     *
     * <p>The active attempt is borrowed from the enclosing atomic invocation, which owns
     * its lifecycle and releases its locks after commit or failure.
     *
     * @param variable transactional variable to read
     * @param <T> value type, including its nullability
     * @return the observed value, which may be null when {@code T} is nullable
     * @throws IllegalStateException if no transaction is active or the attempt belongs to another thread
     * @throws NullPointerException if {@code variable} is null
     */
    public static <T extends @Nullable Object> T read(TVar<T> variable) {
        return requireCurrentContext().read(Objects.requireNonNull(variable, "variable"));
    }

    /**
     * Buffers a replacement value for publication when the current attempt commits.
     * The pessimistic write protocol acquires the variable's lock before buffering.
     *
     * <p>The active attempt is borrowed from the enclosing atomic invocation and remains
     * open until that invocation commits or fails.
     *
     * @param variable transactional variable to replace
     * @param value replacement value, which may be null when {@code T} is nullable
     * @param <T> value type, including its nullability
     * @throws IllegalStateException if no transaction is active or the attempt belongs to another thread
     * @throws NullPointerException if {@code variable} is null
     */
    public static <T extends @Nullable Object> void write(TVar<T> variable, T value) {
        requireCurrentContext().write(Objects.requireNonNull(variable, "variable"), value);
    }

    /** Returns borrowed access to the attempt owned by the enclosing atomic invocation. */
    private static TransactionContext requireCurrentContext() {
        return CURRENT_CONTEXT.orElseThrow(() -> new IllegalStateException("No active transaction"));
    }

    /**
     * Joins an attempt owned by the current thread or creates and retries an outer attempt.
     *
     * <p>Nested calls borrow the enclosing attempt. Each new attempt is owned by this method
     * and closed through try-with-resources before returning, propagating a failure, or
     * delaying the next retry.
     *
     * @param body transaction body, which may execute repeatedly
     * @param protocol read behavior and write-lock timing for a new attempt
     */
    private static void execute(Runnable body, ConcurrencyProtocol protocol) {
        Objects.requireNonNull(body, "body");
        if (CURRENT_CONTEXT.isBound()) {
            requireCurrentContext().requireOwnerThread();
            body.run();
            return;
        }

        AdaptiveBackoff backoff = new AdaptiveBackoff();
        while (true) {
            try (Transaction transaction = new Transaction(protocol)) {
                try {
                    ScopedValue.where(CURRENT_CONTEXT, transaction).run(body);
                } catch (TransactionConflictException | VersionEvictedException retry) {
                    throw retry;
                } catch (RuntimeException failure) {
                    if (transaction.isInvalidated() && hasRetryCause(failure)) {
                        throw transaction.conflict();
                    }
                    throw failure;
                }
                transaction.commit();
                return;
            } catch (TransactionConflictException | VersionEvictedException _) {
                if (backoff.retries() >= MAX_RETRIES) {
                    throw new MaxRetriesExceededException(MAX_RETRIES);
                }
                backoff.pause();
            }
        }
    }

    /** Recognizes wrapped retry signals while tolerating cyclic application cause chains. */
    private static boolean hasRetryCause(Throwable failure) {
        if (failure instanceof TransactionConflictException || failure instanceof VersionEvictedException) return true;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure.getCause(); cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof TransactionConflictException || cause instanceof VersionEvictedException) return true;
        }
        return false;
    }

    private static ConcurrencyProtocol selectProtocol(TransactionHints hints) {
        if (hints.totalOps() == 0) {
            return ConcurrencyProtocol.OPTIMISTIC;
        }
        double readRatio = (double) hints.readOps() / hints.totalOps();
        double writeRatio = (double) hints.writeOps() / hints.totalOps();
        if (readRatio >= READ_HEAVY_THRESHOLD) {
            return ConcurrencyProtocol.OPTIMISTIC;
        }
        return writeRatio >= WRITE_HEAVY_THRESHOLD
                ? ConcurrencyProtocol.PESSIMISTIC_WRITE
                : ConcurrencyProtocol.OPTIMISTIC;
    }
}
