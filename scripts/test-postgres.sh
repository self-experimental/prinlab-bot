#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_container="printlab-test-$$"
cleanup() { docker rm -f "$test_container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run --rm -d --name "$test_container" \
  -e POSTGRES_DB=printlab_test -e POSTGRES_PASSWORD=printlab-test-only \
  -p 127.0.0.1::5432 postgres:17 >/dev/null
test_port="$(docker port "$test_container" 5432/tcp | cut -d: -f2)"
ready=false
for attempt in $(seq 1 30); do
  if docker exec "$test_container" pg_isready -U postgres -d printlab_test >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo 'PostgreSQL did not become ready' >&2
  exit 1
fi
TEST_DATABASE_URL="jdbc:postgresql://localhost:${test_port}/printlab_test" \
TEST_DATABASE_USER=postgres TEST_DATABASE_PASSWORD=printlab-test-only \
  ./mvnw -B -ntp verify
