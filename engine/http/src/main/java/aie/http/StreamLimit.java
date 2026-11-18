package aie.http;

/**
 * How many event streams an instance keeps open at once (point 40). A stream reads the log every 200 milliseconds while
 * nothing new comes, on the instance's pool of 10 connections, so without a limit enough streams slow every request of
 * the instance. The api role reads it from engine.streams.max; without one, and in tests that register none, it is
 * {@link #DEFAULT}.
 */
public record StreamLimit(int max) {

    /** Four times the answers an instance writes at once (Answers.MAX_RUNNING): an answer often has a few readers. */
    public static final StreamLimit DEFAULT = new StreamLimit(256);

    public StreamLimit {
        if (max < 1) {
            throw new IllegalArgumentException("At least one stream must be able to open, not " + max);
        }
    }
}
