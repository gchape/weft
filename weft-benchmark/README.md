# Weft benchmarks

Use JDK 25 and build with `./mvnw clean verify`. Run from the repository root.
The default measurement policy is five 2-second warmups, eight 2-second measurements,
and three separate JVM forks. CLI overrides allow short execution checks. No short
execution check establishes performance or a protocol ranking.

Run `JAVA_HOME=/path/to/jdk25 weft-benchmark/smoke.sh` after building to exercise
all workload families, policies, access patterns, both transaction sizes, agent
activation, sampled latency, and GC profiling with deliberately short iterations.
The full check launches many JVMs and typically takes a few minutes.

## Workloads and equivalent comparisons

| Class / method                    | One measured invocation                                                             |
| --------------------------------- | ----------------------------------------------------------------------------------- |
| `CounterBenchmark.read`           | One single-value read                                                               |
| `CounterBenchmark.increment`      | One single-value increment                                                          |
| `WeftBenchmark.readOnly`          | Read and sum `size` distinct accounts                                               |
| `WeftBenchmark.transfer`          | Transfer one unit across each of `size / 2` disjoint pairs, atomically as a batch   |
| `WeftBenchmark.mixed`             | Read `size` accounts, then read/modify/write the first pair in the same transaction |
| `ReaderWriterBenchmark.readWrite` | Separate reader and transfer-writer threads on one shared account pool              |
| `AgentBenchmark.writeTriple`      | Read one value and atomically write the incremented value to three references       |

`OPTIMISTIC` and `PESSIMISTIC` both use the explicit-hints overload to force the policy.
Those hints select a policy; they do not claim to describe the workload's actual counts.
`LOCKED` uses one `ReentrantLock` around the complete operation, with primitive long
balances. This is a practical coarse-lock baseline, not a comparison of only lock
instructions: STM's boxing, tracking, and version-storage costs are included.
`ATOMIC` is available only in `CounterBenchmark`, where `AtomicLong` provides equivalent
single-value semantics. Two independent atomic updates would not be equivalent to a transfer.

Every benchmark checks JMH's `Control` flags before issuing work, including baselines.
Operations run during each iteration's active timing window (including warmup iterations).
JMH synchronization loops issue no new transactions. This prevents fast workers from
continually conflicting with slow in-flight retries during warmdown, and keeps those
extra operations out of diagnostic counters. In-flight operations finish normally;
no retry is cancelled or given a shorter budget. The two flag reads are part of every
implementation's measured overhead. See [JMH control](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_18_Control.java).

Thread-local seeded random streams are reset each iteration. The same worker index
receives the same access sequence across engines; scheduling and completed prefix lengths
can still differ. Selection occurs before entering a transaction, so retries repeat the
same operation. Random selection and result handling are included in the measured cost.
Transfer direction is randomized to avoid systematically emptying hot source accounts.
Balances start at 1,000,000 and all state is reset each iteration.

All account operations conserve total balance. Teardown checks the total and rejects
negative balances. Counter teardown compares the final value with the sum of worker-local
completed increments (the bookkeeping increment is included equally for every engine).
Agent teardown checks all three values agree. These checks supplement concurrency tests;
they do not prove every intermediate snapshot was valid.

## Parameters and coverage

Account workloads share these parameters:

| Parameter      | Default           | Suggested sweep                                |
| -------------- | ----------------- | ---------------------------------------------- |
| `engine`       | All three engines | OPTIMISTIC, PESSIMISTIC, LOCKED                |
| `accounts`     | 64                | 2, 64, 4096                                    |
| `size`         | 2 and 16          | Even number no greater than available accounts |
| `access`       | UNIFORM           | UNIFORM, HOT, DISJOINT                         |
| `diagnostics`  | false             | true in a separate diagnostic run              |
| Threads (`-t`) | JMH default       | 1, 2, 4, 8, 16                                 |

UNIFORM chooses a random start for a contiguous circular window. HOT selects start zero
80% of the time and a uniform start otherwise. DISJOINT partitions the account array by
worker index; each worker accesses only its partition. The account count remains the
same across engines. Invalid combinations fail setup: in DISJOINT mode require
`accounts / threads >= size`; otherwise require `accounts >= size`. Remainder accounts
in a disjoint partition are unused. DISJOINT removes TVar overlap but STM still shares
its commit clock; the coarse-lock baseline still shares its global lock.

Start with this matrix; expand one dimension at a time rather than interpreting one
large aggregate score:

| Purpose                   | Accounts | Size  | Access            | Threads        |
| ------------------------- | -------- | ----- | ----------------- | -------------- |
| Extreme contention        | 2        | 2     | UNIFORM           | 1, 2, 4, 8, 16 |
| Typical small transaction | 64       | 2     | UNIFORM, HOT      | 1, 2, 4, 8, 16 |
| Larger transaction        | 64       | 16    | UNIFORM, HOT      | 1, 2, 4, 8, 16 |
| Larger working set        | 4096     | 2, 16 | UNIFORM, DISJOINT | 1, 2, 4, 8, 16 |

Example throughput sweep (all three engines):

