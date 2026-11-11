package tech.provokedynamic.iastm.agent;

import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Registers transaction instrumentation for configured application-name prefixes. */
public final class IASTMAgent {

    private static final System.Logger LOG = System.getLogger(IASTMAgent.class.getName());
    private static final String AGENT_INTERNAL_PREFIX = "tech/provokedynamic/iastm/agent/";

    private IASTMAgent() {
    }

    /**
     * Registers the transformer at JVM startup and retransforms eligible loaded classes.
     *
     * @param agentArguments comma-separated binary-name prefixes, with dots or slashes accepted
     * @param instrumentation instrumentation supplied by the JVM
     * @throws IllegalArgumentException if the scan scope is empty
     */
    public static void premain(String agentArguments, Instrumentation instrumentation) {
        registerTransformer(parseInstrumentationPrefixes(agentArguments), instrumentation);
    }

    /**
     * Registers the transformer through the Attach API on an already running JVM.
     *
     * @param agentArguments comma-separated binary-name prefixes
     * @param instrumentation instrumentation supplied by the JVM
     * @throws IllegalArgumentException if the scan scope is empty
     */
    public static void agentmain(String agentArguments, Instrumentation instrumentation) {
        registerTransformer(parseInstrumentationPrefixes(agentArguments), instrumentation);
    }

    /**
     * Installs the agent programmatically using Byte Buddy's instrumentation attachment.
     * Attachment requires support and permission from the running JVM.
     *
     * @param agentArguments comma-separated binary-name prefixes
     * @throws IllegalArgumentException if the scan scope is empty
     * @throws IllegalStateException if instrumentation cannot be obtained
     */
    public static void attach(String agentArguments) {
        var prefixes = parseInstrumentationPrefixes(agentArguments);
        registerTransformer(prefixes, ByteBuddyAgent.install());
    }

    /** Normalizes, deduplicates, and validates prefixes before transformer registration. */
    static Set<String> parseInstrumentationPrefixes(String agentArguments) {
        if (agentArguments == null || agentArguments.isBlank()) {
            throw new IllegalArgumentException("IASTM agent requires at least one scan prefix, e.g. -javaagent:iastm-agent.jar=com/example/myapp/");
        }
        var prefixes = Arrays.stream(agentArguments.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replace('.', '/'))
                .collect(Collectors.toUnmodifiableSet());
        if (prefixes.isEmpty()) {
            throw new IllegalArgumentException("IASTM agent requires at least one non-empty scan prefix");
        }
        return prefixes;
    }

    static boolean isOutsideInstrumentationScope(String internalName, Set<String> prefixes) {
        return prefixes.stream().noneMatch(internalName::startsWith);
    }

    private static void registerTransformer(Set<String> prefixes, Instrumentation instrumentation) {
        boolean canRetransform = instrumentation.isRetransformClassesSupported();
        instrumentation.addTransformer(new IASTMTransformer(prefixes), canRetransform);
        if (canRetransform) retransformLoadedClasses(instrumentation, prefixes);
    }

    private static void retransformLoadedClasses(Instrumentation instrumentation, Set<String> prefixes) {
        var targets = Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(loadedClass -> {
                    var name = loadedClass.getName().replace('.', '/');
                    return !name.startsWith(AGENT_INTERNAL_PREFIX)
                            && !isOutsideInstrumentationScope(name, prefixes)
                            && instrumentation.isModifiableClass(loadedClass);
                })
                .toArray(Class<?>[]::new);
        if (targets.length == 0) {
            return;
        }
        try {
            instrumentation.retransformClasses(targets);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Could not retransform loaded IASTM application classes", e);
        }
    }
}
