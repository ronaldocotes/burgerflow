#!/usr/bin/env bash
#
# Canonical MenuFlow deploy script for the Oracle A1 host.
# This script is meant to run ON THE A1, from the repo root, after the operator
# has pulled the desired commit. It does not SSH anywhere.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/compose.prod.yml"
ENV_FILE="${MF_ENV_FILE:-$ROOT/.env.prod}"
PUBLIC_URL="${MF_PUBLIC_URL:-https://menuflow.duckdns.org}"
HEALTH_URL="${MF_HEALTH_URL:-$PUBLIC_URL/api/v1/actuator/health}"
BACKUP_DIR="${MF_BACKUP_DIR:-$ROOT/backups}"

section() {
  printf '\n==> %s\n' "$1"
}

require_file() {
  if [[ ! -f "$1" ]]; then
    echo "FATAL: required file not found: $1" >&2
    exit 1
  fi
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "FATAL: required command not found: $1" >&2
    exit 1
  fi
}

section "preflight"
require_cmd docker
require_cmd curl
require_file "$COMPOSE_FILE"
require_file "$ENV_FILE"

if ! docker network inspect web >/dev/null 2>&1; then
  echo "FATAL: docker network 'web' not found. The shared Caddy network must exist first." >&2
  exit 1
fi

if grep -Eq 'CHANGE-ME|change-me|CHANGE_ME' "$ENV_FILE"; then
  echo "FATAL: $ENV_FILE still contains placeholder values." >&2
  exit 1
fi

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config >/dev/null

# Env de producao carregado uma vez: backup (MF_DB_*) e seed do tenant demo
# (MF_DEMO_*) leem daqui.
# shellcheck source=/dev/null
set -a
source "$ENV_FILE"
set +a

section "backup if postgres is already running"
mkdir -p "$BACKUP_DIR"
if docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps --status running postgres | grep -q menuflow-postgres; then
  stamp="$(date +%Y%m%d-%H%M%S)"
  backup_file="$BACKUP_DIR/menuflow-control-$stamp.sql.gz"
  docker exec menuflow-postgres pg_dump -U "$MF_DB_USER" "$MF_DB_CONTROL" | gzip > "$backup_file"
  echo "control backup saved: $backup_file"
else
  echo "postgres container is not running yet; skipping pre-deploy backup"
fi

section "build and restart"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --build

section "wait for container health"
for service in postgres redis backend frontend; do
  echo "waiting for $service..."
  for _ in $(seq 1 40); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "menuflow-$service" 2>/dev/null || true)"
    if [[ "$status" == "healthy" || "$status" == "running" ]]; then
      echo "$service: $status"
      break
    fi
    sleep 3
  done
done

section "seed demo tenant (idempotente)"
# O DevDataSeeder so roda no perfil dev; sem este passo um deploy limpo sobe
# sem o tenant demo e o cardapio publico (NEXT_PUBLIC_TENANT_SLUG=demo) fica
# fora do ar com 404. Requer MF_DEMO_ADMIN_PASSWORD no .env.prod.
if [[ -n "${MF_DEMO_ADMIN_PASSWORD:-}" ]]; then
  MF_PUBLIC_URL="$PUBLIC_URL" "$ROOT/scripts/seed-demo-prod.sh"
else
  echo "AVISO: MF_DEMO_ADMIN_PASSWORD nao definido em $ENV_FILE; pulando o seed" >&2
  echo "       do tenant demo. Se o tenant nao existir, /cardapio fica fora do ar." >&2
fi

section "public smoke"
curl --fail --show-error --location --max-time 20 "$PUBLIC_URL/" >/dev/null
curl --fail --show-error --location --max-time 20 "$HEALTH_URL"
if [[ -n "${MF_DEMO_ADMIN_PASSWORD:-}" ]]; then
  curl --fail --show-error --location --max-time 20 \
    "$PUBLIC_URL/api/v1/public/${MF_DEMO_TENANT_SLUG:-demo}/menu" >/dev/null
  echo "cardapio publico OK"
fi

section "done"
git -C "$ROOT" log -1 --oneline || true

