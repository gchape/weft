package tech.provokedynamic.weft.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import tech.provokedynamic.weft.TransactionHints;
import tech.provokedynamic.weft.atomic.Weft;
import tech.provokedynamic.weft.atomic.TVar;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** JMH workloads for read-only transactions, increments, and multi-account transfers. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@State(Scope.Benchmark)
@SuppressWarnings("unused")
public class WeftBenchmark {

    /**
     * Metrics hint representing a read-heavy workload (90 % reads, 10 % writes).
     * Passed to {@link Weft#atomic(Runnable, TransactionHints)} to select the optimistic protocol.
     */
    private static final TransactionHints READ_HEAVY = new TransactionHints(9, 1);

    /**
     * Metrics hint representing a write-heavy workload (10 % reads, 90 % writes).
     * Passed to {@link Weft#atomic(Runnable, TransactionHints)} to select the pessimistic write protocol.
     */
    private static final TransactionHints WRITE_HEAVY = new TransactionHints(1, 9);

    /**
     * Number of simulated bank accounts. A power-of-two-friendly value that
     * provides enough variables to spread contention across threads.
     */
    private static final int ACCOUNT_COUNT = 64;

    /**
     * Shared counter used by single-threaded baseline benchmarks.
     */
    private TVar<Integer> counter;

    /**
     * Array of account {@code TVar}s used by transferOneUnit and multi-read benchmarks.
     */
    private TVar<Integer>[] accounts;

    /** Creates benchmark state; JMH initializes its variables before each iteration. */
    public WeftBenchmark() {
    }

    /**
     * Reinitialize all shared state before each measurement iteration to prevent
     * counter overflow or balance depletion from affecting timing results.
     */
    @SuppressWarnings("unchecked")
    @Setup(Level.Iteration)
    public void setup() {
        counter = new TVar<>(0);
        accounts = new TVar[ACCOUNT_COUNT];
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            accounts[i] = new TVar<>(100_000);
        }
    }

    /**
     * Baseline: single-threaded read of a single {@code TVar} with no contention.
     * Establishes the minimum per-transaction overhead.
     *
     * @param blackhole JMH sink that consumes the committed read result
     */
    @Benchmark
    @Threads(1)
    public void baseline_read_1t(Blackhole blackhole) {
        int[] result = new int[1];
        Weft.atomic(() -> result[0] = Weft.read(counter));
        blackhole.consume(result[0]);
    }

    /**
     * Baseline: single-threaded read-modify-write of a single {@code TVar}.
     * Isolates commit cost without any inter-thread conflict.
     */
    @Benchmark
    @Threads(1)
    public void baseline_increment_1t() {
        Weft.atomic(() -> Weft.write(counter, Weft.read(counter) + 1));
    }

    /**
     * Pessimistic bank transfer under 4-thread contention.
     */
    @Benchmark
    @Threads(4)
    public void bankTransfer_pessimistic_4t() {
        transferOneUnit(true);
    }

    /**
     * Pessimistic bank transfer under 8-thread contention.
     */
    @Benchmark
    @Threads(8)
    public void bankTransfer_pessimistic_8t() {
        transferOneUnit(true);
    }

    /**
     * Optimistic multi-read under 4-thread contention.
     *
     * @param blackhole JMH sink that consumes the committed read result
     */
    @Benchmark
    @Threads(4)
    public void multiRead_optimistic_4t(Blackhole blackhole) {
        sumAdjacentAccounts(blackhole);
    }

    /**
     * Optimistic multi-read under 8-thread contention.
     *
     * @param blackhole JMH sink that consumes the committed read result
     */
    @Benchmark
    @Threads(8)
    public void multiRead_optimistic_8t(Blackhole blackhole) {
        sumAdjacentAccounts(blackhole);
    }

    /**
     * Optimistic bank transfer under 4-thread contention.
     */
    @Benchmark
    @Threads(4)
    public void bankTransfer_optimistic_4t() {
        transferOneUnit(false);
    }

    /**
     * Optimistic bank transfer under 8-thread contention.
     */
    @Benchmark
    @Threads(8)
    public void bankTransfer_optimistic_8t() {
        transferOneUnit(false);
    }

    /**
     * Mixed read-heavy workload (8 reads + 1 write) under 4-thread contention.
     * Uses an explicit {@link #READ_HEAVY} metrics hint to select the optimistic strategy.
     *
     * @param blackhole JMH sink that consumes the committed read result
     */
    @Benchmark
    @Threads(4)
    public void mixed_readHeavy_4t(Blackhole blackhole) {
        runReadHeavyWorkload(blackhole);
    }

    /**
     * Mixed read-heavy workload (8 reads + 1 write) under 8-thread contention.
     *
     * @param blackhole JMH sink that consumes the committed read result
     */
    @Benchmark
    @Threads(8)
    public void mixed_readHeavy_8t(Blackhole blackhole) {
        runReadHeavyWorkload(blackhole);
    }

    /**
     * Reads two adjacent accounts and sums their balances in a single snapshot
     * transaction. The pair is chosen randomly to distribute read-set overlap
     * across threads.
     */
    private void sumAdjacentAccounts(Blackhole blackhole) {
        int accountIndex = ThreadLocalRandom.current().nextInt(ACCOUNT_COUNT);
        TVar<Integer> firstAccount = accounts[accountIndex];
        TVar<Integer> secondAccount = accounts[(accountIndex + 1) % ACCOUNT_COUNT];
        int[] sum = new int[1];
        Weft.atomic(() -> sum[0] = Weft.read(firstAccount) + Weft.read(secondAccount));
        blackhole.consume(sum[0]);
    }

    /**
     * Reads 8 consecutive accounts, then increments a single "sink" account on
     * the opposite side of the array. Passes {@link #READ_HEAVY} to bias strategy
     * selection towards optimistic.
     */
    private void runReadHeavyWorkload(Blackhole blackhole) {
        int accountIndex = ThreadLocalRandom.current().nextInt(ACCOUNT_COUNT);
        int[] result = new int[1];
        Weft.atomic(() -> {
            int sum = 0;
            for (int i = 0; i < 8; i++) {
                sum += Weft.read(accounts[(accountIndex + i) % ACCOUNT_COUNT]);
            }
            TVar<Integer> sink = accounts[(accountIndex + ACCOUNT_COUNT / 2) % ACCOUNT_COUNT];
            Weft.write(sink, Weft.read(sink) + 1);
            result[0] = sum;
        }, READ_HEAVY);
        blackhole.consume(result[0]);
    }

    /**
     * Transfers 1 unit from a randomly chosen source account to a distinct
     * destination account. The transfer is skipped if the source balance is zero
     * to prevent negative balances. Strategy is selected via {@code pessimistic} flag.
     *
     * @param pessimistic if {@code true}, passes {@link #WRITE_HEAVY} metrics to force
     * the pessimistic write protocol; otherwise uses the default
     * optimistic strategy
     */
    private void transferOneUnit(boolean pessimistic) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int from = random.nextInt(ACCOUNT_COUNT);
        int to = (from + 1 + random.nextInt(ACCOUNT_COUNT - 1)) % ACCOUNT_COUNT;
        TVar<Integer> source = accounts[from];
        TVar<Integer> destination = accounts[to];
        Runnable body = () -> {
            int balance = Weft.read(source);
            if (balance > 0) {
                Weft.write(source, balance - 1);
                Weft.write(destination, Weft.read(destination) + 1);
            }
        };
        if (pessimistic) Weft.atomic(body, WRITE_HEAVY);
        else Weft.atomic(body);
    }
}
