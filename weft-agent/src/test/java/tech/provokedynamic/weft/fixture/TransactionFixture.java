package tech.provokedynamic.weft.fixture;

import tech.provokedynamic.weft.TransactionHints;
import tech.provokedynamic.weft.atomic.Weft;
import tech.provokedynamic.weft.atomic.TVar;

public class TransactionFixture {

    private final TVar<Integer> variable = new TVar<>(0);

    public static void simple(TVar<Integer> variable) {
        Weft.atomic(() -> Weft.write(variable, Weft.read(variable) + 1));
    }

    public static void nested(TVar<Integer> variable) {
        Weft.atomic(() -> {
            Weft.read(variable);
            Weft.atomic(() -> Weft.write(variable, 2));
        });
    }

    public void instanceReference() {
        Weft.atomic(this::writeBody);
    }

    private void writeBody() {
        Weft.write(variable, 1);
        Weft.write(variable, 2);
    }

    public static void externalReference() {
        Weft.atomic(ExternalBody::writeBody);
    }

    public static void unrelatedLambda(TVar<Integer> variable, Runnable body) {
        Runnable unused = () -> Weft.write(variable, 1);
        Weft.atomic(body);
    }

    public static void explicitMetrics(TVar<Integer> variable) {
        Weft.atomic(() -> Weft.write(variable, 3), new TransactionHints(1, 9));
    }

    public static class ExternalBody {
        public static void writeBody() {
        }
    }
}
