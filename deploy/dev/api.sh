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
# Wraps each tenant's audit key, which keeps the HMACs of sensitive calls (M3-5-3).
ENGINE_AUDIT_MASTER_KEY=$(sed -n 's/^ENGINE_AUDIT_MASTER_KEY=//p' .env)
export ENGINE_AUDIT_MASTER_KEY
# The region pack the api loads. The dev server is no cell; it runs the pack of jp-general, the first cell.
export DEPLOY_REGION=jp
# What a tenant without a budget of its own may spend on models each month, in US dollars (M3-6-2). admin.sh gives the
# same, so that both tell the same limit. jp's fake model is free, so the dev server never spends it.
export ENGINE_DEFAULT_MONTHLY_BUDGET_USD="${ENGINE_DEFAULT_MONTHLY_BUDGET_USD:-100}"

exec java -jar ../../engine/app/target/engine.jar --role=api "$@"
