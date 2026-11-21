-- Blueprint 7.4, M3-6-1: metering. The gateway adds a row at the end of each model call: the tokens the model reports
-- and what they cost by the registry's price, in US dollars (M3-6 decision 1). Every call that reached a model counts,
-- however it ended, since the model was paid for it; a request refused before one adds nothing. A row is only added:
-- engine_app may read and add rows and nothing more, so what was used cannot be changed after. Budgets are counted from
-- these rows (M3-6-2). A row keeps no text of what anyone said; the message it names is in the conversation.

CREATE TABLE metering_usage (
    use_id         text PRIMARY KEY,
    tenant_id      text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    -- The request the call belongs to, as its log lines carry it. A call that no request made has none.
    request_id     text,
    application_id text NOT NULL,
    -- The message the answer was written to.
    message_id     text NOT NULL,
    task           text NOT NULL,
    model          text NOT NULL,
    input_tokens   bigint NOT NULL CHECK (input_tokens >= 0),
    output_tokens  bigint NOT NULL CHECK (output_tokens >= 0),
    -- The exact cost, with no scale, so that no sum of costs is rounded on the way.
    cost_usd       numeric NOT NULL CHECK (cost_usd >= 0)
);

ALTER TABLE metering_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE metering_usage FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON metering_usage
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

GRANT SELECT, INSERT ON metering_usage TO engine_app;
