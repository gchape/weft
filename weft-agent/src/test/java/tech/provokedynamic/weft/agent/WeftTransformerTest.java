package tech.provokedynamic.weft.agent;

import org.junit.jupiter.api.Test;
import tech.provokedynamic.weft.TransactionHints;
import tech.provokedynamic.weft.atomic.Weft;
import tech.provokedynamic.weft.atomic.TVar;
import tech.provokedynamic.weft.fixture.TransactionFixture;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WeftTransformerTest {

    private static final String NAME = TransactionFixture.class.getName().replace('.', '/');
    private final WeftTransformer transformer = new WeftTransformer(Set.of("tech/provokedynamic/weft/fixture/"));

    @Test
    void injectsExactCountsAndPreservesDebugInformation() throws Exception {
        byte[] transformed = transform();
        assertThat(metrics(transformed, "simple")).containsExactly(new TransactionHints(1, 1));
        assertThat(ClassFile.of().verify(transformed)).isEmpty();
        assertThat(ClassFile.of().parse(transformed).methods().stream()
                .flatMap(method -> method.code().stream())
                .flatMap(code -> code.elementStream())
                .anyMatch(LineNumber.class::isInstance)).isTrue();

        Class<?> fixture = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(TransactionFixture.class.getName(), transformed, 0, transformed.length);
            }
        }.define();
        var variable = new TVar<>(0);
        fixture.getMethod("simple", TVar.class).invoke(null, variable);
        Weft.atomic(() -> assertThat(Weft.read(variable)).isEqualTo(1));
    }

    @Test
    void resolvesNestedBodiesAndInstanceMethodReferences() throws IOException {
        byte[] transformed = transform();
        assertThat(metrics(transformed, "nested")).containsExactly(new TransactionHints(1, 1));
        assertThat(metrics(transformed, "instanceReference")).containsExactly(new TransactionHints(0, 2));
    }

    @Test
    void doesNotGuessMetricsFromUnrelatedOrExternalRunnableBodies() throws IOException {
        byte[] transformed = transform();
        assertThat(metrics(transformed, "unrelatedLambda")).isEmpty();
        assertThat(metrics(transformed, "externalReference")).isEmpty();
        assertThat(metrics(transformed, "explicitMetrics")).containsExactly(new TransactionHints(1, 9));
    }

    @Test
    void alreadyInstrumentedClassNeedsNoSecondTransformation() throws IOException {
        assertThat(transformer.transform(null, NAME, null, null, transform())).isNull();
    }

    @Test
    void ignoresClassesOutsideScopeAndAgentImplementation() throws IOException {
        byte[] original = original();
        assertThat(transformer.transform(null, "other/Example", null, null, original)).isNull();
        assertThat(transformer.transform(null, null, null, null, original)).isNull();
        var broad = new WeftTransformer(Set.of("tech/"));
        assertThat(broad.transform(null, "tech/provokedynamic/weft/agent/Internal", null, null, original)).isNull();
    }

    private byte[] transform() throws IOException {
        byte[] result = transformer.transform(null, NAME, null, null, original());
        assertThat(result).isNotNull();
        return result;
    }

    private static byte[] original() throws IOException {
        try (var stream = TransactionFixture.class.getResourceAsStream("/" + NAME + ".class")) {
            if (stream == null) throw new IOException("Missing fixture class");
            return stream.readAllBytes();
        }
    }

    private static List<TransactionHints> metrics(byte[] bytes, String methodName) {
        var result = new ArrayList<TransactionHints>();
        var method = ClassFile.of().parse(bytes).methods().stream()
                .filter(m -> m.methodName().equalsString(methodName)).findFirst().orElseThrow();
        var instructions = method.code().orElseThrow().elementStream()
                .filter(Instruction.class::isInstance).toList();
        for (int i = 2; i < instructions.size(); i++) {
            if (instructions.get(i) instanceof InvokeInstruction call
                    && call.owner().asInternalName().equals("tech/provokedynamic/weft/TransactionHints")
                    && call.name().equalsString("<init>")) {
                int reads = (Integer) ((ConstantInstruction) instructions.get(i - 2)).constantValue();
                int writes = (Integer) ((ConstantInstruction) instructions.get(i - 1)).constantValue();
                result.add(new TransactionHints(reads, writes));
            }
        }
        return result;
    }
}
