-- Blueprint 6.5: an anonymous visitor has no host ID. Each anonymous session gets an end user of its own, with no
-- external_id, so one visitor's sessions cannot be linked to each other. NULLs are distinct in the unique key, so any
-- number of them fit, and an end user found by the host's ID is never an anonymous one, because that lookup compares
-- external_id with the ID. The check on its length passes a NULL, as every CHECK does.

ALTER TABLE tenancy_end_users ALTER COLUMN external_id DROP NOT NULL;
