package tech.provokedynamic.weft.mvcc;

import org.jspecify.annotations.Nullable;

import tech.provokedynamic.weft.exception.VersionEvictedException;

/**
 * Bounded version storage with explicit capacity changes.
 * Callers must coordinate readers with concurrent writes and resizing.
 *
 * @param <T> stored value type, including its nullability
 */
public sealed interface VersionHistory<T extends @Nullable Object> permits CircularVersionHistory {

    /**
     * Finds the newest retained value whose version is at most the requested read point.
     *
     * @param readVersion maximum visible version
     * @return the retained value, which may be null when {@code T} is nullable
     * @throws VersionEvictedException if the point predates the retained history
     */
    T scan(long readVersion);

    /**
     * Appends a value and its commit version, evicting the oldest entry when full.
     *
     * @param value value to retain, which may be null
     * @param version commit stamp, greater than the previous entry's stamp
     */
    void append(T value, long version);

    /**
     * Expands capacity up to the implementation's maximum without restoring evicted entries.
     *
     * @param factor finite multiplier greater than or equal to one
     * @throws IllegalArgumentException if the multiplier is invalid
     */
    void expandByFactor(float factor);

    /**
     * Shrinks capacity down to the implementation's minimum, retaining the newest entries.
     *
     * @param factor finite multiplier in the interval (0, 1]
     * @throws IllegalArgumentException if the multiplier is invalid
     */
    void shrinkByFactor(float factor);
}
