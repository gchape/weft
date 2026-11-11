package tech.provokedynamic.weft.fixture;

import tech.provokedynamic.weft.agent.WeftAgent;
import tech.provokedynamic.weft.atomic.TVar;
import tech.provokedynamic.weft.atomic.Weft;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Forked check of programmatic attachment through the packaged, relocated agent. */
public final class AttachSmoke {

    private AttachSmoke() {
    }

    /**
     * Attaches after loading the fixture and verifies its transformed write-lock behavior.
     *
     * @param arguments unused command-line arguments
     * @throws ReflectiveOperationException if the packaged agent or fixture cannot be inspected
     */
    static void main(String[] arguments) throws ReflectiveOperationException {
        var source = WeftAgent.class.getProtectionDomain().getCodeSource().getLocation();
        if (!source.getPath().endsWith(".jar")) {
            throw new AssertionError("Agent was not loaded from its packaged JAR");
        }
        Class.forName("tech.provokedynamic.weft.shaded.bytebuddy.agent.ByteBuddyAgent");
        Class.forName(TransactionFixture.class.getName());
        WeftAgent.attach("tech.provokedynamic.weft.fixture.");
        var variable = new TVar<>(0);
        var field = TVar.class.getDeclaredField("lock");
        field.setAccessible(true);
        var lock = (ReentrantReadWriteLock) field.get(variable);
        TransactionFixture.writeHeavy(variable, () -> {
            if (!lock.isWriteLockedByCurrentThread()) throw new AssertionError("Loaded fixture was not retransformed");
        });
        Weft.atomic(() -> {
            if (Weft.read(variable) != 2) throw new AssertionError("Unexpected committed value");
        });
        System.out.println("ATTACH_OK");
    }
}