```bash
for threads in 1 2 4 8 16; do
  java -jar weft-benchmark/target/benchmarks.jar 'WeftBenchmark.transfer' \
    -p accounts=64 -p size=2,16 -p access=UNIFORM,HOT -t "$threads" -foe true \
    -rf json -rff "weft-benchmark/target/transfer-${threads}t.json"
done
```

The reader/writer group defaults to three readers and one writer. `-tg` lists counts
in method-name order (`read`, `write`). Use one group first, or multiples of its size:

```bash
java -jar weft-benchmark/target/benchmarks.jar ReaderWriterBenchmark \
  -p size=2 -tg 7,1 -t 8 -foe true -rf json -rff weft-benchmark/target/read-mostly.json
java -jar weft-benchmark/target/benchmarks.jar ReaderWriterBenchmark \
  -p size=2 -tg 1,3 -t 4 -foe true -rf json -rff weft-benchmark/target/write-mostly.json
```

All groups share the same account state. Report reader and writer subgroup throughput
separately; their operations perform different amounts of work. Worker counts describe
concurrency, not the ratio of completed operations. With DISJOINT access the readers
and writers intentionally do not overlap.

## Latency, allocation, and retry diagnostics

Use sampled latency in a separate run; JMH JSON includes percentile data. It describes
closed-loop service time, including internal retries, not externally queued request
latency under a fixed arrival rate.

```bash
java -jar weft-benchmark/target/benchmarks.jar 'WeftBenchmark.transfer' \
  -p accounts=64 -p size=2 -p access=HOT -t 8 -bm sample -tu us -foe true \
  -rf json -rff weft-benchmark/target/latency.json
java -jar weft-benchmark/target/benchmarks.jar 'WeftBenchmark.transfer' \
  -p accounts=64 -p size=2 -p access=HOT -t 8 -prof gc -foe true \
  -rf json -rff weft-benchmark/target/allocation.json
java -jar weft-benchmark/target/benchmarks.jar 'WeftBenchmark.transfer' \
  -p accounts=64 -p size=2 -p access=HOT -p diagnostics=true -t 8 -foe true \
  -rf json -rff weft-benchmark/target/diagnostics.json
```

Diagnostics use JMH thread-local auxiliary event counters: `attempts` counts body
entries, `completed` counts committed invocations, `noOps` counts committed write
invocations that moved no money, and `exhausted` counts retry-budget failures.
For each measurement interval, retries = attempts - completed - exhausted. A batch
that moves money in only some pairs counts as a completed, non-no-op invocation.
These are event counts, not throughput rates. Use matched intervals when deriving
ratios. For groups, the shared counter names aggregate reader and writer events.
Counters remain zero when diagnostics are disabled.

Primary timing fails on retry exhaustion. Diagnostic timing catches only that exception
to record it; its invocation throughput therefore includes failures and must not be
presented as successful-transaction throughput. Diagnostic bookkeeping changes timing;
compare diagnostic runs with other diagnostic runs. Backoff pauses make tail latency
especially important. Do not subtract two independently measured benchmark means to
claim a precise internal operation cost.

## Instrumentation

The general account workloads deliberately use shared helper bodies and explicit hints.
Use `AgentBenchmark` to measure instrumentation. It contains directly supplied local
lambdas and verifies actual eager-lock behavior outside the timed region. A missing agent,
wrong scan prefix, or incorrect `expectAgent` setting fails setup.

```bash
java -jar weft-benchmark/target/benchmarks.jar AgentBenchmark \
  -p selection=DEFAULT,EXPLICIT -p expectAgent=false -t 4 -foe true \
  -rf json -rff weft-benchmark/target/agent-off.json
java -jar weft-benchmark/target/benchmarks.jar AgentBenchmark \
  -p selection=DEFAULT,EXPLICIT -p expectAgent=true -t 4 -foe true \
  -jvmArgsAppend '-javaagent:weft-agent/target/weft-agent-1.0-SNAPSHOT.jar=tech/provokedynamic/weft/benchmark/' \
  -rf json -rff weft-benchmark/target/agent-on.json
```

`-jvmArgsAppend` passes the agent into each measured fork. DEFAULT without the agent
uses optimistic locking; DEFAULT with the agent gets static (1 read, 3 writes) hints
and pessimistic locking. EXPLICIT supplies the same hints with or without the agent.
Compare DEFAULT-on with EXPLICIT-on to assess steady-state injected-hint overhead;
DEFAULT-on versus DEFAULT-off also changes protocol. Startup transformation time is
outside these steady-state measurements and requires a separate startup experiment.
This fixed workload does not establish that the selection threshold is universally optimal.

## Reporting

Retain raw JSON and console output with source revision, JDK build, JVM flags, heap/GC,
CPU/core count, OS, thread count, parameters, power mode, and background-load conditions.
Repeat with reversed or randomized engine order if machine conditions drift. Inspect
per-fork means and iteration trends. Extend warmup/forks when results remain unstable;
three forks is a starting point, not a stability guarantee. Report confidence intervals,
latency percentiles, bytes/op, and failures alongside throughput. Never mix differently
sized transactions into one performance ranking. Current benchmark workers are platform
threads; virtual-thread request scheduling requires a separate workload.

JMH references: [forking](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_12_Forking.java),
[reader/writer groups](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_15_Asymmetric.java),
[auxiliary counters](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_23_AuxCounters.java),
[profilers](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_35_Profilers.java).
