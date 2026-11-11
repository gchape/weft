package tech.provokedynamic.weft.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tech.provokedynamic.weft.fixture.AttachSmoke;

import java.io.File;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the shaded artifact after Maven packaging, through a separate JVM. */
@Timeout(45)
class AgentAttachIT {

    @Test
    void packagedAgentCanAttachAndRetransformLoadedClasses() throws Exception {
        var jar = Path.of(System.getProperty("weft.agent.jar")).toAbsolutePath();
        var classes = Path.of("target/classes").toAbsolutePath().normalize();
        var entries = new ArrayList<String>();
        entries.add(jar.toString());
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!Path.of(entry).toAbsolutePath().normalize().equals(classes) && !entry.contains("byte-buddy-agent")) {
                entries.add(entry);
            }
        }
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, "-XX:+EnableDynamicAgentLoading", "-cp",
                String.join(File.pathSeparator, entries), AttachSmoke.class.getName()).redirectErrorStream(true).start();
        try {
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly().waitFor();
            String output;
            try (var input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(completed).withFailMessage(output).isTrue();
            assertThat(process.exitValue()).withFailMessage(output).isZero();
            assertThat(output).contains("ATTACH_OK").doesNotContain("No SLF4J providers")
                    .doesNotContain("A Java agent has been loaded dynamically");
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
