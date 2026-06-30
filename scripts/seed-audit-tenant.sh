#!/usr/bin/env bash
set -euo pipefail

# Garante a empresa/tenant de auditoria e popula dados ricos usando a API.
#
# Uso local QA:
#   MF_CONTROL_DATABASE_URL='postgresql://menuflow:menuflow123@localhost:5545/menuflow_control' \
#   MF_API_URL='http://localhost:8088/api/v1' \
#   scripts/seed-audit-tenant.sh
#
# Variaveis opcionais:
#   MF_AUDIT_TENANT_SLUG          default: audit
#   MF_AUDIT_TENANT_NAME          default: Auditoria MenuFlow
#   MF_AUDIT_ADMIN_EMAIL          default: audit@menuflow.local
#   MF_AUDIT_ADMIN_PASSWORD       default: Audit@1234
#   MF_AUDIT_ADMIN_PASSWORD_HASH  default: hash BCrypt de Audit@1234

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

API_URL="${MF_API_URL:-http://localhost:8088/api/v1}"

MF_API_URL="$API_URL" \
MF_SEED_OFFICIAL=true \
"$ROOT_DIR/scripts/create-audit-tenant.sh"

