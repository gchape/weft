package tech.provokedynamic.weft.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.provokedynamic.weft.TransactionHints;
import tech.provokedynamic.weft.atomic.Weft;
import tech.provokedynamic.weft.atomic.TVar;
import tech.provokedynamic.weft.fixture.TransactionFixture;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CompoundElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeftTransformerTest {

    private static final String NAME = TransactionFixture.class.getName().replace('.', '/');
    private final WeftTransformer transformer = new WeftTransformer(Set.of("tech/provokedynamic/weft/fixture/"));

    @Test
    void injectsExactCountsAndPreservesDebugInformation() throws Exception {
        byte[] transformed = transform();
        assertThat(hints(transformed, "simple")).containsExactly(new TransactionHints(1, 1));
        assertThat(ClassFile.of().verify(transformed)).isEmpty();
        assertThat(ClassFile.of().parse(transformed).methods().stream()
                .flatMap(method -> method.code().stream())
                .flatMap(CompoundElement::elementStream)
                .anyMatch(LineNumber.class::isInstance)).isTrue();

        Class<?> fixture = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(TransactionFixture.class.getName(), transformed, 0, transformed.length);
            }
        }.define();
        var variable = new TVar<>(0);
        fixture.getMethod("simple", TVar.class).invoke(null, variable);
        Weft.atomic(() -> assertThat(Weft.read(variable)).isEqualTo(1));
        fixture.getMethod("conditionalLambda", TVar.class, boolean.class).invoke(null, variable, true);
        Weft.atomic(() -> assertThat(Weft.read(variable)).isEqualTo(1));
        fixture.getMethod("conditionalLambda", TVar.class, boolean.class).invoke(null, variable, false);
        Weft.atomic(() -> assertThat(Weft.read(variable)).isEqualTo(4));
    }

    @Test
    void resolvesNestedBodiesAndInstanceMethodReferences() throws IOException {
        byte[] transformed = transform();
        assertThat(hints(transformed, "nested")).containsExactly(new TransactionHints(1, 1));
        assertThat(hints(transformed, "instanceReference")).containsExactly(new TransactionHints(0, 2));
    }

    @Test
    void doesNotGuessHintsFromUnrelatedOrExternalRunnableBodies() throws IOException {
        byte[] transformed = transform();
        assertThat(hints(transformed, "unrelatedLambda")).isEmpty();
        assertThat(hints(transformed, "externalReference")).isEmpty();
        assertThat(hints(transformed, "conditionalLambda")).isEmpty();
        assertThat(hints(transformed, "explicitHints")).containsExactly(new TransactionHints(1, 9));
    }

    @Test
    void alreadyInstrumentedClassNeedsNoSecondTransformation() throws IOException {
        assertNull(transformer.transform(null, NAME, null, null, transform()));
    }

    @Test
    void ignoresClassesOutsideScopeAndAgentImplementation() throws IOException {
        byte[] original = original();
        assertNull(transformer.transform(null, "other/Example", null, null, original));
        assertNull(transformer.transform(null, null, null, null, original));
        var broad = new WeftTransformer(Set.of("tech/"));
        assertNull(broad.transform(null, "tech/provokedynamic/weft/agent/Internal", null, null, original));
        assertNull(broad.transform(null, "tech/provokedynamic/weft/shaded/bytebuddy/Agent", null, null, original));
    }

    @Test
    void resolvesHierarchyThroughTheDefiningLoader(@TempDir Path directory) throws Exception {
        var source = directory.resolve("LoaderFixture.java");
        Files.writeString(source, """
                package tech.provokedynamic.weft.fixture;
                import tech.provokedynamic.weft.atomic.TVar;
                import tech.provokedynamic.weft.atomic.Weft;
                public class LoaderFixture {
                    public static String run(boolean left, TVar<Integer> variable) {
                        Weft.atomic(() -> Weft.write(variable, 1));
                        Base value;
                        if (left) value = new Left();
                        else value = new Right();
                        return value.label();
                    }
                    static class Base { String label() { return "base"; } }
                    static class Left extends Base { String label() { return "left"; } }
                    static class Right extends Base { String label() { return "right"; } }
                }
                """);
        var compilerOutput = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, compilerOutput, compilerOutput,
                "-proc:none", "-classpath", System.getProperty("java.class.path"),
                "-d", directory.toString(), source.toString());
        assertThat(status).withFailMessage(compilerOutput.toString()).isZero();
        var transformed = new AtomicBoolean();
        try (var loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("tech.provokedynamic.weft.fixture.LoaderFixture")) {
                    try {
                        byte[] original = Files.readAllBytes(directory.resolve(name.replace('.', '/') + ".class"));
                        byte[] result = transformer.transform(this, name.replace('.', '/'), null, null, original);
                        assertThat(result).isNotNull();
                        transformed.set(true);
                        return defineClass(name, result, 0, result.length);
                    } catch (IOException failure) {
                        throw new ClassNotFoundException(name, failure);
                    }
                }
                return super.findClass(name);
            }
        }) {
            Class<?> fixture = loader.loadClass("tech.provokedynamic.weft.fixture.LoaderFixture");
            var variable = new TVar<>(0);
            assertThat(fixture.getMethod("run", boolean.class, TVar.class).invoke(null, true, variable)).isEqualTo("left");
            assertThat(fixture.getMethod("run", boolean.class, TVar.class).invoke(null, false, variable)).isEqualTo("right");
            assertThat(transformed).isTrue();
            Weft.atomic(() -> assertThat(Weft.read(variable)).isEqualTo(1));
        }
    }

    private byte[] transform() throws IOException {
        byte[] result = transformer.transform(getClass().getClassLoader(), NAME, null, null, original());
        assertThat(result).isNotNull();
        return result;
    }

    private static byte[] original() throws IOException {
        try (var stream = TransactionFixture.class.getResourceAsStream("/" + NAME + ".class")) {
            if (stream == null) throw new IOException("Missing fixture class");
            return stream.readAllBytes();
        }
    }

    private static List<TransactionHints> hints(byte[] bytes, String methodName) {
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
