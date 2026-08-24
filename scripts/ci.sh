#!/bin/bash
# CI 파이프라인 — 로컬에서도 같은 명령으로 돈다: ./scripts/ci.sh
# 실패를 skip 으로 우회하지 않는다 (docs/testing.md).
set -euo pipefail
cd "$(dirname "$0")/.."

# 0. 로컬 실행기 구문·명령 계약
./scripts/tests/local-test.sh
./scripts/tests/system-test-test.sh
./scripts/tests/system-test-ci-test.sh
./scripts/tests/production-boundary-test.sh
./scripts/tests/local-distribution-test.sh
./scripts/tests/fireblocks-contract-test-test.sh
if [ -n "${BCM_LOCAL_DIST_SMOKE_ARCHIVE:-}" ]; then
    ./scripts/internal/local-distribution-smoke.sh "$BCM_LOCAL_DIST_SMOKE_ARCHIVE"
fi

# 1. 빌드 + 전체 테스트 + ktlintCheck (check 에 통합) — dependency lock 은 strict 기본
node --test docs/api/try-it.test.mjs
./gradlew --no-build-cache build

# 2. 전체 배포 의존성 CVE 검사 — High/Critical(CVSS 7.0+) 또는 스캔 오류면 실패
./gradlew --no-build-cache --no-configuration-cache --no-parallel --rerun-tasks dependencyCheckAggregate

# 3. docs/api 생성물 신선도 — openapi.yaml 과 생성물이 어긋난 채 커밋되는 것 방지
docs_api_snapshot="$(mktemp -d)"
trap 'rm -rf "$docs_api_snapshot"' EXIT
cp docs/api/api.md docs/api/api.html docs/api/spec.js "$docs_api_snapshot/"
(cd docs/api && python3 build.py)
docs_api_drift=false
for generated in api.md api.html spec.js; do
    cmp -s "$docs_api_snapshot/$generated" "docs/api/$generated" || docs_api_drift=true
done
if [ "$docs_api_drift" = true ]; then
    echo "docs/api 생성물이 openapi.yaml 과 어긋남 — 'python3 docs/api/build.py' 재생성 결과를 커밋할 것" >&2
    exit 1
fi

echo "CI 그린"
