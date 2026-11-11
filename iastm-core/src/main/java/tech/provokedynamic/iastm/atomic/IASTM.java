package tech.provokedynamic.iastm.atomic;

import tech.provokedynamic.iastm.TxMetrics;
import tech.provokedynamic.iastm.exception.MaxRetriesExceededException;
import tech.provokedynamic.iastm.exception.VersionEvictedException;
import tech.provokedynamic.iastm.retry.AdaptiveBackoff;

import java.util.Objects;

/**
 * Runs transactions over {@link TVar} values with bounded conflict retries.
 *
 * <p>Nested starts join the current attempt and inherit its protocol. Bodies may execute
 * repeatedly, so external side effects must be performed after {@code start} returns.
 * Buffered writes are published only after successful validation; exceptions escaping
 * the outer body discard those writes and release all owned locks.
 *
 * <p>Each attempt is confined to its executing thread. Scoped bindings support independent
 * transactions on both platform and virtual threads without retaining thread-local state.
 */
public final class IASTM {

    private static final ScopedValue<Tx> CURRENT_TX = ScopedValue.newInstance();
    private static final int MAX_RETRIES = 512;
    private static final double READ_HEAVY_THRESHOLD = 0.8;
    private static final double WRITE_HEAVY_THRESHOLD = 0.6;

    private IASTM() {
    }

    /**
     * Runs a transaction using the optimistic protocol, or joins the current attempt.
     *
     * @param body transaction body, which must tolerate retries
     * @throws NullPointerException if {@code body} is null
     * @throws MaxRetriesExceededException if the initial attempt and 512 retries conflict
     */
    public static void start(Runnable body) {
        execute(body, ConcurrencyProtocol.Optimistic.INSTANCE);
    }

    /**
     * Runs a transaction using workload hints to select its protocol.
     * Nested invocations retain the enclosing attempt's protocol and retry budget.
     *
     * @param body transaction body, which must tolerate retries
     * @param metrics static operation counts or explicitly supplied workload hints
     * @throws NullPointerException if either argument is null
     * @throws MaxRetriesExceededException if the initial attempt and 512 retries conflict
     */
    public static void start(Runnable body, TxMetrics metrics) {
        execute(body, selectProtocol(Objects.requireNonNull(metrics, "metrics")));
    }

    /**
     * Reads the attempt's buffered value, if present, or a validated committed value.
     *
     * @param variable transactional variable to read
     * @param <T> value type
     * @return the observed value, which may be null
     * @throws IllegalStateException if no transaction is active
     * @throws NullPointerException if {@code variable} is null
     */
    public static <T> T read(TVar<T> variable) {
        return currentTransaction().read(Objects.requireNonNull(variable, "variable"));
    }

    /**
     * Buffers a replacement value for publication when the current attempt commits.
     * The pessimistic write protocol acquires the variable's lock before buffering.
     *
     * @param variable transactional variable to replace
     * @param value replacement value, which may be null
     * @param <T> value type
     * @throws IllegalStateException if no transaction is active
     * @throws NullPointerException if {@code variable} is null
     */
    public static <T> void write(TVar<T> variable, T value) {
        currentTransaction().write(Objects.requireNonNull(variable, "variable"), value);
    }

    private static Tx currentTransaction() {
        return CURRENT_TX.orElseThrow(() -> new IllegalStateException("No active transaction"));
    }

    private static void execute(Runnable body, ConcurrencyProtocol protocol) {
        Objects.requireNonNull(body, "body");
        if (CURRENT_TX.isBound()) {
            body.run();
            return;
        }

        AdaptiveBackoff backoff = new AdaptiveBackoff();
        while (true) {
            try (Tx tx = new Tx(protocol)) {
                ScopedValue.where(CURRENT_TX, tx).run(body);
                tx.commit();
                return;
            } catch (TransactionConflictException | VersionEvictedException e) {
                if (backoff.getAttempt() >= MAX_RETRIES) {
                    throw new MaxRetriesExceededException(MAX_RETRIES);
                }
                backoff.park();
            }
        }
    }

    private static ConcurrencyProtocol selectProtocol(TxMetrics metrics) {
        if (metrics.totalOps() == 0) {
            return ConcurrencyProtocol.Optimistic.INSTANCE;
        }
        double readRatio = (double) metrics.readOps() / metrics.totalOps();
        double writeRatio = (double) metrics.writeOps() / metrics.totalOps();
        if (readRatio >= READ_HEAVY_THRESHOLD) {
            return ConcurrencyProtocol.Optimistic.INSTANCE;
        }
        return writeRatio >= WRITE_HEAVY_THRESHOLD
                ? ConcurrencyProtocol.PessimisticWrite.INSTANCE
                : ConcurrencyProtocol.Optimistic.INSTANCE;
    }
}
