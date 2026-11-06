#!/usr/bin/env bash
# Starts the development database. The first run writes random passwords to deploy/dev/.env,
# which git ignores. To start over: docker compose -f deploy/dev/compose.yaml down -v
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  umask 077
  {
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)"
    echo "ENGINE_OWNER_PASSWORD=$(openssl rand -hex 24)"
    echo "ENGINE_APP_PASSWORD=$(openssl rand -hex 24)"
  } > .env
fi

docker compose up -d --wait
