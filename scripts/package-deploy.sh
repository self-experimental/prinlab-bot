#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p target
LC_ALL=C COPYFILE_DISABLE=1 tar --exclude='__pycache__' --exclude='*.pyc' \
  -czf target/printlab-deploy.tar.gz \
  Dockerfile .dockerignore .env.example compose.yml pom.xml mvnw .mvn \
  src config scripts deploy .github docs README.md
printf 'Архив для VPS: %s/target/printlab-deploy.tar.gz\n' "$PWD"
