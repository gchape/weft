package tech.provokedynamic.weft.retry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveBackoffTest {

    @Test
    void startsAtZeroRetries() {
        assertThat(new AdaptiveBackoff().retries()).isZero();
    }

    @Test
    void countsEachRetry() {
        var backoff = new AdaptiveBackoff();
        backoff.pause();
        backoff.pause();
        assertThat(backoff.retries()).isEqualTo(2);
    }

    @Test
    void spinPhaseDoesNotParkOrRequestJitter() {
        var delays = new ArrayList<Long>();
        var backoff = new AdaptiveBackoff(delays::add, _ -> {
            throw new AssertionError("Spin phase must not request jitter");
        });
        for (int i = 0; i < 8; i++) backoff.pause();
        assertThat(delays).isEmpty();
        assertThat(backoff.retries()).isEqualTo(8);
    }

    @Test
    void exponentialPhasesUseTheirCapsAndAddJitter() {
        var delays = new ArrayList<Long>();
        var backoff = new AdaptiveBackoff(delays::add, bound -> {
            assertThat(bound).isEqualTo(5_001);
            return 5_000;
        });
        for (int i = 0; i < 24; i++) backoff.pause();
        assertThat(delays).containsExactly(
                6_000L, 7_000L, 9_000L, 13_000L, 21_000L, 21_000L,
                7_000L, 9_000L, 13_000L, 21_000L, 37_000L, 69_000L,
                133_000L, 261_000L, 261_000L, 261_000L);
    }

    @Test
    void fullRetryBudgetRequestsLessThan130MillisecondsOfParking() {
        var delays = new ArrayList<Long>();
        var backoff = new AdaptiveBackoff(delays::add, bound -> bound - 1);
        for (int i = 0; i < 512; i++) backoff.pause();
        assertThat(backoff.retries()).isEqualTo(512);
        assertThat(delays).hasSize(504);
        assertThat(delays.stream().mapToLong(Long::longValue).sum()).isLessThan(130_000_000L);
    }
}
