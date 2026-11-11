package tech.provokedynamic.weft.atomic;

import java.util.concurrent.atomic.AtomicLong;

/** Monotonic counter used independently for commit timestamps and variable identities. */
final class Clock {

    private final AtomicLong counter = new AtomicLong();

    /** Returns the latest visible counter value without advancing it. */
    long snapshot() {
        return counter.get();
    }

    /** Atomically reserves and returns the next counter value. */
    long advance() {
        return counter.incrementAndGet();
    }
}
