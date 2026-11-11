package tech.provokedynamic.weft.retry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveBackoffTest {

    @Test
    void startsAtZeroAttempts() {
        assertThat(new AdaptiveBackoff().getAttempt()).isZero();
    }

    @Test
    void incrementsAttemptOnEachPark() {
        var b = new AdaptiveBackoff();
        b.park();
        b.park();
        assertThat(b.getAttempt()).isEqualTo(2);
    }

    @Test
    void spinPhaseCompletesQuickly() {
        var b = new AdaptiveBackoff();
        long start = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) b.park();
        assertThat(System.currentTimeMillis() - start).isLessThan(100);
    }

    @Test
    void shortExpPhaseDelays() {
        var b = new AdaptiveBackoff();
        for (int i = 0; i < 8; i++) b.park();
        long start = System.currentTimeMillis();
        b.park();
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(1);
    }
}
