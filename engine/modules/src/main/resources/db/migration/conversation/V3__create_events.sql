-- Blueprint 6.4: an answer is an ordered log of events, and a stream is only one reader of it. The database numbers the
-- events of each answer itself: the first is 1 and each next one is one more, with no gap. This script takes
-- message.started and message.delta. The ending events come later, each with the rules that keep it right.
--
-- No answer exists before this script: nothing has called Messages.startAssistantMessage yet, so no answer lacks its
-- message.started.

-- A foreign key between tenant tables pairs tenant_id with tenant_id (T-1), so the pair must be unique here.
ALTER TABLE conversation_messages
    ADD CONSTRAINT conversation_messages_tenant_message_key UNIQUE (tenant_id, message_id);

CREATE TABLE conversation_events (
    tenant_id  text NOT NULL,
    message_id text NOT NULL,
    -- Given by conversation_number_event below, whatever the writer puts here.
    seq        integer NOT NULL,
    type       text NOT NULL CHECK (type IN ('message.started', 'message.delta')),
    -- The text of a delta. message.started carries none.
    text       text,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- With the tenant first, an event of one tenant never meets another tenant's events, not even in this key.
    PRIMARY KEY (tenant_id, message_id, seq),
    FOREIGN KEY (tenant_id, message_id) REFERENCES conversation_messages (tenant_id, message_id),
    -- An answer starts with message.started, and only its first event is one.
    CONSTRAINT conversation_events_started_first CHECK ((type = 'message.started') = (seq = 1)),
    -- A delta has text; message.started has none.
    CONSTRAINT conversation_events_text CHECK (
        CASE type WHEN 'message.delta' THEN coalesce(text, '') <> '' ELSE text IS NULL END)
);

ALTER TABLE conversation_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_events FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON conversation_events
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

-- Events are only added. M2 deletes nothing (cleanup is M4).
GRANT SELECT, INSERT ON conversation_events TO engine_app;

-- A writer locks the answer before it takes a number, and locking a row needs UPDATE on its table. The grant must not
-- let engine_app change a message itself, so the table refuses every change it is asked for. Locking does not count as
-- a change.
GRANT UPDATE ON conversation_messages TO engine_app;

CREATE FUNCTION conversation_refuse_message_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'A message changes only through its events, so message % cannot be changed', OLD.message_id;
END
$$;

CREATE TRIGGER changes_only_through_events BEFORE UPDATE ON conversation_messages
    FOR EACH ROW EXECUTE FUNCTION conversation_refuse_message_change();

-- Gives each event its number. It locks the answer first and keeps the lock until the transaction ends, so a second
-- writer waits here: the numbers of one answer are given and committed one after the other, and a reader that reads on
-- past a number never skips one committed later. A number comes from the events themselves inside the transaction, not
-- from a sequence, so a rolled-back event leaves no gap: a sequence never takes back a number it gave.
--
-- A message out of the writer's reach gets no lock and no check here. Row-level security, the checks or the foreign key
-- refuse the row next, whatever number it got.
CREATE FUNCTION conversation_number_event() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = engine, pg_temp
AS $$
DECLARE
    found_role text;
BEGIN
    SELECT role INTO found_role
    FROM conversation_messages
    WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id
    FOR UPDATE;
    IF FOUND AND found_role <> 'assistant' THEN
        RAISE EXCEPTION 'Only an answer has events, and message % is not one', NEW.message_id;
    END IF;
    NEW.seq := coalesce((
        SELECT max(seq) FROM conversation_events WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id), 0) + 1;
    RETURN NEW;
END
$$;

CREATE TRIGGER number_event BEFORE INSERT ON conversation_events
    FOR EACH ROW EXECUTE FUNCTION conversation_number_event();
