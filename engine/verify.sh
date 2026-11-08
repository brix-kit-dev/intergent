#!/usr/bin/env bash
# The whole CI check. GitHub Actions runs this file, so a pass here means a pass there.
# It builds from an empty Maven repository, then checks what that build really downloaded.
#
#   engine/verify.sh                 run the check
#   engine/verify.sh --update-lock   also rewrite dependency-lock.txt, after a dependency change
set -euo pipefail
cd "$(dirname "$0")"

# dependency-lock.txt records what this Maven version downloads.
maven_version=3.8.7
found=$(mvn -B -v)
if [[ ! "$found" =~ Apache\ Maven\ ${maven_version}([[:space:]]|$) ]]; then
  echo "verify.sh needs Maven ${maven_version}. Found: ${found%%$'\n'*}" >&2
  exit 2
fi

repository=$(mktemp -d)
trap 'rm -rf "$repository"' EXIT

# Maven Central sometimes answers 5xx or drops a connection for a moment, and Maven 3.8.7 gives up on the first 503.
# With these settings, a 408, 429, 500, 502, 503 or 504 is retried every 5 seconds, up to 10 times.
# A dropped connection is retried up to 3 times. A pooled connection is not reused after 25 seconds,
# because the runner's network cuts idle ones after about 4 minutes.
# They live here and not in .mvn, because a .mvn directory fails the build.
retries=(
  -Dmaven.wagon.http.serviceUnavailableRetryStrategy.class=standard
  -Dmaven.wagon.http.serviceUnavailableRetryStrategy.retryInterval=5000
  -Dmaven.wagon.http.serviceUnavailableRetryStrategy.maxRetries=10
  -Dmaven.wagon.http.retryHandler.count=3
  -Dmaven.wagon.httpconnectionManager.ttlSeconds=25
)
mvn -B -ntp "${retries[@]}" -Dmaven.repo.local="$repository" clean verify

check=(java -cp guard/target/classes aie.guard.supply.SupplyCheck)
if [ "${1:-}" = "--update-lock" ]; then
  "${check[@]}" write-lock . "$repository"
fi
"${check[@]}" check . "$repository"
