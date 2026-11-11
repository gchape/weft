package tech.provokedynamic.iastm.exception;

/** Signals that a transaction exhausted its conflict retry budget. */
public final class MaxRetriesExceededException extends RuntimeException {

    /**
     * Creates an exception identifying the retry limit.
     *
     * @param maxRetries configured number of retries after the initial attempt
     */
    public MaxRetriesExceededException(int maxRetries) {
        super("Transaction exceeded maximum retry limit of " + maxRetries);
    }
}
