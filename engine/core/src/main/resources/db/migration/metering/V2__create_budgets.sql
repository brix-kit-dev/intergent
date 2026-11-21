-- Blueprint 7.4, M3-6-2: each tenant spends up to a monthly limit on model calls, in US dollars (M3-6 decision 1). A
-- tenant without a row here has the cell's default limit, which the api is given when it starts (decision 4), so no
-- tenant is without one (T-4). The admin role sets a tenant's own limit, and changes it, so engine_app may add and change
-- a row; deleting one, and with it the tenant's own limit, comes with deleting a tenant.

CREATE TABLE metering_budgets (
    tenant_id         text PRIMARY KEY,
    monthly_limit_usd numeric NOT NULL CHECK (monthly_limit_usd >= 0),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE metering_budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE metering_budgets FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON metering_budgets
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

GRANT SELECT, INSERT, UPDATE ON metering_budgets TO engine_app;

-- Every ask sums what its tenant's calls cost since the start of the month, so the uses are kept in that order.
CREATE INDEX metering_usage_by_time ON metering_usage (tenant_id, created_at);
