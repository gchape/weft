package tech.provokedynamic.iastm.fixture;

import tech.provokedynamic.iastm.TxMetrics;
import tech.provokedynamic.iastm.atomic.IASTM;
import tech.provokedynamic.iastm.atomic.TVar;

public class TransactionFixture {

    private final TVar<Integer> variable = new TVar<>(0);

    public static void simple(TVar<Integer> variable) {
        IASTM.start(() -> IASTM.write(variable, IASTM.read(variable) + 1));
    }

    public static void nested(TVar<Integer> variable) {
        IASTM.start(() -> {
            IASTM.read(variable);
            IASTM.start(() -> IASTM.write(variable, 2));
        });
    }

    public void instanceReference() {
        IASTM.start(this::writeBody);
    }

    private void writeBody() {
        IASTM.write(variable, 1);
        IASTM.write(variable, 2);
    }

    public static void externalReference() {
        IASTM.start(ExternalBody::writeBody);
    }

    public static void unrelatedLambda(TVar<Integer> variable, Runnable body) {
        Runnable unused = () -> IASTM.write(variable, 1);
        IASTM.start(body);
    }

    public static void explicitMetrics(TVar<Integer> variable) {
        IASTM.start(() -> IASTM.write(variable, 3), new TxMetrics(1, 9));
    }

    public static class ExternalBody {
        public static void writeBody() {
        }
    }
}
