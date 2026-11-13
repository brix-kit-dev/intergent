-- Blueprint 6.3: a conversation of one end user of one application. End users belong to another module, so this
-- table keeps the end user's ID alone, with no foreign key across modules (A-4). It keeps the application as well,
-- because a conversation is read only within its application: keys and end users are per application, so a test key
-- never reads a live conversation.

CREATE TABLE conversation_conversations (
    conversation_id text PRIMARY KEY,
    tenant_id       text NOT NULL,
    application_id  text NOT NULL,
    end_user_id     text NOT NULL,
    -- A language tag in standard form, as in the session token, or NULL when the caller gave none.
    locale          text CHECK (char_length(locale) BETWEEN 1 AND 35),
    created_at      timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE conversation_conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_conversations FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON conversation_conversations
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

GRANT SELECT, INSERT, UPDATE, DELETE ON conversation_conversations TO engine_app;
