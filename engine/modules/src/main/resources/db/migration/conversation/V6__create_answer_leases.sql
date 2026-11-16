-- Blueprint 6.4: the writer of an answer holds a lease and renews it while it writes. Once the lease has run out, a
-- reader ends the answer for it, since a writer that stopped renewing has gone away. The ending refuses every later
-- write, so a writer taken for gone cannot write into the answer after all.
--
-- A lease belongs to an answer in progress: it starts with the answer, and is renewed only while the answer goes on. It
-- is kept apart from the message, which changes only through its events (M2-11 decision 1).

CREATE TABLE conversation_answer_leases (
    tenant_id  text NOT NULL,
    message_id text NOT NULL,
    -- When a reader may take the writer for gone, unless the writer renews the lease first.
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, message_id),
    FOREIGN KEY (tenant_id, message_id) REFERENCES conversation_messages (tenant_id, message_id)
);

ALTER TABLE conversation_answer_leases ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON conversation_answer_leases
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

-- A lease is added with its answer and renewed while the answer goes on. M2 deletes nothing (cleanup is M4).
GRANT SELECT, INSERT, UPDATE ON conversation_answer_leases TO engine_app;

-- A lease starts and is renewed under its answer's lock, as an event goes in, so a renewal and an ending come one after
-- the other: a renewal that waited for an ending sees it and is refused. A lease stays with its answer.
--
-- A message out of the writer's reach gets no lock and no check here. Row-level security or the foreign key refuses the
-- row next.
CREATE FUNCTION conversation_check_answer_lease() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = engine, pg_temp
AS $$
DECLARE
    found_role text;
    found_status text;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.message_id <> OLD.message_id THEN
        RAISE EXCEPTION 'A lease stays with its answer, so it cannot move from % to %', OLD.message_id, NEW.message_id;
    END IF;
    SELECT role, status INTO found_role, found_status
    FROM conversation_messages
    WHERE tenant_id = NEW.tenant_id AND message_id = NEW.message_id
    FOR UPDATE;
    IF FOUND AND found_role <> 'assistant' THEN
        RAISE EXCEPTION 'Only an answer has a lease, and message % is not one', NEW.message_id;
    END IF;
    IF FOUND AND found_status <> 'in_progress' THEN
        RAISE EXCEPTION 'Answer % has ended, so its lease is over', NEW.message_id;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER check_answer_lease BEFORE INSERT OR UPDATE ON conversation_answer_leases
    FOR EACH ROW EXECUTE FUNCTION conversation_check_answer_lease();

-- Every answer in progress gets a lease, those started before this script too. Their writers renew nothing, so a reader
-- may end them once the lease runs out. The owner is held by FORCE like anyone, and migrate runs with row_security off,
-- so FORCE is lifted for the copy and put back after it.
ALTER TABLE conversation_messages NO FORCE ROW LEVEL SECURITY;

INSERT INTO conversation_answer_leases (tenant_id, message_id, expires_at)
SELECT tenant_id, message_id, now() + interval '30 seconds'
FROM conversation_messages
WHERE role = 'assistant' AND status = 'in_progress';

ALTER TABLE conversation_messages FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation_answer_leases FORCE ROW LEVEL SECURITY;
