CREATE TABLE alpha_lock_seen AS
SELECT count(*) AS held FROM pg_locks WHERE locktype = 'advisory' AND objid = 20261105 AND granted;
