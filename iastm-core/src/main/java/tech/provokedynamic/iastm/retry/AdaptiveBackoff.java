package tech.provokedynamic.iastm.retry;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * Per-invocation retry delay that spins eight times, then parks with exponential jitter.
 * Mutable state is confined to the thread executing the outer transaction.
 */
@Slf4j
public final class AdaptiveBackoff {

    /**
     * Maximum jitter added to each sleep to desynchronize concurrent retriers.
     */
    private static final int JITTER_MS = 5;

    /**
     * Attempts up to and including this value use {@link Thread#onSpinWait()} — no parking.
     */
    private static final int SPIN_THRESHOLD = 8;

    /**
     * Attempts above {@link #SPIN_THRESHOLD} and up to this value use the short exponential ladder.
     */
    private static final int SHORT_THRESHOLD = 14;

    /**
     * Starting sleep for the short ladder (attempts 9–14).
     */
    private static final long SHORT_BASE_MS = 1L;

    /**
     * Ceiling for the short ladder; sleeps are capped here before jitter is added.
     */
    private static final long SHORT_MAX_MS = 16L;

    /**
     * Starting sleep for the long ladder (attempts 15+).
     */
    private static final long LONG_BASE_MS = 2L;

    /**
     * Ceiling for the long ladder; sleeps are capped here before jitter is added.
     */
    private static final long LONG_MAX_MS = 256L;

    /**
     * Number of {@link #park()} calls so far; checked against the transaction retry limit.
     */
    private int attempt = 0;

    /** Creates a backoff state with no retries performed. */
    public AdaptiveBackoff() {
    }

    /**
     * Returns the number of delays already requested.
     *
     * @return completed retry-delay count
     */
    public int getAttempt() {
        return attempt;
    }

    /**
     * Advances the retry count and delays according to its current phase.
     * Parked delays include zero to five milliseconds of jitter; spinning does not park.
     */
    public void park() {
        attempt++;
        if (attempt <= SPIN_THRESHOLD) {
            log.debug("backoff attempt={} spin-yield", attempt);
            Thread.onSpinWait();
            return;
        }
        long sleepMs;
        if (attempt <= SHORT_THRESHOLD) {
            long exponentialDelayMs = SHORT_BASE_MS * (1L << (attempt - SPIN_THRESHOLD - 1));
            sleepMs = Math.min(exponentialDelayMs, SHORT_MAX_MS);
        } else {
            long exponentialDelayMs = LONG_BASE_MS * (1L << Math.min(attempt - SHORT_THRESHOLD - 1, 7));
            sleepMs = Math.min(exponentialDelayMs, LONG_MAX_MS);
        }
        sleepMs += ThreadLocalRandom.current().nextLong(JITTER_MS + 1);
        log.debug("backoff attempt={} sleeping={}ms", attempt, sleepMs);
        LockSupport.parkNanos(sleepMs * 1_000_000L);
    }
}
