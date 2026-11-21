#!/usr/bin/env bash
# Runs the admin role on the development database, for example: deploy/dev/admin.sh create-tenant
# Build engine.jar and run deploy/dev/up.sh and migrate.sh first. The dev server is not a cell, so its cell code is dev.
set -euo pipefail
cd "$(dirname "$0")"

export ENGINE_DATABASE_URL=jdbc:postgresql://127.0.0.1:15432/engine
export ENGINE_CELL_CODE=dev
ENGINE_APP_PASSWORD=$(sed -n 's/^ENGINE_APP_PASSWORD=//p' .env)
export ENGINE_APP_PASSWORD
# budget and set-budget tell a tenant's month and limit as the api does (M3-6-2), so they read what api.sh gives it.
export DEPLOY_REGION=jp
export ENGINE_DEFAULT_MONTHLY_BUDGET_USD="${ENGINE_DEFAULT_MONTHLY_BUDGET_USD:-100}"

exec java -jar ../../engine/app/target/engine.jar --role=admin "$@"
