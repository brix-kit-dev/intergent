-- Blueprint 6.4: a caller may cancel an answer in progress. The call can come to any instance, while the answer is
-- written on one of them, so the request is kept here, and the database holds what it means: from the request on, the
-- answer takes no more deltas, and message.cancelled is the only ending it can get, whoever writes it. A writer learns
-- of the request when its next write is refused.
--
-- A request and an ending lock the answer in turn, so the one that comes first wins. An answer that has ended takes no
-- request, and one that has a request cannot end otherwise, even when its model completes or fails right after.

CREATE TABLE conversation_cancel_requests (
    tenant_id    text NOT NULL,
    message_id   text NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    -- An answer has one request at most: asking again changes nothing.
    PRIMARY KEY (tenant_id, message_id),
    FOREIGN KEY (tenant_id, message_id) REFERENCES conversation_messages (tenant_id, message_id)
);

ALTER TABLE conversation_cancel_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_cancel_requests FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON conversation_cancel_requests
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

-- Requests are only added. M2 deletes nothing (cleanup is M4).
GRANT SELECT, INSERT ON conversation_cancel_requests TO engine_app;

-- A request locks its answer as an event does, and keeps the lock until the transaction ends, so a request and an
-- ending are committed one after the other. The check comes after the lock, so a request that waited for an ending sees
-- it and is refused. Only an answer in progress takes a request.
--
-- A message out of the writer's reach gets no lock and no check here. Row-level security or the foreign key refuses the
-- row next.
CREATE FUNCTION conversation_check_cancel_request() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = engine, pg_temp
AS $$
DECLARE
    found_role text;
    found_status text;
BEGIN
    SELECT role, status INTO found_role, found_status
    FROM conversation_messages
    WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id
    FOR UPDATE;
    IF FOUND AND found_role <> 'assistant' THEN
        RAISE EXCEPTION 'Only an answer can be cancelled, and message % is not one', NEW.message_id;
    END IF;
    IF FOUND AND found_status <> 'in_progress' THEN
        RAISE EXCEPTION 'Answer % has ended, so it can no longer be cancelled', NEW.message_id;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER check_cancel_request BEFORE INSERT ON conversation_cancel_requests
    FOR EACH ROW EXECUTE FUNCTION conversation_check_cancel_request();

-- As in V4, and now an answer with a cancel request takes message.cancelled alone. The check comes after the lock, so
-- an event that waited for a request's lock sees the request and is refused.
CREATE OR REPLACE FUNCTION conversation_number_event() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = engine, pg_temp
AS $$
DECLARE
    found_role text;
    found_status text;
    so_far text;
BEGIN
    SELECT role, status INTO found_role, found_status
    FROM conversation_messages
    WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id
    FOR UPDATE;
    IF FOUND AND found_role <> 'assistant' THEN
        RAISE EXCEPTION 'Only an answer has events, and message % is not one', NEW.message_id;
    END IF;
    IF FOUND AND found_status <> 'in_progress' THEN
        RAISE EXCEPTION 'Answer % has ended, so it takes no more events', NEW.message_id;
    END IF;
    IF FOUND AND NEW.type <> 'message.cancelled' AND EXISTS (
            SELECT FROM conversation_cancel_requests
            WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id) THEN
        RAISE EXCEPTION 'Answer % is to be cancelled, so it takes message.cancelled alone', NEW.message_id;
    END IF;
    NEW.seq := coalesce((
        SELECT max(seq) FROM conversation_events WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id), 0) + 1;
    IF NEW.type = 'message.cancelled' THEN
        so_far := coalesce((
            SELECT string_agg(text, '' ORDER BY seq) FROM conversation_events
            WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id AND type = 'message.delta'), '');
        IF NEW.text IS NULL THEN
            NEW.text := so_far;
        ELSIF NEW.text <> so_far THEN
            RAISE EXCEPTION 'message.cancelled carries what answer % generated, and that is not it', NEW.message_id;
        END IF;
    END IF;
    RETURN NEW;
END
$$;
