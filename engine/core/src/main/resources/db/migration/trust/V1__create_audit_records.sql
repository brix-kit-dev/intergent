-- Blueprint 9.4, M3-5-1: the audit. The gateway adds an entry when the input check blocks a text, one before it calls a
-- model, and one when the call ends. An entry is only added: engine_app, the role the engine runs as, may read and add
-- rows and nothing more, so nothing at run time changes or deletes one. The table's owner is the migrate role's, which
-- the engine never runs as (blueprint 7.6).
--
-- Who asked is kept by the engine's own IDs alone: the end user by end_user_id, never by the host's ID, so once the end
-- user is deleted the ID points at no one, and the audit stays as it was (blueprint 7.5). No entry keeps what anyone said,
-- at any class (M3-5 decision 3): the text is in the conversation's messages, which the entry names, and which are kept
-- and deleted by their own rules. The conversation and its end user belong to other modules, so this table keeps their
-- IDs with no foreign key across modules (A-4).

CREATE TABLE trust_audit_records (
    record_id       text PRIMARY KEY,
    tenant_id       text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- The request the entry belongs to, as its log lines carry it. A call that no request made has none.
    request_id      text,
    application_id  text NOT NULL,
    end_user_id     text NOT NULL,
    -- The conversation's ID. A-4's scan keeps every name with another module's prefix out of this module's SQL, and the
    -- conversation module's is conversation_, so the column is named after the ID's own prefix instead.
    conv_id         text NOT NULL,
    -- The message the answer is written to. A blocked input stored nothing, so it has none.
    message_id      text,
    task            text NOT NULL,
    event           text NOT NULL CHECK (event IN (
        'input_blocked', 'model_called', 'completed', 'cancelled', 'failed', 'output_blocked')),
    data_class      text CHECK (data_class IN ('public', 'internal', 'personal', 'sensitive')),
    -- The model's ID in the registry, and the prompt's version, such as customer_chat/fake/v1.
    model           text,
    prompt          text,
    -- The rule that blocked a text, by its stable name.
    rule            text,
    -- A blocked input names its rule and nothing of a call. A call names its message, class, model and prompt, and only a
    -- blocked output names a rule as well.
    CONSTRAINT trust_audit_records_fit_the_event CHECK (
        CASE event
            WHEN 'input_blocked' THEN message_id IS NULL AND data_class IS NULL AND model IS NULL AND prompt IS NULL
                AND rule IS NOT NULL
            ELSE message_id IS NOT NULL AND data_class IS NOT NULL AND model IS NOT NULL AND prompt IS NOT NULL
                AND (rule IS NOT NULL) = (event = 'output_blocked')
        END)
);

ALTER TABLE trust_audit_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE trust_audit_records FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON trust_audit_records
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

-- Entries are only added and read. With no UPDATE and no DELETE, engine_app can neither change an entry nor delete one,
-- and it cannot lock one either. Cleaning up after the retention period comes with the other data's (blueprint 7.5).
GRANT SELECT, INSERT ON trust_audit_records TO engine_app;
