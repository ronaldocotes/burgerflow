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
# NETWORK: the Flyway container joins the Postgres container's OWN Docker
# network (default: menuflow-net, auto-detected — see detect_network below).
# `--network host` was used previously, but compose.prod.yml does NOT publish
# the Postgres port to the A1 host (intentional hardening: the DB is reachable
# only from containers on menuflow-net, never from the host's localhost). Use
# an internal hostname in the JDBC URL (e.g. "postgres:5432", the compose
# service alias — same value the backend itself uses via MF_DB_HOST), never
# "localhost". `scripts/check-tenant-migrations.sh --apply-command` already
# generates URLs in this form.
#
# USAGE:
#   scripts/apply-migrations.sh <control_url> [tenant_url ...]
#
# Each *_url is a full JDBC URL using the INTERNAL compose hostname, e.g.:
#   jdbc:postgresql://postgres:5432/menuflow_control?user=U&password=P
#   jdbc:postgresql://postgres:5432/tenant_abc?user=U&password=P
#
# The FIRST argument is treated as the CONTROL database (control migrations +
# default history table). Every argument AFTER it is a TENANT database (tenant
# migrations + schema_version history table).
#
# EXAMPLES:
#   scripts/apply-migrations.sh \
#     "jdbc:postgresql://postgres:5432/menuflow_control?user=menuflow&password=menuflow123" \
#     "jdbc:postgresql://postgres:5432/tenant_abc?user=menuflow&password=menuflow123" \
#     "jdbc:postgresql://postgres:5432/tenant_xyz?user=menuflow&password=menuflow123"
#
# ENV OVERRIDES:
#   MF_DOCKER_NETWORK    Force the Docker network the Flyway container joins
#                        (skips auto-detection). Use this if auto-detection
#                        fails or picks the wrong network.
#   MF_POSTGRES_CONTAINER Container used to auto-detect the network.
#                        Default: menuflow-postgres (matches compose.prod.yml
#                        container_name).
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
POSTGRES_CONTAINER="${MF_POSTGRES_CONTAINER:-menuflow-postgres}"

for d in "$CONTROL_MIGRATIONS" "$TENANT_MIGRATIONS"; do
  if [[ ! -d "$d" ]]; then
    echo "FATAL: migrations dir not found: $d" >&2
    exit 1
  fi
done

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "FATAL: required command not found: $1" >&2
    exit 1
  fi
}
require_cmd docker

# Auto-detect the Docker network of the running Postgres container instead of
# hardcoding "menuflow-net": Compose prefixes network names with the project
# name (e.g. "menuflow_menuflow-net" on the A1 host, see aprendizado.md
# 2026-07-02), so a literal "menuflow-net" would not match in practice.
# Reading it off the live container is robust to that prefix and to the
# network being renamed later. MF_DOCKER_NETWORK always wins when set.
detect_network() {
  if [[ -n "${MF_DOCKER_NETWORK:-}" ]]; then
    echo "$MF_DOCKER_NETWORK"
    return
  fi
  if ! docker inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1; then
    echo "FATAL: container '$POSTGRES_CONTAINER' not found; cannot auto-detect its network." >&2
    echo "       Start the stack first, or set MF_DOCKER_NETWORK explicitly." >&2
    exit 1
  fi
  local net
  net="$(docker inspect "$POSTGRES_CONTAINER" \
    --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' \
    | head -n 1)"
  if [[ -z "$net" ]]; then
    echo "FATAL: could not auto-detect a network for '$POSTGRES_CONTAINER'." >&2
    echo "       Set MF_DOCKER_NETWORK explicitly and re-run." >&2
    exit 1
  fi
  echo "$net"
}

NETWORK="$(detect_network)"
echo "using docker network: $NETWORK (container: $POSTGRES_CONTAINER)" >&2

run_flyway() {
  local label="$1"; shift
  local url="$1"; shift
  local migrations_dir="$1"; shift
  local history_table="$1"; shift

  echo ">>> [$label] migrating ${url%%\?*}"   # strip query string (credentials) from the log
  docker run --rm \
    --network "$NETWORK" \
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
