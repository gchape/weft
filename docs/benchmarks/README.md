# Benchmark run — 11 November 2026

Full JMH results for the current transaction implementation:
[`2026-11-11-jdk25.json`](2026-11-11-jdk25.json).
The summary table is in the [project README](../../README.md#results).

## Environment

| Setting | Value |
| --- | --- |
| JVM | Eclipse Temurin 25.0.5+8-LTS, OpenJDK 64-Bit Server VM |
| Operating system | Linux 7.3.3-200.fc44.x86_64 |
| CPU | AMD Ryzen 5 7535HS with Radeon Graphics |
| Cores / hardware threads | 6 / 12 |
| JMH | 1.37 |
| Mode | Throughput, operations per second |
| Warmup | 3 iterations × 1 second per fork |
| Measurement | 5 iterations × 2 seconds per fork |
| Forks | 2 per workload |
| Benchmark threads | 1, 4, or 8, as declared by each workload |
| Additional JVM arguments | None |
| Blackhole mode | Compiler, auto-detected by JMH |
| Java agent | Disabled; forced protocols use explicit `TxMetrics` hints |
| Source | Working tree based on commit `b5d89bbce9a3`, including the transaction and naming refactor |

Benchmark JAR SHA-256:

```text
971f18480e8197e58bb95a1513ea51356d7c52dcf0ed4c6386dfc473e9bcd787
```

## Reproduce

Use JDK 25 and build from the repository root:

```bash
mvn clean verify
java -jar iastm-benchmark/target/benchmarks.jar -foe true \
     -rf json -rff iastm-benchmark/target/jmh-results.json
```

JMH uses the JVM that launches the runner for its forks. The run above explicitly selected
the Temurin 25.0.5 installation through its `java` executable.

## Interpretation

All 10 workloads completed successfully, producing 100 measurement samples in total.
Scores and uncertainty are retained at full precision in the JSON, together with the JVM,
thread counts, settings, and per-fork measurement data. The README rounds values to whole
operations per second and reports JMH's 99.9% confidence-interval half-width.

The account pool contains 64 TVars initialized to 100,000 each and is reset before every
iteration. Random account selection distributes contention. A benchmark operation is one
completed benchmark invocation, including any transaction retries; it is not one individual
read or write. No instrumentation overhead is measured because the agent is not enabled.

The run used the local workstation's normal scheduling and power-management settings.
Results are environment-specific; repeat with a representative workload before drawing
application-level performance conclusions.
