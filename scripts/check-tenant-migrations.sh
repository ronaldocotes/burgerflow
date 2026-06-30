#!/usr/bin/env bash
#
# Read-only tenant migration drift check.
# Runs against the control database and each physical tenant_<slug> database.
#
# Default: dry-run/read-only. It prints the apply-migrations command that an
# operator can review before applying migrations out-of-band.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${MF_ENV_FILE:-$ROOT/.env.prod}"
DB_HOST="${MF_DB_HOST:-localhost:5432}"
DB_PREFIX="${MF_DB_PREFIX:-tenant_}"

usage() {
  cat <<'EOF'
usage: scripts/check-tenant-migrations.sh [--env FILE] [--host HOST:PORT] [--apply-command]

Options:
  --env FILE        Env file with MF_DB_USER, MF_DB_PASSWORD, MF_DB_CONTROL.
                    Default: ./.env.prod or MF_ENV_FILE.
  --host HOST:PORT  Postgres host and port. Default: localhost:5432 or MF_DB_HOST.
  --apply-command   Print a ready-to-review scripts/apply-migrations.sh command.

This script is read-only. It never runs Flyway migrations.
EOF
}

PRINT_APPLY_COMMAND=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      ENV_FILE="$2"
      shift 2
      ;;
    --host)
      DB_HOST="$2"
      shift 2
      ;;
    --apply-command)
      PRINT_APPLY_COMMAND=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

: "${MF_DB_USER:?MF_DB_USER is required}"
: "${MF_DB_PASSWORD:?MF_DB_PASSWORD is required}"
: "${MF_DB_CONTROL:?MF_DB_CONTROL is required}"

if grep -Eq 'CHANGE-ME|change-me|CHANGE_ME' "$ENV_FILE" 2>/dev/null; then
  echo "FATAL: $ENV_FILE still contains placeholder values." >&2
  exit 1
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "FATAL: psql is required for read-only migration checks." >&2
  exit 1
fi

DB_HOST_ONLY="${DB_HOST%:*}"
DB_PORT="${DB_HOST##*:}"
if [[ "$DB_PORT" == "$DB_HOST" ]]; then
  DB_PORT=5432
fi

latest_tenant_version="$(
  find "$ROOT/backend/src/main/resources/db/tenant/migration" -maxdepth 1 -type f -name 'V*.sql' \
    | sed -E 's#^.*/V([^_]+)__.*#\1#' \
    | sort -V \
    | tail -1
)"
latest_control_version="$(
  find "$ROOT/backend/src/main/resources/db/control/migration" -maxdepth 1 -type f -name 'V*.sql' \
    | sed -E 's#^.*/V([^_]+)__.*#\1#' \
    | sort -V \
    | tail -1
)"

psql_base=(
  psql
  -h "$DB_HOST_ONLY"
  -p "$DB_PORT"
  -U "$MF_DB_USER"
  -v ON_ERROR_STOP=1
  -At
)

run_psql() {
  local db="$1"; shift
  PGPASSWORD="$MF_DB_PASSWORD" "${psql_base[@]}" -d "$db" "$@"
}

echo "control_db=$MF_DB_CONTROL"
echo "latest_control_migration=$latest_control_version"
echo "latest_tenant_migration=$latest_tenant_version"
echo

control_version="$(
  run_psql "$MF_DB_CONTROL" -c "select coalesce((select version from flyway_schema_history where success = true order by installed_rank desc limit 1), '0');"
)"
echo "control_applied_version=$control_version"
echo

mapfile -t tenants < <(
  run_psql "$MF_DB_CONTROL" -c "select slug from tenants where is_active = true order by slug;"
)

if [[ ${#tenants[@]} -eq 0 ]]; then
  echo "no active tenants found"
  exit 0
fi

printf '%-28s %-24s %-16s %-8s\n' "tenant" "database" "applied" "drift"
printf '%-28s %-24s %-16s %-8s\n' "------" "--------" "-------" "-----"

drift_count=0
tenant_urls=()
for slug in "${tenants[@]}"; do
  safe_slug="$(printf '%s' "$slug" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]_')"
  db_name="${DB_PREFIX}${safe_slug}"
  exists="$(run_psql "$MF_DB_CONTROL" -c "select exists(select 1 from pg_database where datname = '$db_name');")"
  if [[ "$exists" != "t" ]]; then
    printf '%-28s %-24s %-16s %-8s\n' "$slug" "$db_name" "missing-db" "yes"
    drift_count=$((drift_count + 1))
    continue
  fi

  applied="$(
    run_psql "$db_name" -c "select coalesce((select version from schema_version where success = true order by installed_rank desc limit 1), '0');" 2>/dev/null \
      || printf 'missing-schema-version'
  )"
  drift="no"
  if [[ "$applied" != "$latest_tenant_version" ]]; then
    drift="yes"
    drift_count=$((drift_count + 1))
  fi
  printf '%-28s %-24s %-16s %-8s\n' "$slug" "$db_name" "$applied" "$drift"
  tenant_urls+=("jdbc:postgresql://$DB_HOST/$db_name?user=$MF_DB_USER&password=***")
done

echo
echo "tenants=${#tenants[@]}"
echo "tenants_with_drift=$drift_count"

if [[ "$PRINT_APPLY_COMMAND" == "true" ]]; then
  echo
  echo "# Review and replace *** with the real password before running:"
  printf 'scripts/apply-migrations.sh %q' "jdbc:postgresql://$DB_HOST/$MF_DB_CONTROL?user=$MF_DB_USER&password=***"
  for url in "${tenant_urls[@]}"; do
    printf ' \\\n  %q' "$url"
  done
  printf '\n'
fi
