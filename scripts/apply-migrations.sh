#!/usr/bin/env bash
#
# apply-migrations.sh — run MenuFlow Flyway migrations against N databases
# OUT-OF-BAND, the same way the app does on boot / first tenant access.
#
# WHY: the Spring app migrates the control DB on startup and each tenant DB on
# first access. But when you ship a new control V<n> or tenant V<n>, you often
# need to migrate EVERY existing database BEFORE the new code deploys (expand
# first), and to migrate read replicas / A1 mirrors that the app never touches
# directly. This script does exactly that, idempotently — re-running is a no-op
# for databases already at HEAD.
#
# It uses the Flyway CLI via Docker (no local Flyway install required) and the
# migration SQL bundled in the repo. The history table names MUST match the app:
#   - control DB  -> default  flyway_schema_history
#   - tenant DBs  -> schema_version
#
# USAGE:
#   scripts/apply-migrations.sh <control_url> [tenant_url ...]
#
# Each *_url is a full JDBC URL, e.g.:
#   jdbc:postgresql://HOST:5432/menuflow_control?user=U&password=P
#   jdbc:postgresql://HOST:5432/tenant_abc?user=U&password=P
#
# The FIRST argument is treated as the CONTROL database (control migrations +
# default history table). Every argument AFTER it is a TENANT database (tenant
# migrations + schema_version history table).
#
# EXAMPLES:
#   scripts/apply-migrations.sh \
#     "jdbc:postgresql://localhost:5432/menuflow_control?user=menuflow&password=menuflow123" \
#     "jdbc:postgresql://localhost:5432/tenant_abc?user=menuflow&password=menuflow123" \
#     "jdbc:postgresql://localhost:5432/tenant_xyz?user=menuflow&password=menuflow123"
#
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <control_jdbc_url> [tenant_jdbc_url ...]" >&2
  exit 2
fi

# Resolve repo paths (this script lives in <repo>/scripts).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONTROL_MIGRATIONS="$REPO_ROOT/backend/src/main/resources/db/control/migration"
TENANT_MIGRATIONS="$REPO_ROOT/backend/src/main/resources/db/tenant/migration"

FLYWAY_IMAGE="${FLYWAY_IMAGE:-flyway/flyway:10}"

for d in "$CONTROL_MIGRATIONS" "$TENANT_MIGRATIONS"; do
  if [[ ! -d "$d" ]]; then
    echo "FATAL: migrations dir not found: $d" >&2
    exit 1
  fi
done

run_flyway() {
  local label="$1"; shift
  local url="$1"; shift
  local migrations_dir="$1"; shift
  local history_table="$1"; shift

  echo ">>> [$label] migrating ${url%%\?*}"   # strip query string (credentials) from the log
  docker run --rm \
    --network host \
    -v "$migrations_dir":/flyway/sql:ro \
    "$FLYWAY_IMAGE" \
    -url="$url" \
    -locations=filesystem:/flyway/sql \
    -table="$history_table" \
    -baselineOnMigrate=true \
    -baselineVersion=0 \
    -connectRetries=10 \
    migrate
  echo "<<< [$label] done"
}

CONTROL_URL="$1"; shift
run_flyway "control" "$CONTROL_URL" "$CONTROL_MIGRATIONS" "flyway_schema_history"

for tenant_url in "$@"; do
  # Derive a readable label from the db name in the URL (between last / and ?).
  db_name="${tenant_url##*/}"; db_name="${db_name%%\?*}"
  run_flyway "tenant:$db_name" "$tenant_url" "$TENANT_MIGRATIONS" "schema_version"
done

echo "All migrations applied."
