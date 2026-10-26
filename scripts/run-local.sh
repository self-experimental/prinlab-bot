#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then
  echo 'Создайте .env из .env.example и заполните настройки.' >&2
  exit 1
fi
set -a
source .env
set +a
exec java -jar target/app.jar "$@"
