package tech.provokedynamic.weft.exception;

/** Signals that a requested snapshot predates the oldest retained history entry. */
public final class VersionEvictedException extends RuntimeException {

    /**
     * Creates an exception identifying the unavailable snapshot.
     *
     * @param requested requested snapshot version
     */
    public VersionEvictedException(long requested) {
        super("Version " + requested + " has been evicted from history.");
    }
}
