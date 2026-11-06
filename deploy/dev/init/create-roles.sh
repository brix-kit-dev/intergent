#!/bin/sh
# The postgres image runs this once, when the data volume is new.
set -eu
psql -v ON_ERROR_STOP=1 \
  -v owner_password="$ENGINE_OWNER_PASSWORD" \
  -v app_password="$ENGINE_APP_PASSWORD" \
  -U postgres -d engine -f /create-roles.sql
