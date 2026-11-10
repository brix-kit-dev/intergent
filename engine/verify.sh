#!/usr/bin/env bash
# The whole CI check. GitHub Actions runs this file, so a pass here means a pass there.
# It builds from an empty Maven repository, checks that the build printed no key, then checks what it really downloaded.
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
build_log=$(mktemp)
trap 'rm -rf "$repository" "$build_log"' EXIT

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
status=0
mvn -B -ntp "${retries[@]}" -Dmaven.repo.local="$repository" clean verify 2>&1 | tee "$build_log" || status=$?

# No key may reach a log, not even one a test made up, because nobody reading the log can tell which is which.
# This runs even when the build failed, since a failed assertion can print a key. The shape is that of ApiKeys and
# SiteKeys, which BuildLogKeysTest checks; a key that QuietOutput masked does not match it.
key_shape='(aie_(site_)?(live|test)_[a-z0-9]{1,8}_)[0-9A-Za-z]{40}'
keys() { LC_ALL=C grep -a "$@" -E "$key_shape" "$build_log"; }
if keys -q; then
  echo "verify.sh: the build printed a key on $(keys -c) line(s). Mask it where it is printed. The first, masked:" >&2
  keys -m 5 | LC_ALL=C sed -E "s/$key_shape/\\1<masked>/g" | cut -c1-300 >&2
  exit 1
fi
[ "$status" -eq 0 ] || exit "$status"

check=(java -cp guard/target/classes aie.guard.supply.SupplyCheck)
if [ "${1:-}" = "--update-lock" ]; then
  "${check[@]}" write-lock . "$repository"
fi
"${check[@]}" check . "$repository"
