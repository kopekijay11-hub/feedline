package ca.feedline.web;

public class OutboxEventNotPoisonedException extends RuntimeException {
    public OutboxEventNotPoisonedException(Long id) {
        super("Outbox event is not currently poisoned: " + id);
    }
}
