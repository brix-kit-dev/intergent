-- M3-4-3, blueprint 6.4 and 9.2: the output check can stop an answer, which then ends with message.blocked and the code
-- output_blocked. What was sent of it is void: the clients take it back, and the database deletes its deltas in the same
-- transaction as the ending, after the ending is written, so that the numbers never go back and a stream that resumes
-- after a delta that is gone still finds the ending. The answer keeps its usage, since the model was paid for, and no
-- text.

ALTER TABLE conversation_messages DROP CONSTRAINT conversation_messages_status_check;
ALTER TABLE conversation_messages ADD CONSTRAINT conversation_messages_status_check
    CHECK (status IN ('in_progress', 'completed', 'blocked', 'cancelled', 'failed'));

ALTER TABLE conversation_events DROP CONSTRAINT conversation_events_type_check;
ALTER TABLE conversation_events ADD CONSTRAINT conversation_events_type_check CHECK (type IN (
    'message.started', 'message.delta', 'message.completed', 'message.blocked', 'message.cancelled', 'error'));

-- As in V4, and message.blocked carries the usage too, so a blocked answer is metered as far as it went.
ALTER TABLE conversation_events DROP CONSTRAINT conversation_events_usage;
ALTER TABLE conversation_events ADD CONSTRAINT conversation_events_usage CHECK (CASE
    WHEN type IN ('message.completed', 'message.blocked', 'message.cancelled')
        THEN coalesce(input_tokens >= 0 AND output_tokens >= 0, false)
    ELSE input_tokens IS NULL AND output_tokens IS NULL END);

-- As in V4, and message.blocked carries output_blocked, the one code it can have (blueprint 6.10).
ALTER TABLE conversation_events DROP CONSTRAINT conversation_events_code;
ALTER TABLE conversation_events ADD CONSTRAINT conversation_events_code CHECK (CASE type
    WHEN 'error' THEN coalesce(code IN ('model_failed', 'stream_interrupted'), false)
    WHEN 'message.blocked' THEN coalesce(code = 'output_blocked', false)
    ELSE code IS NULL END);

-- As in V4, and a blocked answer has no text, its usage and output_blocked.
ALTER TABLE conversation_messages DROP CONSTRAINT conversation_messages_answer;
ALTER TABLE conversation_messages ADD CONSTRAINT conversation_messages_answer CHECK (role <> 'assistant' OR CASE status
    WHEN 'in_progress' THEN text = '' AND input_tokens IS NULL AND output_tokens IS NULL AND code IS NULL
    WHEN 'completed' THEN coalesce(input_tokens >= 0 AND output_tokens >= 0, false) AND code IS NULL
    WHEN 'blocked' THEN text = '' AND coalesce(input_tokens >= 0 AND output_tokens >= 0, false)
        AND coalesce(code = 'output_blocked', false)
    WHEN 'cancelled' THEN coalesce(input_tokens >= 0 AND output_tokens >= 0, false) AND code IS NULL
    WHEN 'failed' THEN input_tokens IS NULL AND output_tokens IS NULL
        AND coalesce(code IN ('model_failed', 'stream_interrupted'), false)
    ELSE false END);

-- As in V4, and message.blocked ends its answer as blocked, with no text, then deletes the answer's deltas. It runs
-- after the event has passed its checks and its foreign key, in the same statement, so the ending, the message and the
-- deletion are written together or not at all. A cancel request still wins: V5 lets an answer that has one take
-- message.cancelled alone.
CREATE OR REPLACE FUNCTION conversation_end_answer() RETURNS trigger
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
    ELSIF NEW.type = 'message.blocked' THEN
        UPDATE conversation_messages
        SET status = 'blocked',
            text = '',
            input_tokens = NEW.input_tokens,
            output_tokens = NEW.output_tokens,
            code = NEW.code,
            completed_at = now()
        WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id;
        DELETE FROM conversation_events
        WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id AND type = 'message.delta';
    END IF;
    RETURN NULL;
END
$$;

-- The engine still only adds events, but for one case: the deltas of an answer that message.blocked voids, which the
-- ending's own trigger deletes in the statement that adds the ending. engine_app needs DELETE for that, and this trigger
-- refuses every other delete it asks for: of another kind of event, or of an answer that is not blocked. An answer's
-- deltas are gone in the statement that blocks it, and it takes no event after, so no delta of a blocked answer is ever
-- left for a delete from elsewhere to reach. The owner's clean-up of old logs (M4) is no business of the engine's runtime
-- role, so it is not held to this.
CREATE FUNCTION conversation_refuse_event_delete() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = engine, pg_temp
AS $$
BEGIN
    IF current_user = 'engine_app' AND (OLD.type <> 'message.delta' OR NOT EXISTS (
            SELECT FROM conversation_events
            WHERE tenant_id = OLD.tenant_id AND message_id = OLD.message_id AND type = 'message.blocked')) THEN
        RAISE EXCEPTION 'Events are only added, so event % of message % cannot be deleted; only message.blocked voids deltas',
            OLD.seq, OLD.message_id;
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER deltas_go_only_when_blocked BEFORE DELETE ON conversation_events
    FOR EACH ROW EXECUTE FUNCTION conversation_refuse_event_delete();

GRANT DELETE ON conversation_events TO engine_app;
