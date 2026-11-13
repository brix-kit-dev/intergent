-- Blueprint 6.3 and 6.4: the messages of a conversation. What the end user says is stored whole. An answer starts in
-- progress and empty: its events go to an event log of their own, and its ending brings its text, usage, code and end
-- time, each with the rules that keep them right.

-- A foreign key between tenant tables pairs tenant_id with tenant_id (T-1), so the pair must be unique here.
ALTER TABLE conversation_conversations
    ADD CONSTRAINT conversation_conversations_tenant_conversation_key UNIQUE (tenant_id, conversation_id);

CREATE TABLE conversation_messages (
    message_id      text PRIMARY KEY,
    tenant_id       text NOT NULL,
    conversation_id text NOT NULL,
    -- The order of the conversation. Message IDs cannot give it: two messages made in the same millisecond differ only in
    -- their random part. Only the order counts here, so a gap does no harm.
    ordinal         bigint GENERATED ALWAYS AS IDENTITY,
    role            text NOT NULL CHECK (role IN ('user', 'assistant')),
    -- The statuses of M2. blocked comes with the trust layer in M3.
    status          text NOT NULL CHECK (status IN ('in_progress', 'completed', 'cancelled', 'failed')),
    text            text NOT NULL DEFAULT '',
    input_tokens    bigint,
    output_tokens   bigint,
    code            text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation_conversations (tenant_id, conversation_id),
    -- A message has an end time exactly when it has ended.
    CONSTRAINT conversation_messages_end_time CHECK ((status = 'in_progress') = (completed_at IS NULL)),
    -- What the end user says is whole as stored: the contract's 1 to 32000 characters, with no usage and no code.
    CONSTRAINT conversation_messages_user_message CHECK (role <> 'user' OR (
        status = 'completed' AND char_length(text) BETWEEN 1 AND 32000
        AND input_tokens IS NULL AND output_tokens IS NULL AND code IS NULL)),
    -- An answer starts in progress and empty. Nothing can end it yet: that comes with its ending events, which replace
    -- this rule.
    CONSTRAINT conversation_messages_answer CHECK (role <> 'assistant' OR (
        status = 'in_progress' AND text = ''
        AND input_tokens IS NULL AND output_tokens IS NULL AND code IS NULL))
);

-- A conversation's messages in order.
CREATE INDEX conversation_messages_conversation_order ON conversation_messages (conversation_id, ordinal);

ALTER TABLE conversation_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_messages FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON conversation_messages
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

-- Messages are only added. M2 deletes nothing (cleanup is M4), and an answer will change only through its events.
GRANT SELECT, INSERT ON conversation_messages TO engine_app;
