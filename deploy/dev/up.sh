#!/usr/bin/env bash
# Starts the development database. The first run writes random passwords and the session key to deploy/dev/.env,
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

# The session key came after the first .env files, so an older .env gets one here. An existing key stays as it is,
# because changing it would cut off the tokens already out.
if ! grep -q '^ENGINE_SESSION_KEY=' .env; then
  echo "ENGINE_SESSION_KEY=$(openssl rand -base64 32)" >> .env
fi

docker compose up -d --wait
