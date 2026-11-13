-- Blueprint 6.4: every answer ends with exactly one ending event. message.completed carries the usage,
-- message.cancelled what was generated and the usage, and error the code. The same transaction writes the answer's
-- result into its message, so the message stays whole once its event log is cleaned up (M4). After the ending nothing
-- more is written: no event, and no change of the message.
--
-- The database makes sure an answer ends at most once. That it ends at least once is the writer's task, or the reader's
-- when the writer's lease runs out (M2-11): a database cannot notice a writer that has gone quiet.

ALTER TABLE conversation_events
    ADD COLUMN input_tokens  bigint,
    ADD COLUMN output_tokens bigint,
    ADD COLUMN code          text;

ALTER TABLE conversation_events DROP CONSTRAINT conversation_events_type_check;
ALTER TABLE conversation_events ADD CONSTRAINT conversation_events_type_check
    CHECK (type IN ('message.started', 'message.delta', 'message.completed', 'message.cancelled', 'error'));

-- A delta has text, message.cancelled has what was generated (which may be nothing), and the others have none.
ALTER TABLE conversation_events DROP CONSTRAINT conversation_events_text;
ALTER TABLE conversation_events ADD CONSTRAINT conversation_events_text CHECK (CASE type
    WHEN 'message.delta' THEN coalesce(text, '') <> ''
    WHEN 'message.cancelled' THEN text IS NOT NULL
    ELSE text IS NULL END);

-- message.completed and message.cancelled carry the usage, so the answer is metered as far as it went; the others none.
ALTER TABLE conversation_events ADD CONSTRAINT conversation_events_usage CHECK (CASE
    WHEN type IN ('message.completed', 'message.cancelled') THEN coalesce(input_tokens >= 0 AND output_tokens >= 0, false)
    ELSE input_tokens IS NULL AND output_tokens IS NULL END);

-- error carries why the answer failed after it started (blueprint 6.10); the others carry no code.
ALTER TABLE conversation_events ADD CONSTRAINT conversation_events_code CHECK (CASE type
    WHEN 'error' THEN coalesce(code IN ('model_failed', 'stream_interrupted'), false)
    ELSE code IS NULL END);

-- An answer in progress is empty. An ended one has what its ending event says: usage when it completed or was
-- cancelled, a code when it failed.
ALTER TABLE conversation_messages DROP CONSTRAINT conversation_messages_answer;
ALTER TABLE conversation_messages ADD CONSTRAINT conversation_messages_answer CHECK (role <> 'assistant' OR CASE status
    WHEN 'in_progress' THEN text = '' AND input_tokens IS NULL AND output_tokens IS NULL AND code IS NULL
    WHEN 'completed' THEN coalesce(input_tokens >= 0 AND output_tokens >= 0, false) AND code IS NULL
    WHEN 'cancelled' THEN coalesce(input_tokens >= 0 AND output_tokens >= 0, false) AND code IS NULL
    WHEN 'failed' THEN input_tokens IS NULL AND output_tokens IS NULL
        AND coalesce(code IN ('model_failed', 'stream_interrupted'), false)
    ELSE false END);

-- Only an ending event ends an answer, so an answer cannot be stored as ended to begin with.
CREATE FUNCTION conversation_refuse_ended_answer() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.role = 'assistant' AND NEW.status <> 'in_progress' THEN
        RAISE EXCEPTION 'An answer starts in progress and only its ending event ends it, so message % cannot start %',
            NEW.message_id, NEW.status;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER answers_start_in_progress BEFORE INSERT ON conversation_messages
    FOR EACH ROW EXECUTE FUNCTION conversation_refuse_ended_answer();

-- conversation_end_answer below ends an answer from inside its own trigger, one level down. A change from anywhere else
-- is still refused.
CREATE OR REPLACE FUNCTION conversation_refuse_message_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'A message changes only through its events, so message % cannot be changed', OLD.message_id;
    END IF;
    RETURN NEW;
END
$$;

-- As in V3, and now an answer that has ended takes no more events. The check comes after the lock, so of two writers
-- that end an answer at once, the second sees the first one's ending and is refused. message.cancelled gets what the
-- deltas so far say when the writer gives no text, and is refused when the writer gives another.
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

-- Writes the result of an ending event into its answer. It runs after the event has passed its checks and its foreign
-- key, in the same statement, so either both are written or neither.
CREATE FUNCTION conversation_end_answer() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = engine, pg_temp
AS $$
BEGIN
    IF NEW.type IN ('message.completed', 'message.cancelled', 'error') THEN
        UPDATE conversation_messages
        SET status = CASE NEW.type
                WHEN 'message.completed' THEN 'completed'
                WHEN 'message.cancelled' THEN 'cancelled'
                ELSE 'failed' END,
            text = coalesce((
                SELECT string_agg(e.text, '' ORDER BY e.seq) FROM conversation_events e
                WHERE e.tenant_id = NEW.tenant_id AND e.message_id = NEW.message_id AND e.type = 'message.delta'), ''),
            input_tokens = NEW.input_tokens,
            output_tokens = NEW.output_tokens,
            code = NEW.code,
            completed_at = now()
        WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id;
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER end_answer AFTER INSERT ON conversation_events
    FOR EACH ROW EXECUTE FUNCTION conversation_end_answer();
