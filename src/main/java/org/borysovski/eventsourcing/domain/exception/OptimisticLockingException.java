package org.borysovski.eventsourcing.domain.exception;

public class OptimisticLockingException extends RuntimeException {

    public OptimisticLockingException(String aggregateId) {
        super("Concurrent modification detected for aggregate: " + aggregateId);
    }
}
