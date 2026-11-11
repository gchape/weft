package tech.provokedynamic.weft.agent;

import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Registers transaction instrumentation for configured application-name prefixes. */
public final class WeftAgent {

    private static final System.Logger LOG = System.getLogger(WeftAgent.class.getName());
    private static final String AGENT_PREFIX = "tech/provokedynamic/weft/agent/";
    private static final String SHADED_PREFIX = "tech/provokedynamic/weft/shaded/";

    private WeftAgent() {
    }

    /**
     * Registers the transformer at JVM startup and retransforms eligible loaded classes.
     *
     * @param agentArguments comma-separated binary-name prefixes, with dots or slashes accepted
     * @param instrumentation instrumentation supplied by the JVM
     * @throws IllegalArgumentException if the scan scope is empty
     */
    public static void premain(String agentArguments, Instrumentation instrumentation) {
        registerTransformer(parsePrefixes(agentArguments), instrumentation);
    }

    /**
     * Registers the transformer through the Attach API on an already running JVM.
     *
     * @param agentArguments comma-separated binary-name prefixes
     * @param instrumentation instrumentation supplied by the JVM
     * @throws IllegalArgumentException if the scan scope is empty
     */
    public static void agentmain(String agentArguments, Instrumentation instrumentation) {
        registerTransformer(parsePrefixes(agentArguments), instrumentation);
    }

    /**
     * Installs the agent programmatically using Byte Buddy's instrumentation attachment.
     * Attachment requires support and permission from the running JVM.
     * Use {@code -XX:+EnableDynamicAgentLoading} to opt into dynamic loading on recent JDKs.
     *
     * @param agentArguments comma-separated binary-name prefixes
     * @throws IllegalArgumentException if the scan scope is empty
     * @throws IllegalStateException if instrumentation cannot be obtained
     */
    public static void attach(String agentArguments) {
        var prefixes = parsePrefixes(agentArguments);
        registerTransformer(prefixes, ByteBuddyAgent.install());
    }

    /** Normalizes, deduplicates, and validates prefixes before transformer registration. */
    static Set<String> parsePrefixes(String agentArguments) {
        if (agentArguments == null || agentArguments.isBlank()) {
            throw new IllegalArgumentException("Weft agent requires at least one scan prefix, e.g. -javaagent:weft-agent.jar=com/example/myapp/");
        }
        var prefixes = Arrays.stream(agentArguments.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replace('.', '/'))
                .collect(Collectors.toUnmodifiableSet());
        if (prefixes.isEmpty()) {
            throw new IllegalArgumentException("Weft agent requires at least one non-empty scan prefix");
        }
        return prefixes;
    }

    static boolean isInScope(String internalName, Set<String> prefixes) {
        return !internalName.startsWith(AGENT_PREFIX)
                && !internalName.startsWith(SHADED_PREFIX)
                && prefixes.stream().anyMatch(internalName::startsWith);
    }

    private static void registerTransformer(Set<String> prefixes, Instrumentation instrumentation) {
        boolean canRetransform = instrumentation.isRetransformClassesSupported();
        instrumentation.addTransformer(new WeftTransformer(prefixes), canRetransform);
        if (canRetransform) retransformLoadedClasses(instrumentation, prefixes);
    }

    /** Retransforms a batch, falling back to individual classes after a recoverable failure. */
    private static void retransformLoadedClasses(Instrumentation instrumentation, Set<String> prefixes) {
        var targets = Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(loadedClass -> {
                    var name = loadedClass.getName().replace('.', '/');
                    return isInScope(name, prefixes)
                            && instrumentation.isModifiableClass(loadedClass);
                })
                .toArray(Class<?>[]::new);
        if (targets.length == 0) {
            return;
        }
        try {
            instrumentation.retransformClasses(targets);
        } catch (Exception | LinkageError failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not retransform the application class batch; retrying individually", failure);
            for (Class<?> target : targets) {
                try {
                    instrumentation.retransformClasses(target);
                } catch (Exception | LinkageError skipped) {
                    LOG.log(System.Logger.Level.WARNING, "Could not retransform " + target.getName(), skipped);
                }
            }
        }
    }
}
