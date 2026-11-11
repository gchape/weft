package tech.provokedynamic.weft.benchmark;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;
import tech.provokedynamic.weft.TransactionHints;
import tech.provokedynamic.weft.atomic.TVar;
import tech.provokedynamic.weft.atomic.Weft;
import tech.provokedynamic.weft.exception.MaxRetriesExceededException;

import java.util.concurrent.locks.ReentrantLock;

/** Shared accounts with equivalent atomic semantics for every implementation. */
@State(Scope.Benchmark)
public class WorkloadState {
    /** Implementation used to keep a complete account operation atomic. */
    public enum Engine {
        /** Weft transactions with write locks deferred until commit. */
        OPTIMISTIC,
        /** Weft transactions with eager write locking. */
        PESSIMISTIC,
        /** Plain account balances protected by one shared lock. */
        LOCKED
    }

    /** Distribution of the starting account selected for each operation. */
    public enum Access {
        /** Chooses a random starting account across the whole pool. */
        UNIFORM,
        /** Chooses account zero in the hot branch and otherwise samples the whole pool. */
        HOT,
        /** Assigns each worker a separate contiguous account partition. */
        DISJOINT
    }

    /** Atomicity implementation selected for this benchmark run. */
    @Param({"OPTIMISTIC", "PESSIMISTIC", "LOCKED"}) public Engine engine;
    /** Total number of accounts shared by the benchmark workers. */
    @Param({"64"}) public int accounts;
    /** Account access distribution selected for this benchmark run. */
    @Param({"UNIFORM"}) public Access access;
    /** Number of distinct accounts touched by each transaction; must be even. */
    @Param({"2", "16"}) public int size;
    /** Counts body attempts and outcomes; deliberately disabled for primary timing. */
    @Param({"false"}) public boolean diagnostics;

    private static final long INITIAL_BALANCE = 1_000_000;
    private static final TransactionHints OPTIMISTIC_HINTS = new TransactionHints(1, 0);
    private static final TransactionHints PESSIMISTIC_HINTS = new TransactionHints(0, 1);
    private final ReentrantLock lock = new ReentrantLock();
    private TVar<Long>[] balances;
    private long[] plainBalances;
    int partitionSize;

    /** Creates shared account state for JMH. */
    public WorkloadState() {
    }

    /**
     * Initializes account balances and determines the account range available to each worker.
     *
     * @param params JMH run configuration supplying the total worker count
     * @throws IllegalArgumentException if the account count or transaction size is invalid,
     *         or a worker's partition cannot accommodate the requested size
     */
    @Setup(Level.Iteration)
    public void setup(BenchmarkParams params) { initialize(accounts, size, params.getThreads()); }

    /**
     * Validates and initializes sizes supplied by JMH or a direct caller.
     *
     * @param accounts total account count
     * @param size even number of distinct accounts touched by an operation
     * @param threads number of workers sharing the account pool
     * @throws IllegalArgumentException if the sizes cannot support the requested operation
     */
    @SuppressWarnings("unchecked")
    void initialize(int accounts, int size, int threads) {
        if (accounts < 2 || size < 2 || (size & 1) != 0) {
            throw new IllegalArgumentException("accounts >= 2 and even size >= 2 required");
        }
        partitionSize = access == Access.DISJOINT ? accounts / threads : accounts;
        if (size > partitionSize) throw new IllegalArgumentException("size exceeds accounts available per worker");
        this.accounts = accounts;
        this.size = size;
        balances = new TVar[accounts];
        plainBalances = new long[accounts];
        for (int i = 0; i < accounts; i++) {
            balances[i] = new TVar<>(INITIAL_BALANCE);
            plainBalances[i] = INITIAL_BALANCE;
        }
    }

    int index(WorkerState worker, int offset) {
        int partition = access == Access.DISJOINT ? worker.threadIndex * partitionSize : 0;
        return partition + (worker.start + offset) % partitionSize;
    }

