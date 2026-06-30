#!/usr/bin/env bash
set -euo pipefail

# Executa a Fase 4 da auditoria em QA local:
# - sobe backend em 8088 apontado para o Postgres QA;
# - garante e popula o tenant audit;
# - cria usuarios RBAC controlados no banco de controle;
# - valida auth, RBAC, tenant binding, idempotencia, DTO publico e invariantes.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/.codex-run"
BACKEND_LOG="$LOG_DIR/phase4-backend.log"

API_URL="${MF_API_URL:-http://localhost:8088/api/v1}"
CONTROL_URL="${MF_CONTROL_DATABASE_URL:-postgresql://menuflow:menuflow123@localhost:5545/menuflow_control}"
TENANT_SLUG="${MF_AUDIT_TENANT_SLUG:-audit}"
ADMIN_EMAIL="${MF_AUDIT_ADMIN_EMAIL:-audit@menuflow.local}"
ADMIN_PASSWORD="${MF_AUDIT_ADMIN_PASSWORD:-Audit@1234}"
RBAC_PASSWORD="${MF_RBAC_PASSWORD:-Audit@1234}"
OPERATOR_EMAIL="${MF_OPERATOR_EMAIL:-audit.operator@menuflow.local}"
STAFF_EMAIL="${MF_STAFF_EMAIL:-audit.staff@menuflow.local}"

# Hash BCrypt de Audit@1234, aceito pelo BCryptPasswordEncoder.
RBAC_PASSWORD_HASH="${MF_RBAC_PASSWORD_HASH:-\$2b\$10\$NlfPUSSP43uxc8hF0Tj8oe4KPwnX9cdYEyMJVdC0bkXmRwwtyZvjK}"

mkdir -p "$LOG_DIR"
rm -f "$BACKEND_LOG"

backend_pid=""

cleanup() {
  if [[ -n "$backend_pid" ]] && kill -0 "$backend_pid" >/dev/null 2>&1; then
    kill "$backend_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_http() {
  local url="$1"
  local name="$2"
  local attempts="${3:-90}"
  echo "Aguardando $name em $url ..."
  for _ in $(seq 1 "$attempts"); do
    local code
    code="$(curl -sS -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || true)"
    if [[ "$code" != "000" ]]; then
      echo "$name OK (HTTP $code)."
      return 0
    fi
    if [[ -n "$backend_pid" ]] && ! kill -0 "$backend_pid" >/dev/null 2>&1; then
      echo "$name encerrou antes de responder." >&2
      return 1
    fi
    sleep 1
  done
  echo "$name nao respondeu em $url." >&2
  return 1
}

seed_rbac_users() {
  local operator_id
  local staff_id
  operator_id="$(python3 -c 'import uuid; print(uuid.uuid4())')"
  staff_id="$(python3 -c 'import uuid; print(uuid.uuid4())')"
  export TENANT_SLUG OPERATOR_EMAIL STAFF_EMAIL RBAC_PASSWORD_HASH operator_id staff_id
  psql "$CONTROL_URL" \
    -v ON_ERROR_STOP=1 \
    -v TENANT_SLUG="$TENANT_SLUG" \
    -v OPERATOR_EMAIL="$OPERATOR_EMAIL" \
    -v STAFF_EMAIL="$STAFF_EMAIL" \
    -v RBAC_PASSWORD_HASH="$RBAC_PASSWORD_HASH" \
    -v operator_id="$operator_id" \
    -v staff_id="$staff_id" <<'SQL'
WITH tenant_row AS (
  SELECT id FROM tenants WHERE slug = :'TENANT_SLUG'
),
operator_upsert AS (
  INSERT INTO users (
    id, tenant_id, email, password_hash, first_name, last_name, role, is_active, created_at, updated_at
  )
  SELECT :'operator_id', tenant_row.id, lower(:'OPERATOR_EMAIL'), :'RBAC_PASSWORD_HASH',
         'Operador', 'Auditoria', 'OPERATOR', true, now(), now()
  FROM tenant_row
  ON CONFLICT (tenant_id, email) DO UPDATE
    SET password_hash = EXCLUDED.password_hash,
        role = 'OPERATOR',
        is_active = true,
        updated_at = now()
  RETURNING email, role, is_active
),
staff_upsert AS (
  INSERT INTO users (
    id, tenant_id, email, password_hash, first_name, last_name, role, is_active, created_at, updated_at
  )
  SELECT :'staff_id', tenant_row.id, lower(:'STAFF_EMAIL'), :'RBAC_PASSWORD_HASH',
         'Atendente', 'Auditoria', 'STAFF', true, now(), now()
  FROM tenant_row
  ON CONFLICT (tenant_id, email) DO UPDATE
    SET password_hash = EXCLUDED.password_hash,
        role = 'STAFF',
        is_active = true,
        updated_at = now()
  RETURNING email, role, is_active
)
SELECT * FROM operator_upsert
UNION ALL
SELECT * FROM staff_upsert;
SQL
}

cd "$ROOT_DIR/backend"
env \
  SERVER_PORT=8088 \
  MF_DB_HOST="${MF_DB_HOST:-localhost:5545}" \
  MF_DB_MAINTENANCE="${MF_DB_MAINTENANCE:-postgres}" \
  MF_DB_CONTROL="${MF_DB_CONTROL:-menuflow_control}" \
  MF_DB_USER="${MF_DB_USER:-menuflow}" \
  MF_DB_PASSWORD="${MF_DB_PASSWORD:-menuflow123}" \
  MF_ASAAS_RECONCILE_ENABLED=false \
  ./gradlew bootRun >"$BACKEND_LOG" 2>&1 &
backend_pid="$!"

wait_for_http "$API_URL/auth/login" "backend" 120 || {
  tail -n 180 "$BACKEND_LOG" >&2 || true
  exit 1
}

cd "$ROOT_DIR"
MF_CONTROL_DATABASE_URL="$CONTROL_URL" \
MF_API_URL="$API_URL" \
MF_AUDIT_TENANT_SLUG="$TENANT_SLUG" \
MF_AUDIT_ADMIN_EMAIL="$ADMIN_EMAIL" \
MF_AUDIT_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
scripts/seed-audit-tenant.sh

echo "Garantindo usuarios RBAC controlados no tenant $TENANT_SLUG..."
seed_rbac_users

MF_API_URL="$API_URL" \
MF_TENANT="$TENANT_SLUG" \
MF_EMAIL="$ADMIN_EMAIL" \
MF_PASSWORD="$ADMIN_PASSWORD" \
MF_OPERATOR_EMAIL="$OPERATOR_EMAIL" \
MF_STAFF_EMAIL="$STAFF_EMAIL" \
MF_RBAC_PASSWORD="$RBAC_PASSWORD" \
node "$ROOT_DIR/docs/auditorias/2026-06-30-menuflow-phase4-backend-security.cjs"

echo "Fase 4 backend/dados/seguranca concluida."
