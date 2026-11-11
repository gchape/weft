package tech.provokedynamic.weft.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeftAgentTest {

    @Test
    void normalizesAndDeduplicatesScanPrefixes() {
        assertThat(WeftAgent.parseInstrumentationPrefixes(" com.example.,com/example/, other.app. "))
                .containsExactlyInAnyOrder("com/example/", "other/app/");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ",", " , , "})
    void rejectsEmptyScanScope(String args) {
        assertThatThrownBy(() -> WeftAgent.parseInstrumentationPrefixes(args))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scan prefix");
    }

    @Test
    void respectsPackageBoundaryWhenPrefixEndsWithSlash() {
        var prefixes = Set.of("com/example/");
        assertThat(WeftAgent.isOutsideInstrumentationScope("com/example/App", prefixes)).isFalse();
        assertThat(WeftAgent.isOutsideInstrumentationScope("com/examples/App", prefixes)).isTrue();
    }
}
