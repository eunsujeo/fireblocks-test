#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'EOF'
사용법:
  ./scripts/system-test-ci.sh pr                 # PR lane: STUB+LOCAL smoke
  ./scripts/system-test-ci.sh nightly            # nightly lane: STUB+LOCAL full
  ./scripts/system-test-ci.sh manual-fireblocks  # 사용자 승인·Secret이 있는 수동 read-only lane
  ./scripts/system-test-ci.sh plan <lane>        # CI 제품 설정에 넣을 실제 명령 출력

자동 lane은 실 Fireblocks를 호출하지 않습니다. manual-fireblocks도 fireblocks-contract-test.sh의
실행별 승인 ID와 공식 origin 검증을 그대로 통과해야 합니다.
EOF
}

command_for() {
    case "$1" in
        pr) printf '%s\n' './scripts/system-test.sh smoke' ;;
        nightly) printf '%s\n' './scripts/system-test.sh full' ;;
        manual-fireblocks) printf '%s\n' './scripts/fireblocks-contract-test.sh' ;;
        *) echo "오류: 알 수 없는 CI lane입니다: $1" >&2; return 2 ;;
    esac
}

if [ "$#" -eq 0 ] || [ "$1" = "help" ] || [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    usage
    exit 0
fi

if [ "$1" = "plan" ]; then
    [ "$#" -eq 2 ] || { echo "오류: plan에는 lane 하나가 필요합니다." >&2; exit 2; }
    command_for "$2"
    exit 0
fi

[ "$#" -eq 1 ] || { echo "오류: lane 하나만 지정하세요." >&2; exit 2; }
case "$1" in
    pr) exec ./scripts/system-test.sh smoke ;;
    nightly) exec ./scripts/system-test.sh full ;;
    manual-fireblocks) exec ./scripts/fireblocks-contract-test.sh ;;
    *) command_for "$1" >/dev/null ;;
esac