    /** Chooses the access path once, so retries repeat the same operation. */
    void choose(WorkerState worker) {
        worker.start = access == Access.HOT && worker.random.nextInt(10) < 8
                ? 0 : worker.random.nextInt(partitionSize);
        worker.reverse = worker.random.nextBoolean();
    }

    /**
     * Runs one operation and records its outcome when diagnostics are enabled.
     *
     * <p>Both transactional protocols use the same atomic overload and workload body.
     * Account selection and direction are fixed before entering the retry loop.
     *
     * @param worker access stream, sum storage, and optional diagnostic counters
     * @param write whether to transfer units between accounts
     * @param readAll whether to sum the selected account set before any transfers
     * @return the sum when reading the whole set, otherwise zero; {@link Long#MIN_VALUE}
     *         indicates exhausted retries during a diagnostic run
     */
    long run(WorkerState worker, boolean write, boolean readAll) {
        choose(worker);
        worker.transferred = false;
        worker.sum = 0;
        if (engine == Engine.LOCKED) {
            lock.lock();
            try { body(worker, write, readAll, false); }
            finally { lock.unlock(); }
        } else {
            try {
                Weft.atomic(() -> body(worker, write, readAll, true),
                        engine == Engine.OPTIMISTIC ? OPTIMISTIC_HINTS : PESSIMISTIC_HINTS);
            } catch (MaxRetriesExceededException failure) {
                if (!diagnostics) throw failure;
                worker.exhausted++;
                return Long.MIN_VALUE;
            }
        }
        if (diagnostics) {
            worker.completed++;
            if (write && !worker.transferred) worker.noOps++;
        }
        return worker.sum;
    }

    /**
     * Executes one attempt, resetting its sum and transfer marker before doing any work.
     *
     * <p>Resetting per-attempt output prevents aborted attempts from reporting a successful
     * transfer. Mixed operations read the whole account set and transfer across its first
     * pair; transfer-only operations visit every selected pair.
     *
     * @param worker fixed account selection and per-attempt output storage
     * @param write whether to transfer units between accounts
     * @param readAll whether to sum the selected accounts before any transfers
     * @param transactional whether account access uses Weft rather than the locked baseline
     */
    private void body(WorkerState worker, boolean write, boolean readAll, boolean transactional) {
        if (diagnostics) worker.attempts++;
        worker.transferred = false;
        worker.sum = 0;
        if (readAll) {
            for (int i = 0; i < size; i++) worker.sum += read(index(worker, i), transactional);
        }
        if (write) {
            int limit = readAll ? 2 : size;
            for (int i = 0; i < limit; i += 2) {
                int from = index(worker, i + (worker.reverse ? 1 : 0));
                int to = index(worker, i + (worker.reverse ? 0 : 1));
                long balance = read(from, transactional);
                if (balance > 0) {
                    long target = read(to, transactional);
                    write(from, balance - 1, transactional);
                    write(to, target + 1, transactional);
                    worker.transferred = true;
                }
            }
        }
    }

    private long read(int index, boolean transactional) {
        return transactional ? Weft.read(balances[index]) : plainBalances[index];
    }

    private void write(int index, long value, boolean transactional) {
        if (transactional) Weft.write(balances[index], value);
        else plainBalances[index] = value;
    }

    /**
     * Checks non-negative balances, conservation of the total balance, and lock cleanup.
     *
     * @throws AssertionError if a balance invariant is broken or the baseline lock remains held
     */
    @TearDown(Level.Iteration)
    public void verify() {
        long total = 0;
        for (int i = 0; i < accounts; i++) {
            long value;
            if (engine == Engine.LOCKED) value = plainBalances[i];
            else {
                long[] observed = new long[1];
                TVar<Long> variable = balances[i];
                Weft.atomic(() -> observed[0] = Weft.read(variable));
                value = observed[0];
            }
            if (value < 0) throw new AssertionError("Negative balance");
            total += value;
        }
        if (total != accounts * INITIAL_BALANCE) throw new AssertionError("Total balance changed: " + total);
        if (lock.isLocked()) throw new AssertionError("Leaked baseline lock");
    }
}
