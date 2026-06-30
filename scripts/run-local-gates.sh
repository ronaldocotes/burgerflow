#!/usr/bin/env bash
#
# Run the local quality gates used before MenuFlow deploy/audit work.
# It intentionally avoids production, remote SSH and destructive business flows.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

section() {
  printf '\n==> %s\n' "$1"
}

section "frontend lint"
(cd "$ROOT/frontend" && npm run lint)

section "frontend type-check"
(cd "$ROOT/frontend" && npm run type-check)

section "frontend unit tests"
(cd "$ROOT/frontend" && npm test)

section "frontend production build"
(cd "$ROOT/frontend" && npm run build)

section "backend tests"
(cd "$ROOT/backend" && ./gradlew test --no-daemon --stacktrace)

section "mobile lint"
(cd "$ROOT/mobile" && npm run lint)

section "mobile type-check"
(cd "$ROOT/mobile" && npm run type-check)

section "mobile unit tests"
(cd "$ROOT/mobile" && npm test)

section "ia tests"
if command -v pytest >/dev/null 2>&1; then
  (cd "$ROOT/ia" && pytest tests/ -q)
elif python3 -m pytest --version >/dev/null 2>&1; then
  (cd "$ROOT/ia" && python3 -m pytest tests/ -q)
else
  echo "SKIP: pytest not installed in this shell. Run scripts/run-ia-tests-local.sh first." >&2
fi

section "done"
