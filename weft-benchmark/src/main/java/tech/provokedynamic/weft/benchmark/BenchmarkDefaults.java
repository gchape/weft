package tech.provokedynamic.weft.benchmark;

import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.infra.Control;

/** Common defaults; override on the JMH command line for exploratory runs. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 8, time = 2)
@Fork(3)
public abstract class BenchmarkDefaults {
    /** Creates a benchmark with the shared JMH defaults. */
    protected BenchmarkDefaults() {
    }

    /**
     * Determines whether the current iteration is outside its active workload window.
     *
     * <p>JMH activates this window for both warmup and measurement iterations. Preventing
     * new transactions during synchronization loops lets workers finish outstanding retries.
     *
     * @param control JMH iteration lifecycle flags
     * @return whether timing has not started or has stopped
     */
    static boolean inactive(Control control) {
        return !control.startMeasurement || control.stopMeasurement;
    }
}
