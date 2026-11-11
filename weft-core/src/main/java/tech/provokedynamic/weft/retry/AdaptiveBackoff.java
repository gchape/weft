package tech.provokedynamic.weft.retry;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongUnaryOperator;

/**
 * Per-invocation retry delay that spins eight times, then parks with exponential jitter.
 * Mutable state is confined to the thread executing the outer transaction.
 *
 * <p>The first 512 retries request less than 130 milliseconds of total parking, including
 * maximum jitter. Scheduling and transaction execution can increase elapsed time.
 */
@Slf4j
public final class AdaptiveBackoff {

    /**
     * Maximum jitter added to each parked delay to desynchronize concurrent retriers.
     */
    private static final int JITTER_NANOS = 5_000;

    /**
     * Retry numbers up to and including this value use {@link Thread#onSpinWait()}.
     */
    private static final int SPIN_THRESHOLD = 8;

    /**
     * Retry numbers above {@link #SPIN_THRESHOLD} and up to this value use the short ladder.
     */
    private static final int SHORT_THRESHOLD = 14;

    /**
     * Starting delay for the short ladder (retries 9–14).
     */
    private static final long SHORT_BASE_NANOS = 1_000L;

    /**
     * Ceiling for the short ladder, before jitter is added.
     */
    private static final long SHORT_MAX_NANOS = 16_000L;

    /**
     * Starting delay for the long ladder (retries 15+).
     */
    private static final long LONG_BASE_NANOS = 2_000L;

    /**
     * Ceiling for the long ladder, before jitter is added.
     */
    private static final long LONG_MAX_NANOS = 256_000L;

    /**
     * Number of {@link #pause()} calls so far; checked against the transaction retry limit.
     */
    private int retries;
    private final LongConsumer parkNanos;
    private final LongUnaryOperator randomBelow;

    /** Creates a backoff state with no retries performed. */
    public AdaptiveBackoff() {
        this(LockSupport::parkNanos, bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    AdaptiveBackoff(LongConsumer parkNanos, LongUnaryOperator randomBelow) {
        this.parkNanos = parkNanos;
        this.randomBelow = randomBelow;
    }

    /**
     * Returns the number of retries for which a delay has been requested.
     *
     * @return retry count, initially zero
     */
    public int retries() {
        return retries;
    }

    /**
     * Advances the retry count and delays according to its current phase.
     * Parked delays include zero to five microseconds of jitter; the first eight retries spin.
     */
    public void pause() {
        retries++;
        if (retries <= SPIN_THRESHOLD) {
            if (log.isDebugEnabled()) log.debug("backoff retry={} spin", retries);
            Thread.onSpinWait();
            return;
        }
        long delayNanos;
        if (retries <= SHORT_THRESHOLD) {
            long exponentialDelay = SHORT_BASE_NANOS * (1L << (retries - SPIN_THRESHOLD - 1));
            delayNanos = Math.min(exponentialDelay, SHORT_MAX_NANOS);
        } else {
            long exponentialDelay = LONG_BASE_NANOS * (1L << Math.min(retries - SHORT_THRESHOLD - 1, 7));
            delayNanos = Math.min(exponentialDelay, LONG_MAX_NANOS);
        }
        delayNanos += randomBelow.applyAsLong(JITTER_NANOS + 1);
        if (log.isDebugEnabled()) log.debug("backoff retry={} parking={}ns", retries, delayNanos);
        parkNanos.accept(delayNanos);
    }
}
