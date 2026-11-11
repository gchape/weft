package tech.provokedynamic.weft.mvcc;

import org.jspecify.annotations.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.provokedynamic.weft.exception.VersionEvictedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircularVersionHistoryTest {

    @Test
    @DisplayName("scan returns initial value at version 0")
    void scanInitial() {
        CircularVersionHistory<String> h = new CircularVersionHistory<>("init");
        assertThat(h.scan(0L)).isEqualTo("init");
    }

    @Test
    @DisplayName("scan returns latest value at or before readVersion")
    void scanSnapshotSemantics() {
        CircularVersionHistory<Integer> h = new CircularVersionHistory<>(0);
        h.append(10, 1L);
        h.append(20, 3L);
        h.append(30, 5L);

        assertThat(h.scan(0L)).isEqualTo(0);
        assertThat(h.scan(1L)).isEqualTo(10);
        assertThat(h.scan(2L)).isEqualTo(10);
        assertThat(h.scan(3L)).isEqualTo(20);
        assertThat(h.scan(4L)).isEqualTo(20);
        assertThat(h.scan(5L)).isEqualTo(30);
        assertThat(h.scan(Long.MAX_VALUE)).isEqualTo(30);
    }

    @Test
    @DisplayName("scan throws VersionEvictedException after 32 overwrites")
    void scanThrowsWhenEvicted() {
        CircularVersionHistory<Integer> h = new CircularVersionHistory<>(0);
        for (int i = 1; i <= 33; i++) {
            h.append(i, i);
        }
        assertThatThrownBy(() -> h.scan(0L))
                .isInstanceOf(VersionEvictedException.class)
                .hasMessageContaining("0");
    }

    @Test
    void expansionAfterWraparoundDoesNotResurrectEvictedEntries() {
        var history = new CircularVersionHistory<>(0);
        for (int i = 1; i <= 100; i++) history.append(i, i);

        history.expandByFactor(2);

        assertThatThrownBy(() -> history.scan(68)).isInstanceOf(VersionEvictedException.class);
        for (int i = 69; i <= 100; i++) assertThat(history.scan(i)).isEqualTo(i);
        for (int i = 101; i <= 132; i++) history.append(i, i);
        for (int i = 69; i <= 132; i++) assertThat(history.scan(i)).isEqualTo(i);
    }

    @Test
    void shrinkThenExpandRetainsOnlyTheSurvivingWindow() {
        var history = new CircularVersionHistory<>(0);
        history.expandByFactor(4);
        for (int i = 1; i <= 200; i++) history.append(i, i);

        history.shrinkByFactor(0.25f);
        history.expandByFactor(2);

        assertThatThrownBy(() -> history.scan(168)).isInstanceOf(VersionEvictedException.class);
        for (int i = 169; i <= 200; i++) assertThat(history.scan(i)).isEqualTo(i);
        history.append(201, 201);
        assertThat(history.scan(201)).isEqualTo(201);
    }

    @Test
    void expansionOfPartiallyFilledHistoryPreservesInitialAndNullValues() {
        var history = new CircularVersionHistory<@Nullable String>(null);
        history.append("next", 3);
        history.expandByFactor(3);
        assertNull(history.scan(0));
        assertNull(history.scan(2));
        assertThat(history.scan(3)).isEqualTo("next");
    }

    @Test
    void invalidResizeFactorsAreRejected() {
        var history = new CircularVersionHistory<>(0);
        assertThatThrownBy(() -> history.expandByFactor(Float.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.expandByFactor(0.5f)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.expandByFactor(Float.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.shrinkByFactor(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.shrinkByFactor(2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("append wraps slot correctly at boundary")
    void appendWrapsAtBoundary() {
        CircularVersionHistory<Integer> h = new CircularVersionHistory<>(0);
        for (int i = 1; i <= 32; i++) {
            h.append(i * 100, i);
        }
        assertThat(h.scan(32L)).isEqualTo(3200);
        assertThat(h.scan(16L)).isEqualTo(1600);
    }

    @Test
    @DisplayName("VersionEvictedException message includes readVersion")
    void evictedExceptionMessage() {
        assertThatThrownBy(() -> {
            throw new VersionEvictedException(42L);
        }).hasMessageContaining("42");
    }
}
