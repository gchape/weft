package tech.provokedynamic.weft.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Injects static workload hints into directly associated local transaction bodies.
 *
 * <p>Uses the Class-File API to count direct read/write call sites and resolve nested
 * transaction bodies in the same class. Arbitrary helper calls and dynamically supplied
 * Runnable values are not analyzed. Unsupported sites retain the original overload.
 * Debug information is preserved, and explicit hints are never overwritten.
 */
public final class WeftTransformer implements ClassFileTransformer {

    private static final ClassDesc WEFT_CLASS = ClassDesc.of("tech.provokedynamic.weft.atomic.Weft");
    private static final ClassDesc HINTS_CLASS = ClassDesc.of("tech.provokedynamic.weft.TransactionHints");
    private static final ClassDesc RUNNABLE_CLASS = ClassDesc.of("java.lang.Runnable");

    private static final MethodTypeDesc ATOMIC_METHOD = MethodTypeDesc.of(ConstantDescs.CD_void, RUNNABLE_CLASS);
    private static final MethodTypeDesc ATOMIC_WITH_HINTS_METHOD = MethodTypeDesc.of(ConstantDescs.CD_void, RUNNABLE_CLASS, HINTS_CLASS);
    private static final MethodTypeDesc HINTS_CONSTRUCTOR = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_int);

    private static final ClassFile CLASS_FILE = ClassFile.of();
    private static final System.Logger LOG = System.getLogger(WeftTransformer.class.getName());

    private final Set<String> scanPrefixes;

    WeftTransformer(Set<String> scanPrefixes) {
        this.scanPrefixes = Set.copyOf(scanPrefixes);
    }

    /**
     * Collects direct read/write counts and edges to directly associated local atomic bodies.
     *
     * @param classModel application class being analyzed
     * @param directCounts output map from method keys to their direct operation counts
     * @param nestedBodies output map from method keys to nested atomic body keys
     * @return whether at least one plain atomic call has an eligible body
     */
    private static boolean collectDirectCounts(ClassModel classModel,
                                            Map<String, Counts> directCounts,
                                            Map<String, List<String>> nestedBodies) {
        boolean hasEligibleAtomicCall = false;
        var owner = classModel.thisClass().asSymbol();

        for (var method : classModel.methods()) {
            if (method.code().isEmpty()) continue;

            var methodKey = methodKey(method);
            int reads = 0, writes = 0;
            String pendingBodyKey = null;

            for (var element : method.code().get()) {
                var argumentBodyKey = pendingBodyKey;
                if (element instanceof Instruction || element instanceof LabelTarget) pendingBodyKey = null;
                switch (element) {
                    case InvokeDynamicInstruction dynamicCall -> {
                        var bodyKey = localBodyKey(dynamicCall, owner);
                        if (bodyKey != null) {
                            pendingBodyKey = bodyKey;
                            directCounts.putIfAbsent(bodyKey, Counts.ZERO);
                        }
                    }
                    case InvokeInstruction invocation
                            when invocation.opcode() == Opcode.INVOKESTATIC
                            && invocation.owner().asSymbol().equals(WEFT_CLASS) -> {
                        switch (invocation.name().stringValue()) {
                            case "read" -> reads++;
                            case "write" -> writes++;
                            case "atomic" -> {
                                if (invocation.typeSymbol().equals(ATOMIC_METHOD)) {
                                    if (argumentBodyKey != null) {
                                        hasEligibleAtomicCall = true;
                                        nestedBodies.computeIfAbsent(methodKey, _ -> new ArrayList<>())
                                                .add(argumentBodyKey);
                                    }
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            directCounts.put(methodKey, new Counts(reads, writes));
        }
        return hasEligibleAtomicCall;
    }

    /** Resolves each method's direct counts and reachable nested transaction bodies. */
    private static Map<String, Counts> resolveCounts(Map<String, Counts> directCounts,
                                                     Map<String, List<String>> nestedBodies) {
        var resolvedCounts = new HashMap<String, Counts>(directCounts.size() * 2);
        var visiting = new HashSet<String>();
        directCounts.keySet().forEach(m -> resolvedCounts.put(m, accumulate(m, directCounts, nestedBodies, visiting)));
        return resolvedCounts;
    }

    /** Accumulates closure counts; recursive references contribute no additional cycle count. */
    private static Counts accumulate(String method,
                                     Map<String, Counts> directCounts,
                                     Map<String, List<String>> nestedBodies,
                                     Set<String> visiting) {
        if (!visiting.add(method)) return Counts.ZERO;
        var total = directCounts.getOrDefault(method, Counts.ZERO);
        for (var child : nestedBodies.getOrDefault(method, List.of()))
            total = total.add(accumulate(child, directCounts, nestedBodies, visiting));
        visiting.remove(method);
        return total;
    }

    private static String methodKey(MethodModel method) {
        return method.methodName().stringValue() + method.methodType().stringValue();
    }

    /**
     * Returns the local implementation key for a directly supplied Runnable lambda or method reference.
     */
    private static String localBodyKey(InvokeDynamicInstruction dynamicCall, ClassDesc owner) {
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
                || !WeftAgent.isInScope(className, scanPrefixes)) return null;
        try {
            var resolver = ClassHierarchyResolver.ofResourceParsing(
                    loader == null ? ClassLoader.getPlatformClassLoader() : loader).cached();
            var classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
            var classModel = CLASS_FILE.parse(classfileBuffer);
            var directCounts = new HashMap<String, Counts>();
            var nestedBodies = new HashMap<String, List<String>>();
            if (!collectDirectCounts(classModel, directCounts, nestedBodies)) return null;

            var resolvedCounts = resolveCounts(directCounts, nestedBodies);
            return classFile.transformClass(classModel,
                    ClassTransform.transformingMethods(
                            MethodTransform.transformingCode(
                                    CodeTransform.ofStateful(() -> new HintsInjectingTransform(resolvedCounts, classModel.thisClass().asSymbol())))));
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
     * Rewrites each {@code Weft.atomic(Runnable)} call into
     * {@code Weft.atomic(Runnable, TransactionHints)}, injecting a pre-built
     * {@code new TransactionHints(reads, writes)} from the resolved static counts.
     */
    private static final class HintsInjectingTransform implements CodeTransform {

        private final Map<String, Counts> resolvedCounts;
        private final ClassDesc owner;
        private String pendingBodyKey;

        HintsInjectingTransform(Map<String, Counts> resolvedCounts, ClassDesc owner) {
            this.resolvedCounts = resolvedCounts;
            this.owner = owner;
        }

        @Override
        public void accept(CodeBuilder codeBuilder, CodeElement element) {
            var argumentBodyKey = pendingBodyKey;
            if (element instanceof Instruction || element instanceof LabelTarget) pendingBodyKey = null;
            switch (element) {
                case InvokeDynamicInstruction dynamicCall -> {
                    var key = localBodyKey(dynamicCall, owner);
                    if (key != null) pendingBodyKey = key;
                    codeBuilder.with(element);
                }
                case InvokeInstruction invocation
                        when invocation.opcode() == Opcode.INVOKESTATIC
                        && invocation.owner().asSymbol().equals(WEFT_CLASS)
                        && invocation.name().stringValue().equals("atomic")
                        && invocation.typeSymbol().equals(ATOMIC_METHOD)
                        && argumentBodyKey != null -> {

                    var counts = resolvedCounts.getOrDefault(argumentBodyKey, Counts.ZERO);
                    codeBuilder.new_(HINTS_CLASS)
                            .dup()
                            .loadConstant(counts.reads())
                            .loadConstant(counts.writes())
                            .invokespecial(HINTS_CLASS, "<init>", HINTS_CONSTRUCTOR)
                            .invokestatic(WEFT_CLASS, "atomic", ATOMIC_WITH_HINTS_METHOD);
                }
                default -> codeBuilder.with(element);
            }
        }
    }
}
