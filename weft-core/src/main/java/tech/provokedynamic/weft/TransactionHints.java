package tech.provokedynamic.weft;

/**
 * Static operation counts used to choose a transaction's concurrency protocol.
 *
 * @param readOps non-negative number of read call sites
 * @param writeOps non-negative number of write call sites
 * @param totalOps non-negative normalization total; ordinarily the sum of reads and writes
 */
public record TransactionHints(
        int readOps,
        int writeOps,
        int totalOps
) {
    /**
     * Validates counts while retaining an explicitly supplied normalization total.
     *
     * @param readOps non-negative read count
     * @param writeOps non-negative write count
     * @param totalOps non-negative normalization total
     * @throws IllegalArgumentException if any count is negative
     */
    public TransactionHints {
        if (readOps < 0 || writeOps < 0 || totalOps < 0) {
            throw new IllegalArgumentException("Operation counts must be non-negative");
        }
    }

    /**
     * Creates metrics with the total derived from read and write counts.
     *
     * @param readOps non-negative read count
     * @param writeOps non-negative write count
     * @throws IllegalArgumentException if a count is negative
     * @throws ArithmeticException if their sum exceeds the integer range
     */
    public TransactionHints(int readOps, int writeOps) {
        this(readOps, writeOps, Math.addExact(readOps, writeOps));
    }
}
