package tech.provokedynamic.weft.mvcc;

import org.jspecify.annotations.Nullable;

import tech.provokedynamic.weft.exception.VersionEvictedException;

/**
 * Circular version history with binary-search snapshot lookup and capacities from 32 to 2,048.
 *
 * <p>Commit stamps must be appended in increasing order. This class does not synchronize
 * access: callers must coordinate reads with writers and resizing. TVars hold their
 * shared read lock while scanning, and their write lock while appending or resizing history.
 *
 * @param <T> stored value type, including its nullability
 */
public final class CircularVersionHistory<T extends @Nullable Object> implements VersionHistory<T> {

    private static final int MIN_CAPACITY = 32;
    private static final int MAX_CAPACITY = 2048;

    private long[] versions;
    private @Nullable Object[] values;
    private long nextPosition;

    /**
     * Creates a 32-entry history containing the initial value at version zero.
     *
     * @param initial initial value, which may be null when {@code T} is nullable
     */
    public CircularVersionHistory(T initial) {
        versions = new long[MIN_CAPACITY];
        values = new Object[MIN_CAPACITY];
        versions[0] = 0L;
        values[0] = initial;
        nextPosition = 1L;
    }

    @Override
    public void append(T value, long version) {
        int slot = (int) (nextPosition % values.length);
        values[slot] = value;
        versions[slot] = version;
        nextPosition++;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T scan(long readVersion) {
        long readPosition = nextPosition;
        int capacity = values.length;
        long retainedCount = Math.min(readPosition, capacity);
        long lowerBound = readPosition - retainedCount;
        long upperBound = readPosition - 1;

        if (readVersion < versions[(int) (lowerBound % capacity)]) {
            throw new VersionEvictedException(readVersion);
        }

        while (lowerBound < upperBound) {
            long midpoint = lowerBound + ((upperBound - lowerBound + 1) >>> 1);
            if (versions[(int) (midpoint % capacity)] <= readVersion) {
                lowerBound = midpoint;
            } else {
                upperBound = midpoint - 1;
            }
        }
        return (T) values[(int) (lowerBound % capacity)];
    }

    @Override
    public void expandByFactor(float factor) {
        if (!Float.isFinite(factor) || factor < 1) {
            throw new IllegalArgumentException("Expansion factor must be finite and >= 1");
        }
        int capacity = values.length;
        int newCapacity = Math.min((int) (capacity * factor), MAX_CAPACITY);
        if (newCapacity > capacity) resize(newCapacity);
    }

    @Override
    public void shrinkByFactor(float factor) {
        if (!Float.isFinite(factor) || factor <= 0 || factor > 1) {
            throw new IllegalArgumentException("Shrink factor must be finite and in (0, 1]");
        }
        int capacity = values.length;
        int newCapacity = Math.max((int) (capacity * factor), MIN_CAPACITY);
        if (newCapacity < capacity) resize(newCapacity);
    }

    /** Copies only surviving entries into chronological order and rebases the append cursor. */
    private void resize(int newCapacity) {
        int capacity = values.length;
        long[] resizedVersions = new long[newCapacity];
        @Nullable Object[] resizedValues = new Object[newCapacity];

        long retainedCount = Math.min(Math.min(nextPosition, capacity), newCapacity);
        long oldestPosition = nextPosition - retainedCount;

        for (long i = 0; i < retainedCount; i++) {
            long position = oldestPosition + i;
            int sourceSlot = (int) (position % capacity);
            int targetSlot = (int) i;
            resizedValues[targetSlot] = values[sourceSlot];
            resizedVersions[targetSlot] = versions[sourceSlot];
        }

        values = resizedValues;
        versions = resizedVersions;
        nextPosition = retainedCount;
    }
}
