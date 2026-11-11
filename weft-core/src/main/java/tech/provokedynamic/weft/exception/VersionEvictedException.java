package tech.provokedynamic.weft.exception;

import java.io.Serial;

/** Signals that a requested snapshot predates the oldest retained history entry. */
public final class VersionEvictedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception identifying the unavailable snapshot.
     *
     * @param readVersion requested snapshot version
     */
    public VersionEvictedException(long readVersion) {
        super("Version " + readVersion + " has been evicted from history.");
    }
}
