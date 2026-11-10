-- Code point 29: an HTTP status fits an integer. V3 made response_status bigint only because Row.number read nothing
-- else then; since code point 19 it reads smallint, integer and bigint. A status has three digits, so every stored
-- value keeps its meaning, and the table only holds answers for 24 hours.

ALTER TABLE tenancy_idempotency_keys ALTER COLUMN response_status TYPE integer;
