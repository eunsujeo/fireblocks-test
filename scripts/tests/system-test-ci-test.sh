#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/../.."

help_output="$(./scripts/system-test-ci.sh help)"
for lane in 'pr' 'nightly' 'manual-fireblocks'; do
    case "$help_output" in
        *"$lane"*) ;;
        *) echo "CI lane 도움말에 $lane 가 없습니다." >&2; exit 1 ;;
    esac
done

[ "$(./scripts/system-test-ci.sh plan pr)" = './scripts/system-test.sh smoke' ] || {
    echo "PR lane이 smoke를 선택하지 않습니다." >&2
    exit 1
}
[ "$(./scripts/system-test-ci.sh plan nightly)" = './scripts/system-test.sh full' ] || {
    echo "nightly lane이 full을 선택하지 않습니다." >&2
    exit 1
}
[ "$(./scripts/system-test-ci.sh plan manual-fireblocks)" = './scripts/fireblocks-contract-test.sh' ] || {
    echo "수동 Fireblocks lane이 승인 wrapper를 선택하지 않습니다." >&2
    exit 1
}

set +e
./scripts/system-test-ci.sh plan unknown >/dev/null 2>&1
unknown_status=$?
set -e
[ "$unknown_status" -ne 0 ] || {
    echo "알 수 없는 CI lane이 허용됐습니다." >&2
    exit 1
}

echo "system test CI lane tests passed"
