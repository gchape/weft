package tech.provokedynamic.weft.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import tech.provokedynamic.weft.fixture.TransactionFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeftAgentTest {

    @Test
    void normalizesAndDeduplicatesScanPrefixes() {
        assertThat(WeftAgent.parsePrefixes(" com.example.,com/example/, other.app. "))
                .containsExactlyInAnyOrder("com/example/", "other/app/");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ",", " , , "})
    void rejectsEmptyScanScope(String args) {
        assertThatThrownBy(() -> WeftAgent.parsePrefixes(args))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scan prefix");
    }

    @Test
    void respectsPackageBoundaryWhenPrefixEndsWithSlash() {
        var prefixes = Set.of("com/example/");
        assertThat(WeftAgent.isInScope("com/example/App", prefixes)).isTrue();
        assertThat(WeftAgent.isInScope("com/examples/App", prefixes)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tech/provokedynamic/weft/agent/WeftAgent", "tech/provokedynamic/weft/shaded/bytebuddy/Agent"})
    void excludesAgentAndShadedClassesFromBroadScopes(String name) {
        assertThat(WeftAgent.isInScope(name, Set.of("tech/"))).isFalse();
    }

    @Test
    void linkageFailureFallsBackToIndividualRetransformation() {
        var calls = new ArrayList<List<Class<?>>>();
        var first = TransactionFixture.class;
        var second = TransactionFixture.ExternalBody.class;
        var instrumentation = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Instrumentation.class}, (_, method, arguments) -> switch (method.getName()) {
                    case "isRetransformClassesSupported", "isModifiableClass" -> true;
                    case "addTransformer" -> null;
                    case "getAllLoadedClasses" -> new Class<?>[]{first, second, WeftAgent.class};
                    case "retransformClasses" -> {
                        var targets = (Class<?>[]) arguments[0];
                        calls.add(List.of(targets));
                        if (targets.length > 1) throw new UnsupportedClassVersionError("batch rejected");
                        if (targets[0] == first) throw new NoClassDefFoundError("unavailable dependency");
                        yield null;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        WeftAgent.premain("tech/", instrumentation);
        assertThat(calls).containsExactly(List.of(first, second), List.of(first), List.of(second));
    }
}
