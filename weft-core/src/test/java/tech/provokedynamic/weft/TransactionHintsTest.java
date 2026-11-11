package tech.provokedynamic.weft;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionHintsTest {

    @Test
    void derivesTotalOps() {
        var m = new TransactionHints(3, 2);
        assertThat(m.totalOps()).isEqualTo(5);
    }

    @Test
    void threeArgConstructorPreservesTotalOps() {
        var m = new TransactionHints(3, 2, 99);
        assertThat(m.totalOps()).isEqualTo(99);
    }

    @Test
    void zeroOps() {
        var m = new TransactionHints(0, 0);
        assertThat(m.totalOps()).isZero();
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
