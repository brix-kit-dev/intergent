package aie.core.tenancy.api;

/**
 * The Idempotency-Key of blueprint 6.2: a retry with the same key must not do the work twice. A key belongs to one
 * tenant and one application, and lives for 24 hours.
 *
 * <p>The answer is stored in a transaction of its own, because a transaction does not cross modules. A process that
 * dies between doing the work and storing the answer leaves a key that a retry can take over, so that retry runs the
 * work again.
 */
public interface IdempotencyKeys {

    /** Tries to take the key for a request. The request may run only if this returns {@link Claim.Acquired}. */
    Claim claim(Key key, String fingerprint);

    /** Stores the answer retries will get. The first answer stands, so a request that was overtaken cannot replace it. */
    void finish(Key key, int status, String body);

    /**
     * Stores that the request answers as a stream: the event log of this message, which retries get instead of a body.
     * It is called once the answer exists, before it streams, so a retry while the answer is written follows it, and one
     * after it ended replays it. The first answer stands, as with {@link #finish}.
     *
     * @throws IllegalArgumentException for a message ID that is null or empty
     */
    void finishStreamed(Key key, String messageId);

    /**
     * Lets go of a key whose request did nothing, such as one turned away with 429, so that a retry with it runs the
     * request as if the key were new. Only a key still held is let go: a stored answer stands.
     */
    void release(Key key);

    /** A key is only unique within its tenant and application. */
    record Key(String tenantId, String applicationId, String value) {
    }

    /** What became of a claim. */
    sealed interface Claim {

        /** The key is this request's. Run it, then call {@link IdempotencyKeys#finish}. */
        record Acquired() implements Claim {
        }

        /** Another request holds the key and has not finished: {@code idempotency_key_in_use}. */
        record InUse() implements Claim {
        }

        /** The key was used for a different request: {@code idempotency_key_reused}. */
        record Reused() implements Claim {
        }

        /** The same request already ran. This is its answer, to return as it is. */
        record Finished(int status, String body) implements Claim {
        }

        /** The same request already ran and answers as the stream of this message, to send again from its log. */
        record Streamed(String messageId) implements Claim {
        }
    }
}
