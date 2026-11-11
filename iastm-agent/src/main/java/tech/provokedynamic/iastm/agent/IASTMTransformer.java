package tech.provokedynamic.iastm.agent;

import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

/**
 * Injects static workload hints into directly associated local transaction bodies.
 *
 * <p>Uses the Class-File API to count direct read/write call sites and resolve nested
 * transaction bodies in the same class. Arbitrary helper calls and dynamically supplied
 * Runnable values are not analyzed. Unsupported sites retain the original overload.
 * Debug information is preserved, and explicit metrics are never overwritten.
 */
public final class IASTMTransformer implements ClassFileTransformer {

    private static final ClassDesc IASTM_CLASS = ClassDesc.of("tech.provokedynamic.iastm.atomic.IASTM");
    private static final ClassDesc METRICS_CLASS = ClassDesc.of("tech.provokedynamic.iastm.TxMetrics");
    private static final ClassDesc RUNNABLE_CLASS = ClassDesc.of("java.lang.Runnable");

    private static final MethodTypeDesc START_METHOD = MethodTypeDesc.of(ConstantDescs.CD_void, RUNNABLE_CLASS);
    private static final MethodTypeDesc START_WITH_METRICS_METHOD = MethodTypeDesc.of(ConstantDescs.CD_void, RUNNABLE_CLASS, METRICS_CLASS);
    private static final MethodTypeDesc METRICS_CONSTRUCTOR = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_int);

    private static final ClassFile CLASS_FILE = ClassFile.of();
    private static final System.Logger LOG = System.getLogger(IASTMTransformer.class.getName());

    private static final String AGENT_PREFIX = "tech/provokedynamic/iastm/agent/";

    private final Set<String> scanPrefixes;

    IASTMTransformer(Set<String> scanPrefixes) {
        this.scanPrefixes = Set.copyOf(scanPrefixes);
    }

    /**
     * Returns {@code true} if a plain {@code IASTM.start(Runnable)} has a directly preceding lambda
     * (i.element. this class is worth rewriting).
     */
    private static boolean collectRawCounts(ClassModel classModel,
                                            Map<String, Counts> rawCounts,
                                            Map<String, List<String>> lambdaChildren) {
        boolean hasPlainStart = false;
        var owner = classModel.thisClass().asSymbol();

        for (var method : classModel.methods()) {
            if (method.code().isEmpty()) continue;

            var methodKey = methodKey(method);
            int reads = 0, writes = 0;
            String pendingLambda = null;

            for (var element : method.code().get()) {
                var argumentLambda = pendingLambda;
                if (element instanceof Instruction) pendingLambda = null;
                switch (element) {
                    case InvokeDynamicInstruction dynamicCall -> {
                        var lambdaMethod = lambdaKey(dynamicCall, owner);
                        if (lambdaMethod != null) {
                            pendingLambda = lambdaMethod;
                            rawCounts.putIfAbsent(lambdaMethod, Counts.ZERO);
                        }
                    }
                    case InvokeInstruction invocation
                            when invocation.opcode() == Opcode.INVOKESTATIC
                            && invocation.owner().asSymbol().equals(IASTM_CLASS) -> {
                        switch (invocation.name().stringValue()) {
                            case "read" -> reads++;
                            case "write" -> writes++;
                            case "start" -> {
                                if (invocation.typeSymbol().equals(START_METHOD)) {
                                    if (argumentLambda != null) {
                                        hasPlainStart = true;
                                        lambdaChildren.computeIfAbsent(methodKey, _ -> new ArrayList<>())
                                                .add(argumentLambda);
                                    }
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            rawCounts.put(methodKey, new Counts(reads, writes));
        }
        return hasPlainStart;
    }

    /** Resolves each method's direct counts and reachable nested transaction bodies. */
    private static Map<String, Counts> resolveCounts(Map<String, Counts> rawCounts,
                                                     Map<String, List<String>> lambdaChildren) {
        var resolved = new HashMap<String, Counts>(rawCounts.size() * 2);
        var visiting = new HashSet<String>();
        rawCounts.keySet().forEach(m -> resolved.put(m, accumulate(m, rawCounts, lambdaChildren, visiting)));
        return resolved;
    }

    /** Accumulates closure counts; recursive references contribute no additional cycle count. */
    private static Counts accumulate(String method,
                                     Map<String, Counts> raw,
                                     Map<String, List<String>> children,
                                     Set<String> visiting) {
        if (!visiting.add(method)) return Counts.ZERO;
        var total = raw.getOrDefault(method, Counts.ZERO);
        for (var child : children.getOrDefault(method, List.of()))
            total = total.add(accumulate(child, raw, children, visiting));
        visiting.remove(method);
        return total;
    }

    private static String methodKey(MethodModel method) {
        return method.methodName().stringValue() + method.methodType().stringValue();
    }

    /**
     * Returns the local implementation key for a directly supplied Runnable lambda or method reference.
     */
    private static String lambdaKey(InvokeDynamicInstruction dynamicCall, ClassDesc owner) {
        if (!dynamicCall.typeSymbol().returnType().equals(RUNNABLE_CLASS)) return null;
        return dynamicCall.bootstrapArgs().stream()
                .filter(DirectMethodHandleDesc.class::isInstance)
                .map(a -> (DirectMethodHandleDesc) a)
                .filter(methodHandle -> methodHandle.owner().equals(owner))
                .map(methodHandle -> methodHandle.methodName() + methodHandle.lookupDescriptor())
                .findFirst()
                .orElse(null);
    }

    /**
     * Rewrites eligible transaction calls in an in-scope application class.
     *
     * @param loader defining loader, or null for the bootstrap loader
     * @param className internal class name, or null if unknown
     * @param classBeingRedefined class being retransformed, or null on initial definition
     * @param protectionDomain protection domain of the class being defined
     * @param classfileBuffer original class bytes
     * @return transformed bytes, or null when no eligible rewrite exists or rewriting fails
     */
    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null
                || className.startsWith(AGENT_PREFIX)
                || IASTMAgent.isOutsideInstrumentationScope(className, scanPrefixes)) return null;
        try {
            var classModel = CLASS_FILE.parse(classfileBuffer);
            var rawCounts = new HashMap<String, Counts>();
            var lambdaChildren = new HashMap<String, List<String>>();
            if (!collectRawCounts(classModel, rawCounts, lambdaChildren)) return null;

            var resolved = resolveCounts(rawCounts, lambdaChildren);
            return CLASS_FILE.transformClass(classModel,
                    ClassTransform.transformingMethods(
                            MethodTransform.transformingCode(
                                    CodeTransform.ofStateful(() -> new MetricsInjectingTransform(resolved, classModel.thisClass().asSymbol())))));
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not instrument " + className, failure);
            return null;
        }
    }

    private record Counts(int reads, int writes) {
        static final Counts ZERO = new Counts(0, 0);

        Counts add(Counts other) {
            return new Counts(reads + other.reads, writes + other.writes);
        }
    }

    /**
     * Rewrites each {@code IASTM.start(Runnable)} call into
     * {@code IASTM.start(Runnable, TxMetrics)}, injecting a pre-built
     * {@code new TxMetrics(reads, writes)} from the resolved static counts.
     */
    private static final class MetricsInjectingTransform implements CodeTransform {

        private final Map<String, Counts> resolved;
        private final ClassDesc owner;
        private String pendingLambda;

        MetricsInjectingTransform(Map<String, Counts> resolved, ClassDesc owner) {
            this.resolved = resolved;
            this.owner = owner;
        }

        @Override
        public void accept(CodeBuilder codeBuilder, CodeElement element) {
            var argumentLambda = pendingLambda;
            if (element instanceof Instruction) pendingLambda = null;
            switch (element) {
                case InvokeDynamicInstruction dynamicCall -> {
                    var key = lambdaKey(dynamicCall, owner);
                    if (key != null) pendingLambda = key;
                    codeBuilder.with(element);
                }
                case InvokeInstruction invocation
                        when invocation.opcode() == Opcode.INVOKESTATIC
                        && invocation.owner().asSymbol().equals(IASTM_CLASS)
                        && invocation.name().stringValue().equals("start")
                        && invocation.typeSymbol().equals(START_METHOD)
                        && argumentLambda != null -> {

                    var counts = resolved.getOrDefault(argumentLambda, Counts.ZERO);
                    codeBuilder.new_(METRICS_CLASS)
                            .dup()
                            .loadConstant(counts.reads())
                            .loadConstant(counts.writes())
                            .invokespecial(METRICS_CLASS, "<init>", METRICS_CONSTRUCTOR)
                            .invokestatic(IASTM_CLASS, "start", START_WITH_METRICS_METHOD);
                }
                default -> codeBuilder.with(element);
            }
        }
    }
}
