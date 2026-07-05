#!/usr/bin/env bash
#
# Garante o tenant "demo" em PRODUCAO, de forma idempotente.
#
# Por que existe: o DevDataSeeder (que cria o tenant demo) so roda no perfil
# `dev`. Em prod (`SPRING_PROFILES_ACTIVE=prod`) nada provisiona o demo, e o
# frontend e buildado com NEXT_PUBLIC_TENANT_SLUG=demo — num deploy limpo o
# GET /public/demo/menu responde 404 e o cardapio publico fica fora do ar.
#
# O que faz:
#   1. cria tenant "demo" + usuario admin no banco de CONTROLE, via
#      `docker exec` no container menuflow-postgres (nao precisa de psql no
#      host nem de porta publicada). A senha e hasheada com pgcrypto
#      crypt(..., gen_salt('bf', 12)) — compativel com o BCryptPasswordEncoder
#      do backend — e nunca aparece em argv (viaja por env var).
#   2. roda scripts/seed-demo-official.py pela API publica (config do
#      restaurante, categorias, produtos, insumos). O 1o acesso ao tenant
#      tambem dispara a criacao do banco tenant_demo + Flyway (MF_DB_AUTOCREATE).
#
# Ambos os passos sao idempotentes: rodar de novo nao duplica nada.
#
# Uso (na A1, com o compose de producao de pe):
#   MF_DB_USER=menuflow MF_DB_CONTROL=menuflow_control \
#   MF_DEMO_ADMIN_PASSWORD='...' \
#   MF_PUBLIC_URL=https://menuflow.duckdns.org \
#   scripts/seed-demo-prod.sh
#
# Variaveis:
#   MF_DEMO_ADMIN_PASSWORD  obrigatoria; senha do admin do tenant demo
#   MF_DEMO_ADMIN_EMAIL     default: admin@demo.com
#   MF_DEMO_TENANT_SLUG     default: demo (deve casar com NEXT_PUBLIC_TENANT_SLUG)
#   MF_DEMO_TENANT_NAME     default: MenuFlow Demo
#   MF_DB_USER              obrigatoria; usuario do Postgres de controle
#   MF_DB_CONTROL           default: menuflow_control
#   MF_PUBLIC_URL           default: https://menuflow.duckdns.org
#   MF_POSTGRES_CONTAINER   default: menuflow-postgres
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TENANT_SLUG="${MF_DEMO_TENANT_SLUG:-demo}"
TENANT_NAME="${MF_DEMO_TENANT_NAME:-MenuFlow Demo}"
ADMIN_EMAIL="${MF_DEMO_ADMIN_EMAIL:-admin@demo.com}"
ADMIN_PASSWORD="${MF_DEMO_ADMIN_PASSWORD:?defina MF_DEMO_ADMIN_PASSWORD (senha do admin do tenant demo)}"
DB_USER="${MF_DB_USER:?defina MF_DB_USER (usuario do Postgres de controle)}"
DB_CONTROL="${MF_DB_CONTROL:-menuflow_control}"
PUBLIC_URL="${MF_PUBLIC_URL:-https://menuflow.duckdns.org}"
PG_CONTAINER="${MF_POSTGRES_CONTAINER:-menuflow-postgres}"

if ! command -v docker >/dev/null 2>&1; then
  echo "ERRO: docker nao encontrado no PATH." >&2
  exit 2
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "ERRO: python3 nao encontrado no PATH (necessario para o seed oficial)." >&2
  exit 2
fi
if [[ ! "$TENANT_SLUG" =~ ^[a-z0-9][a-z0-9_-]{1,48}[a-z0-9]$ ]]; then
  echo "ERRO: slug invalido: $TENANT_SLUG" >&2
  exit 2
fi
if ! docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then
  echo "ERRO: container '$PG_CONTAINER' nao encontrado. O compose de producao esta de pe?" >&2
  exit 2
fi

echo "==> garantindo tenant '$TENANT_SLUG' + admin '$ADMIN_EMAIL' no banco de controle"

# A senha entra no container por env var e vira variavel psql via backtick
# (\set ... `printf ...`), para nunca aparecer em `ps`/argv.
docker exec -i \
  -e MF_SEED_PASSWORD="$ADMIN_PASSWORD" \
  "$PG_CONTAINER" \
  psql -U "$DB_USER" -d "$DB_CONTROL" \
  -v ON_ERROR_STOP=1 \
  -v TENANT_SLUG="$TENANT_SLUG" \
  -v TENANT_NAME="$TENANT_NAME" \
  -v ADMIN_EMAIL="$ADMIN_EMAIL" <<'SQL'
-- pgcrypto: gen_random_uuid() + crypt()/gen_salt() para o hash bcrypt.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

\set ADMIN_PASSWORD `printf '%s' "$MF_SEED_PASSWORD"`

INSERT INTO tenants (id, slug, display_name, subscription_plan, is_active, created_at, updated_at)
SELECT gen_random_uuid(), :'TENANT_SLUG', :'TENANT_NAME', 'PRO', true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE slug = :'TENANT_SLUG');

-- Hash bcrypt $2a$ (custo 12), aceito pelo BCryptPasswordEncoder do Spring.
INSERT INTO users (id, tenant_id, email, password_hash, first_name, last_name, role, is_active, created_at, updated_at)
SELECT gen_random_uuid(), t.id, lower(:'ADMIN_EMAIL'),
       crypt(:'ADMIN_PASSWORD', gen_salt('bf', 12)),
       'Admin', 'Demo', 'ADMIN', true, now(), now()
FROM tenants t
WHERE t.slug = :'TENANT_SLUG'
  AND NOT EXISTS (
    SELECT 1 FROM users u
    WHERE u.tenant_id = t.id AND u.email = lower(:'ADMIN_EMAIL')
  );

SELECT t.slug, t.is_active, u.email, u.role
FROM tenants t
JOIN users u ON u.tenant_id = t.id AND u.email = lower(:'ADMIN_EMAIL')
WHERE t.slug = :'TENANT_SLUG';
SQL

echo "==> rodando seed oficial via API ($PUBLIC_URL)"
MF_API_URL="$PUBLIC_URL/api/v1" \
MF_TENANT="$TENANT_SLUG" \
MF_EMAIL="$ADMIN_EMAIL" \
MF_PASSWORD="$ADMIN_PASSWORD" \
python3 "$ROOT_DIR/scripts/seed-demo-official.py"

echo "==> smoke do cardapio publico"
curl --fail --show-error --silent --output /dev/null --max-time 20 \
  "$PUBLIC_URL/api/v1/public/$TENANT_SLUG/menu"
echo "cardapio publico OK: $PUBLIC_URL/api/v1/public/$TENANT_SLUG/menu"
