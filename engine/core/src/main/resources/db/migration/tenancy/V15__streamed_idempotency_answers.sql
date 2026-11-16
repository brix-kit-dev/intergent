-- Code point M2-10 (blueprint 6.4): a request that answers as a stream cannot keep its answer as a body, because a retry
-- may come while the answer is still being written. So the key keeps the ID of the message whose event log is the
-- answer, and a retry reads that log from its first event: it follows the answer while it is written, and replays it
-- once it ended. The message belongs to another module, so the ID has no foreign key (A-4).

ALTER TABLE tenancy_idempotency_keys ADD COLUMN streamed_message_id text;

-- A streamed answer is a 200 whose body is the log, so the key keeps no body of its own for it. A key that is not
-- finished has no status, and NULL would pass a check, so coalesce makes it fail.
ALTER TABLE tenancy_idempotency_keys ADD CONSTRAINT tenancy_idempotency_keys_streamed CHECK (
    streamed_message_id IS NULL
    OR coalesce(streamed_message_id <> '' AND response_status = 200 AND response_body IS NULL, false));
