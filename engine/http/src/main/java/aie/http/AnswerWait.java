package aie.http;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a message call without streaming waits for its answer to end. The contract says 120 seconds, and that is
 * what the engine uses: the role registers no other. Tests register a shorter one, so that an answer that does not end
 * in time costs them less than two minutes.
 */
public record AnswerWait(Duration limit) {

    /** The contract's wait. */
    public static final AnswerWait CONTRACT = new AnswerWait(Duration.ofSeconds(120));

    public AnswerWait {
        Objects.requireNonNull(limit, "A wait needs its limit");
        if (limit.isNegative() || limit.isZero()) {
            throw new IllegalArgumentException("A wait is longer than zero, not " + limit);
        }
    }
}
