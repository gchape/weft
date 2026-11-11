package tech.provokedynamic.weft;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionHintsTest {

    @Test
    void derivesTotalOps() {
        var hints = new TransactionHints(3, 2);
        assertThat(hints.totalOps()).isEqualTo(5);
    }

    @Test
    void threeArgConstructorPreservesTotalOps() {
        var hints = new TransactionHints(3, 2, 99);
        assertThat(hints.totalOps()).isEqualTo(99);
    }

    @Test
    void zeroOps() {
        var hints = new TransactionHints(0, 0);
        assertThat(hints.totalOps()).isZero();
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> new TransactionHints(-1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionHints(1, -2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionHints(1, 2, -3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivedTotalCannotOverflow() {
        assertThatThrownBy(() -> new TransactionHints(Integer.MAX_VALUE, 1)).isInstanceOf(ArithmeticException.class);
    }
}
