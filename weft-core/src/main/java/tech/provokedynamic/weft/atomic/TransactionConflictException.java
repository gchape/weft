package tech.provokedynamic.weft.atomic;

import java.io.Serial;
import java.util.ConcurrentModificationException;

/** Internal retry signal, distinct from exceptions thrown by application code. */
final class TransactionConflictException extends ConcurrentModificationException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Keeps this internal control-flow signal free of stack-trace capture.
     *
     * @return this exception
     */
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
