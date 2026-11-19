-- Point 41: message.cancelled says that someone asked to cancel the answer, so an answer takes it only with a cancel
-- request. V5 made the request leave message.cancelled as the only ending; this makes message.cancelled need the
-- request. Both writers of message.cancelled have one already: the generator writes it once a request reached it, and a
-- reader writes it for a writer that has gone only when a request exists, error otherwise. The rule holds them to it.
--
-- As in V5, with that one check more. Only the function changes, so the trigger, the tables and the answers already
-- ended stay as they are; an ended answer takes no event at all.
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
    IF FOUND AND NEW.type = 'message.cancelled' AND NOT EXISTS (
            SELECT FROM conversation_cancel_requests
            WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id) THEN
        RAISE EXCEPTION 'Answer % has no request to cancel it, so it cannot end as cancelled', NEW.message_id;
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
