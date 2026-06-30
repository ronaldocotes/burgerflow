#!/usr/bin/env bash
set -euo pipefail

# Executa a Fase 2 da auditoria frontend em QA local:
# - sobe backend em 8088 apontado para o Postgres QA;
# - garante e popula o tenant audit;
# - sobe frontend em 3011 por build/start;
# - roda fluxos seguros sem salvar entidades nem fechar pedidos.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/.codex-run"
BACKEND_LOG="$LOG_DIR/phase2-backend.log"
FRONTEND_LOG="$LOG_DIR/phase2-frontend.log"

API_URL="${MF_API_URL:-http://localhost:8088/api/v1}"
FRONTEND_PORT="${FRONTEND_PORT:-3011}"
BASE_URL="${BASE_URL:-http://localhost:$FRONTEND_PORT}"
CONTROL_URL="${MF_CONTROL_DATABASE_URL:-postgresql://menuflow:menuflow123@localhost:5545/menuflow_control}"
TENANT_SLUG="${MF_AUDIT_TENANT_SLUG:-audit}"
ADMIN_EMAIL="${MF_AUDIT_ADMIN_EMAIL:-audit@menuflow.local}"
ADMIN_PASSWORD="${MF_AUDIT_ADMIN_PASSWORD:-Audit@1234}"

mkdir -p "$LOG_DIR"
rm -f "$BACKEND_LOG" "$FRONTEND_LOG"

backend_pid=""
frontend_pid=""

cleanup() {
  if [[ -n "$frontend_pid" ]] && kill -0 "$frontend_pid" >/dev/null 2>&1; then
    kill "$frontend_pid" >/dev/null 2>&1 || true
  fi
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
    if [[ "$name" == "frontend" ]] && [[ -n "$frontend_pid" ]] && ! kill -0 "$frontend_pid" >/dev/null 2>&1; then
      echo "$name encerrou antes de responder." >&2
      return 1
    fi
    if [[ "$name" == "backend" ]] && [[ -n "$backend_pid" ]] && ! kill -0 "$backend_pid" >/dev/null 2>&1; then
      echo "$name encerrou antes de responder." >&2
      return 1
    fi
    sleep 1
  done
  echo "$name nao respondeu em $url." >&2
  return 1
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
  tail -n 160 "$BACKEND_LOG" >&2 || true
  exit 1
}

cd "$ROOT_DIR"
MF_CONTROL_DATABASE_URL="$CONTROL_URL" \
MF_API_URL="$API_URL" \
MF_AUDIT_TENANT_SLUG="$TENANT_SLUG" \
MF_AUDIT_ADMIN_EMAIL="$ADMIN_EMAIL" \
MF_AUDIT_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
scripts/seed-audit-tenant.sh

cd "$ROOT_DIR/frontend"
env \
  NEXT_PUBLIC_API_URL="$API_URL" \
  NEXT_PUBLIC_WS_URL="ws://localhost:8088/api/v1/ws" \
  NEXT_PUBLIC_APP_URL="$BASE_URL" \
  NEXT_PUBLIC_TENANT_SLUG="$TENANT_SLUG" \
  npm run build >"$FRONTEND_LOG" 2>&1

env \
  PORT="$FRONTEND_PORT" \
  NEXT_PUBLIC_API_URL="$API_URL" \
  NEXT_PUBLIC_WS_URL="ws://localhost:8088/api/v1/ws" \
  NEXT_PUBLIC_APP_URL="$BASE_URL" \
  NEXT_PUBLIC_TENANT_SLUG="$TENANT_SLUG" \
  npm run start -- --hostname 0.0.0.0 >>"$FRONTEND_LOG" 2>&1 &
frontend_pid="$!"

wait_for_http "$BASE_URL/login" "frontend" 120 || {
  tail -n 160 "$FRONTEND_LOG" >&2 || true
  exit 1
}

cd "$ROOT_DIR/frontend"
BASE_URL="$BASE_URL" \
MF_TENANT="$TENANT_SLUG" \
MF_EMAIL="$ADMIN_EMAIL" \
MF_PASSWORD="$ADMIN_PASSWORD" \
NODE_PATH="$ROOT_DIR/frontend/node_modules" \
node "$ROOT_DIR/docs/auditorias/2026-06-30-menuflow-phase2-safe-flows.cjs"

echo "Fase 2 frontend local concluida."
