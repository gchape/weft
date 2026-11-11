package tech.provokedynamic.weft.exception;

import java.io.Serial;

/** Signals that a transaction exhausted its conflict retry budget. */
public final class MaxRetriesExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception identifying the retry limit.
     *
     * @param maxRetries configured number of retries after the initial attempt
     */
    public MaxRetriesExceededException(int maxRetries) {
        super("Transaction exceeded maximum retry limit of " + maxRetries);
    }
}
