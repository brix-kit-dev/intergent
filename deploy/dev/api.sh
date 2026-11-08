#!/usr/bin/env bash
# Starts the api role on the development database. Build engine.jar and run deploy/dev/up.sh and migrate.sh first.
set -euo pipefail
cd "$(dirname "$0")"

export ENGINE_DATABASE_URL=jdbc:postgresql://127.0.0.1:15432/engine
ENGINE_APP_PASSWORD=$(sed -n 's/^ENGINE_APP_PASSWORD=//p' .env)
export ENGINE_APP_PASSWORD
ENGINE_SESSION_KEY=$(sed -n 's/^ENGINE_SESSION_KEY=//p' .env)
export ENGINE_SESSION_KEY
# Only while a key changes: the old key, which then checks tokens but no longer signs them.
ENGINE_SESSION_PREVIOUS_KEY=$(sed -n 's/^ENGINE_SESSION_PREVIOUS_KEY=//p' .env)
if [ -n "$ENGINE_SESSION_PREVIOUS_KEY" ]; then
  export ENGINE_SESSION_PREVIOUS_KEY
fi

exec java -jar ../../engine/app/target/engine.jar --role=api "$@"
