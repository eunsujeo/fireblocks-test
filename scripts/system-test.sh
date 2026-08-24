#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v python3 >/dev/null 2>&1; then
    echo "오류: system-test 실행에는 Python 3가 필요합니다." >&2
    exit 1
fi

exec python3 scripts/internal/system-test-runner.py "$@"
