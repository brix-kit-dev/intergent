-- M3-5-2: the admin role reads one tenant's entries oldest first, a page at a time, each page after the last entry of
-- the one before, by the time the database added it and then by its ID. This index has them in that order.

CREATE INDEX trust_audit_records_by_time ON trust_audit_records (tenant_id, created_at, record_id);
