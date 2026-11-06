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

mvn -B -ntp -Dmaven.repo.local="$repository" clean verify

check=(java -cp guard/target/classes aie.guard.supply.SupplyCheck)
if [ "${1:-}" = "--update-lock" ]; then
  "${check[@]}" write-lock . "$repository"
fi
"${check[@]}" check . "$repository"
