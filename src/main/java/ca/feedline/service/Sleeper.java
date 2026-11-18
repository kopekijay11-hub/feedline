package ca.feedline.service;

/**
 * Interface for sleep operations, allowing for injectable delays in tests.
 *
 * <p>This interface enables tests to use a mock or a no-op implementation
 * instead of actually sleeping, while production code uses the real
 * Thread.sleep implementation.
 */
public interface Sleeper {

    /**
     * Sleep for the specified duration.
     *
     * @param millis the duration to sleep in milliseconds
     * @throws InterruptedException if the sleep is interrupted
     */
    void sleep(long millis) throws InterruptedException;

    /**
     * No-op sleeper for use in tests where actual delays should be avoided.
     */
    Sleeper NO_OP = millis -> {};

    /**
     * Real sleeper that uses Thread.sleep.
     */
    Sleeper REAL = Thread::sleep;
}
