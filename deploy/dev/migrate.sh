#!/usr/bin/env bash
# Applies the migrations to the development database. Build engine.jar and run deploy/dev/up.sh first.
set -euo pipefail
cd "$(dirname "$0")"

export ENGINE_DATABASE_URL=jdbc:postgresql://127.0.0.1:15432/engine
ENGINE_OWNER_PASSWORD=$(sed -n 's/^ENGINE_OWNER_PASSWORD=//p' .env)
export ENGINE_OWNER_PASSWORD

exec java -jar ../../engine/app/target/engine.jar --role=migrate
