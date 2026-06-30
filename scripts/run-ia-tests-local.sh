#!/usr/bin/env bash
#
# Prepare a local Python venv for IA tests and run pytest.
# Uses the repo-local .venv-ia folder so the system Python stays untouched.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IA_DIR="$ROOT/ia"
VENV_DIR="${MF_IA_VENV:-$IA_DIR/.venv-ia}"

python3 -m venv "$VENV_DIR"
# shellcheck source=/dev/null
source "$VENV_DIR/bin/activate"

python -m pip install --upgrade pip
python -m pip install -r "$IA_DIR/requirements.txt"

cd "$IA_DIR"
python -m pytest tests/ -q
