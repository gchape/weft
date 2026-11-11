package tech.provokedynamic.weft.atomic;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Monotonic counter used independently for commit timestamps and variable identities. */
final class Clock {

    private static final VarHandle COUNTER;

    static {
        try {
            COUNTER = MethodHandles.lookup().findVarHandle(Clock.class, "counter", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings({"FieldMayBeFinal", "unused"})
    private volatile long counter = 0L;

    /** Returns the latest visible counter value without advancing it. */
    long snapshot() {
        return counter;
    }

    /** Atomically reserves and returns the next counter value. */
    long advance() {
        return (long) COUNTER.getAndAdd(this, 1L) + 1L;
    }
}
