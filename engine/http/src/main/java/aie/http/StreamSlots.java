package aie.http;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The event streams this instance has open, at most {@link StreamLimit#max()} (point 40). A stream takes a slot before
 * it starts and gives it back when it ends, whichever way it ends. When none is free, the stream does not start: 429
 * with Retry-After, as when the instance writes as many answers as it may.
 */
final class StreamSlots {

    /** As for a full instance: long enough for some streams to end, short enough for a caller to wait. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private final Semaphore slots;

    StreamSlots(StreamLimit limit) {
        this.slots = new Semaphore(limit.max());
    }

    /**
     * Runs one stream in a slot, from before it asks, reads or sends anything until it has ended, and gives the slot back
     * however it ends: its ending sent, its caller gone, its time up, or a failure. With no slot free it does not run,
     * and the caller gets 429.
     */
    <T> T stream(Supplier<T> stream) {
        if (!slots.tryAcquire()) {
            ErrorResponseException full = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS,
                    ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                            "This instance has as many event streams open as it may, so try again after Retry-After"),
                    null);
            full.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(RETRY_AFTER.toSeconds()));
            throw full;
        }
        try {
            return stream.get();
        } finally {
            slots.release();
        }
    }
}
