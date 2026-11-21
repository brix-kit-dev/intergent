-- Blueprint 9.4, M3-5-3: a sensitive entry keeps the HMAC of what it was about instead of nothing, by a key of its
-- tenant's own, so that the same text of two tenants never shows as the same, and a short text cannot be found by trying
-- them all. Each tenant has one random key, made at its first sensitive entry and kept here wrapped by the master key,
-- which the engine is given from outside (M3-5 decision 1): the database alone opens no key. Deleting a tenant's row
-- destroys its key, and its entries' HMACs can never be checked again; that comes with deleting a tenant, so engine_app
-- may not delete one yet, nor change one: wrapping the keys again under a new master key comes with rotation.

CREATE TABLE trust_tenant_keys (
    key_id        text PRIMARY KEY,
    tenant_id     text NOT NULL UNIQUE,
    -- The master key that wrapped it: the first 8 bytes of its SHA-256, in hex.
    master_key_id text NOT NULL CHECK (master_key_id ~ '^[0-9a-f]{16}$'),
    -- The key, wrapped by the master key with AES-256-GCM and bound to its tenant and its ID: a 12-byte nonce, the 32
    -- bytes of the key encrypted and the 16-byte tag, 60 bytes in base64.
    wrapped       text NOT NULL CHECK (wrapped ~ '^[A-Za-z0-9+/]{80}$'),
    created_at    timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE trust_tenant_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE trust_tenant_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON trust_tenant_keys
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

GRANT SELECT, INSERT ON trust_tenant_keys TO engine_app;

-- The HMAC-SHA256 of the question, on the entry before a call, and of the answer, on the entry at its end, in hex, with
-- the ID of the key that made them. There is no foreign key to the key: an entry stays when its key is destroyed.
ALTER TABLE trust_audit_records
    ADD COLUMN question_hmac text CHECK (question_hmac ~ '^[0-9a-f]{64}$'),
    ADD COLUMN answer_hmac   text CHECK (answer_hmac ~ '^[0-9a-f]{64}$'),
    ADD COLUMN key_id        text;

-- Only a sensitive entry keeps an HMAC, and it names the key of every HMAC it keeps, and no key without one.
ALTER TABLE trust_audit_records ADD CONSTRAINT trust_audit_records_hmacs_only_when_sensitive CHECK (
    (question_hmac IS NULL AND answer_hmac IS NULL) OR data_class = 'sensitive');
ALTER TABLE trust_audit_records ADD CONSTRAINT trust_audit_records_key_with_its_hmacs CHECK (
    (key_id IS NOT NULL) = (question_hmac IS NOT NULL OR answer_hmac IS NOT NULL));

-- From now on a sensitive call keeps the HMAC of what it was about: the entry before it the question's, and the entry at
-- its end the answer's. The entries from before this script have none, so the rule holds for the rows added after it.
ALTER TABLE trust_audit_records ADD CONSTRAINT trust_audit_records_sensitive_keeps_its_hmac CHECK (
    data_class IS DISTINCT FROM 'sensitive' OR CASE event
        WHEN 'model_called' THEN question_hmac IS NOT NULL AND answer_hmac IS NULL
        ELSE answer_hmac IS NOT NULL AND question_hmac IS NULL
    END) NOT VALID;
