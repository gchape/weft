package tech.provokedynamic.weft.atomic;

import java.util.ConcurrentModificationException;

/** Internal retry signal, distinct from exceptions thrown by application code. */
final class TransactionConflictException extends ConcurrentModificationException {
}
