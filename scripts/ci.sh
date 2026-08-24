#!/bin/bash
# CI 파이프라인 — 로컬에서도 같은 명령으로 돈다: ./scripts/ci.sh
# 실패를 skip 으로 우회하지 않는다 (docs/testing.md).
set -euo pipefail
cd "$(dirname "$0")/.."

# 0. 로컬 실행기 구문·명령 계약
./scripts/local-test.sh
./scripts/local-distribution-test.sh
./scripts/fireblocks-contract-test-test.sh
if [ -n "${BCM_LOCAL_DIST_SMOKE_ARCHIVE:-}" ]; then
    ./scripts/local-distribution-smoke.sh "$BCM_LOCAL_DIST_SMOKE_ARCHIVE"
fi

# 1. 빌드 + 전체 테스트 + ktlintCheck (check 에 통합) — dependency lock 은 strict 기본
./gradlew --no-build-cache build

# 2. 전체 배포 의존성 CVE 검사 — High/Critical(CVSS 7.0+) 또는 스캔 오류면 실패
./gradlew --no-build-cache --no-configuration-cache --no-parallel --rerun-tasks dependencyCheckAggregate

# 3. docs/api 생성물 신선도 — openapi.yaml 과 생성물이 어긋난 채 커밋되는 것 방지
(cd docs/api && python3 build.py)
if [ -n "$(git status --porcelain -- docs/api)" ]; then
    echo "docs/api 생성물이 openapi.yaml 과 어긋남 — 'python3 docs/api/build.py' 재생성 결과를 커밋할 것" >&2
    git status --porcelain -- docs/api >&2
    exit 1
fi

echo "CI 그린"
