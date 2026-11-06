CREATE TABLE alpha_items (tenant_id text PRIMARY KEY, note text);
INSERT INTO alpha_items (tenant_id, note) VALUES ('ten_a', 'old');
ALTER TABLE alpha_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE alpha_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON alpha_items USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
