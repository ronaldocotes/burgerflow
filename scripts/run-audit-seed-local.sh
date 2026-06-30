#!/usr/bin/env bash
set -euo pipefail

# Executa o ciclo local de auditoria:
# 1. sobe o backend apontando para o Postgres QA;
# 2. garante o tenant `audit`;
# 3. popula dados ricos via API;
# 4. valida login e menu publico;
# 5. encerra o backend iniciado por este script.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/.codex-run"
LOG_FILE="$LOG_DIR/audit-backend.log"
PID_FILE="$LOG_DIR/audit-backend.pid"

API_URL="${MF_API_URL:-http://localhost:8088/api/v1}"
CONTROL_URL="${MF_CONTROL_DATABASE_URL:-postgresql://menuflow:menuflow123@localhost:5545/menuflow_control}"
TENANT_SLUG="${MF_AUDIT_TENANT_SLUG:-audit}"

mkdir -p "$LOG_DIR"
rm -f "$LOG_FILE" "$PID_FILE"

cd "$ROOT_DIR/backend"
env \
  SERVER_PORT="${SERVER_PORT:-8088}" \
  MF_DB_HOST="${MF_DB_HOST:-localhost:5545}" \
  MF_DB_MAINTENANCE="${MF_DB_MAINTENANCE:-postgres}" \
  MF_DB_CONTROL="${MF_DB_CONTROL:-menuflow_control}" \
  MF_DB_USER="${MF_DB_USER:-menuflow}" \
  MF_DB_PASSWORD="${MF_DB_PASSWORD:-menuflow123}" \
  MF_ASAAS_RECONCILE_ENABLED="${MF_ASAAS_RECONCILE_ENABLED:-false}" \
  ./gradlew bootRun >"$LOG_FILE" 2>&1 &

backend_pid="$!"
echo "$backend_pid" > "$PID_FILE"

cleanup() {
  if kill -0 "$backend_pid" >/dev/null 2>&1; then
    kill "$backend_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "Aguardando backend em $API_URL ..."
for _ in $(seq 1 90); do
  http_code="$(curl -sS -o /dev/null -w "%{http_code}" "$API_URL/auth/login" 2>/dev/null || true)"
  if [[ "$http_code" != "000" ]]; then
    echo "Backend OK."
    break
  fi
  sleep 1
done

http_code="$(curl -sS -o /dev/null -w "%{http_code}" "$API_URL/auth/login" 2>/dev/null || true)"
if [[ "$http_code" == "000" ]]; then
  echo "Backend nao respondeu HTTP dentro do tempo esperado." >&2
  tail -n 120 "$LOG_FILE" >&2 || true
  exit 1
fi

cd "$ROOT_DIR"
MF_CONTROL_DATABASE_URL="$CONTROL_URL" \
MF_API_URL="$API_URL" \
scripts/seed-audit-tenant.sh

python3 - "$API_URL" "$TENANT_SLUG" <<'PY'
import json
import sys
import urllib.request

api_url, tenant = sys.argv[1:3]
with urllib.request.urlopen(f"{api_url.rstrip('/')}/public/{tenant}/menu", timeout=30) as res:
    data = json.loads(res.read().decode("utf-8"))

categories = data.get("categories", [])
products = data.get("products", [])
if len(categories) < 10 or len(products) < 30:
    raise SystemExit(f"Menu publico insuficiente: {len(categories)} categorias, {len(products)} produtos")
print(f"Menu publico validado: {len(categories)} categorias, {len(products)} produtos.")
PY

echo "Seed local de auditoria concluido."
