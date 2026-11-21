package ca.feedline.web;

public class OutboxEventNotFoundException extends RuntimeException {
    public OutboxEventNotFoundException(Long id) {
        super("Outbox event not found: " + id);
    }
}
