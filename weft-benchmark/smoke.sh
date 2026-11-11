#!/bin/sh
# Execution checks only: these settings are too short for performance conclusions.
set -eu
cd "$(dirname "$0")/.."
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
jar=weft-benchmark/target/benchmarks.jar
run() {
  "$java_bin" -jar "$jar" "$@" -f 1 -wi 1 -w 100ms -i 1 -r 100ms -foe true
}
run 'WeftBenchmark\.' -t 4 -p access=UNIFORM,HOT,DISJOINT -p diagnostics=true
run CounterBenchmark -t 4
run ReaderWriterBenchmark -t 8 -tg 7,1 -p size=2 -p access=HOT -p diagnostics=true
run AgentBenchmark -t 4 -p expectAgent=false
run AgentBenchmark -t 4 -p expectAgent=true \
  -jvmArgsAppend '-javaagent:weft-agent/target/weft-agent-1.0-SNAPSHOT.jar=tech/provokedynamic/weft/benchmark/'
run 'WeftBenchmark.transfer' -t 8 -p accounts=2 -p size=2 -p access=HOT
run 'WeftBenchmark.transfer' -t 16 -p accounts=4096 -p size=16 -p access=DISJOINT
run 'WeftBenchmark.transfer' -t 4 -p size=2 -p access=HOT -bm sample -tu us
run 'WeftBenchmark.transfer' -t 4 -p size=2 -prof gc
