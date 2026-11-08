-- Blueprint 6.3: POST /v1/api-keys creates a key, and its name tells the keys of a rotation apart. The contract allows
-- up to 100 characters. The lookup never reads it, so engine_system gets no grant on it.

ALTER TABLE tenancy_api_keys ADD COLUMN name text CHECK (char_length(name) <= 100);
