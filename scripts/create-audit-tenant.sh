#!/usr/bin/env bash
set -euo pipefail

# Cria um tenant/empresa de auditoria isolado para testes destrutivos controlados.
#
# Uso minimo, usando uma DATABASE_URL do banco de CONTROLE:
#
#   MF_CONTROL_DATABASE_URL='postgresql://USER:PASS@HOST:5432/menuflow_control' \
#   scripts/create-audit-tenant.sh
#
# Uso com validacao pela API e seed oficial:
#
#   MF_CONTROL_DATABASE_URL='postgresql://USER:PASS@HOST:5432/menuflow_control' \
#   MF_API_URL='https://menuflow.duckdns.org/api/v1' \
#   MF_SEED_OFFICIAL=true \
#   scripts/create-audit-tenant.sh
#
# Variaveis:
#   MF_AUDIT_TENANT_SLUG          default: audit
#   MF_AUDIT_TENANT_NAME          default: Auditoria MenuFlow
#   MF_AUDIT_ADMIN_EMAIL          default: audit@menuflow.local
#   MF_AUDIT_ADMIN_PASSWORD       default: Audit@1234
#   MF_AUDIT_ADMIN_PASSWORD_HASH  opcional; se omitido usa hash BCrypt da senha default
#   MF_CONTROL_DATABASE_URL       obrigatorio para escrever no banco de controle
#   MF_API_URL                    opcional; se informado valida login apos inserir
#   MF_SEED_OFFICIAL              true/false; se true roda scripts/seed-demo-official.py no tenant

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TENANT_SLUG="${MF_AUDIT_TENANT_SLUG:-audit}"
TENANT_NAME="${MF_AUDIT_TENANT_NAME:-Auditoria MenuFlow}"
ADMIN_EMAIL="${MF_AUDIT_ADMIN_EMAIL:-audit@menuflow.local}"
ADMIN_PASSWORD="${MF_AUDIT_ADMIN_PASSWORD:-Audit@1234}"

# Hash gerado com bcrypt para "Audit@1234". Se trocar a senha, gere outro hash
# e passe via MF_AUDIT_ADMIN_PASSWORD_HASH.
DEFAULT_PASSWORD_HASH='$2b$10$NlfPUSSP43uxc8hF0Tj8oe4KPwnX9cdYEyMJVdC0bkXmRwwtyZvjK'
ADMIN_PASSWORD_HASH="${MF_AUDIT_ADMIN_PASSWORD_HASH:-$DEFAULT_PASSWORD_HASH}"

CONTROL_URL="${MF_CONTROL_DATABASE_URL:-}"
API_URL="${MF_API_URL:-}"
SEED_OFFICIAL="${MF_SEED_OFFICIAL:-false}"

if [[ -z "$CONTROL_URL" ]]; then
  echo "ERRO: informe MF_CONTROL_DATABASE_URL apontando para o banco de controle." >&2
  exit 2
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "ERRO: psql nao encontrado no PATH." >&2
  exit 2
fi

if [[ ! "$TENANT_SLUG" =~ ^[a-z0-9][a-z0-9_-]{1,48}[a-z0-9]$ ]]; then
  echo "ERRO: slug invalido: $TENANT_SLUG" >&2
  exit 2
fi

export TENANT_SLUG TENANT_NAME ADMIN_EMAIL ADMIN_PASSWORD_HASH
TENANT_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
USER_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
export TENANT_ID USER_ID

psql "$CONTROL_URL" \
  -v ON_ERROR_STOP=1 \
  -v TENANT_ID="$TENANT_ID" \
  -v USER_ID="$USER_ID" \
  -v TENANT_SLUG="$TENANT_SLUG" \
  -v TENANT_NAME="$TENANT_NAME" \
  -v ADMIN_EMAIL="$ADMIN_EMAIL" \
  -v ADMIN_PASSWORD_HASH="$ADMIN_PASSWORD_HASH" <<'SQL'
WITH existing AS (
  SELECT id FROM tenants WHERE slug = :'TENANT_SLUG'
),
inserted AS (
  INSERT INTO tenants (
    id,
    slug,
    display_name,
    subscription_plan,
    restaurant_type,
    is_active,
    created_at,
    updated_at
  )
  SELECT
    :'TENANT_ID',
    :'TENANT_SLUG',
    :'TENANT_NAME',
    'PRO',
    'HAMBURGUERIA',
    true,
    now(),
    now()
  WHERE NOT EXISTS (SELECT 1 FROM existing)
  RETURNING id
),
tenant_row AS (
  SELECT id FROM inserted
  UNION ALL
  SELECT id FROM existing
)
INSERT INTO users (
  id,
  tenant_id,
  email,
  password_hash,
  first_name,
  last_name,
  role,
  is_active,
  created_at,
  updated_at
)
SELECT
  :'USER_ID',
  tenant_row.id,
  lower(:'ADMIN_EMAIL'),
  :'ADMIN_PASSWORD_HASH',
  'Admin',
  'Auditoria',
  'ADMIN',
  true,
  now(),
  now()
FROM tenant_row
WHERE NOT EXISTS (
  SELECT 1
  FROM users
  WHERE tenant_id = tenant_row.id
    AND email = lower(:'ADMIN_EMAIL')
);

SELECT
  t.slug,
  t.display_name,
  u.email,
  u.role,
  t.is_active
FROM tenants t
JOIN users u ON u.tenant_id = t.id
WHERE t.slug = :'TENANT_SLUG'
  AND u.email = lower(:'ADMIN_EMAIL');
SQL

echo "Tenant de auditoria garantido no controle:"
echo "  tenantSlug=$TENANT_SLUG"
echo "  adminEmail=$ADMIN_EMAIL"
echo "  adminPassword=$ADMIN_PASSWORD"

if [[ -n "$API_URL" ]]; then
  echo "Validando login pela API: $API_URL"
  python3 - "$API_URL" "$TENANT_SLUG" "$ADMIN_EMAIL" "$ADMIN_PASSWORD" <<'PY'
import json
import sys
import urllib.request
import urllib.error

api_url, tenant, email, password = sys.argv[1:5]
payload = json.dumps({"tenantSlug": tenant, "email": email, "password": password}).encode("utf-8")
req = urllib.request.Request(
    f"{api_url.rstrip('/')}/auth/login",
    data=payload,
    headers={"Content-Type": "application/json"},
    method="POST",
)
try:
    with urllib.request.urlopen(req, timeout=30) as res:
        body = json.loads(res.read().decode("utf-8"))
        if not body.get("token"):
            raise SystemExit("Login respondeu sem token")
        print("Login OK.")
except urllib.error.HTTPError as exc:
    detail = exc.read().decode("utf-8", errors="replace")
    raise SystemExit(f"Login falhou: HTTP {exc.code}: {detail}") from exc
PY
fi

if [[ "$SEED_OFFICIAL" == "true" ]]; then
  if [[ -z "$API_URL" ]]; then
    echo "ERRO: MF_SEED_OFFICIAL=true exige MF_API_URL." >&2
    exit 2
  fi
  echo "Rodando seed oficial no tenant $TENANT_SLUG..."
  MF_API_URL="$API_URL" \
  MF_TENANT="$TENANT_SLUG" \
  MF_EMAIL="$ADMIN_EMAIL" \
  MF_PASSWORD="$ADMIN_PASSWORD" \
  python3 "$ROOT_DIR/scripts/seed-demo-official.py"
fi
