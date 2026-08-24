#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/.."

set +e
output="$(env -u BCM_FIREBLOCKS_CONTRACT_TEST_APPROVAL_ID \
    -u BCM_FIREBLOCKS_API_KEY \
    -u BCM_FIREBLOCKS_PRIVATE_KEY_FILE \
    ./scripts/fireblocks-contract-test.sh 2>&1)"
status=$?
set -e

[ "$status" -ne 0 ] || {
    echo "승인과 Secret 없는 실 Fireblocks 계약 검사가 열렸습니다." >&2
    exit 1
}
case "$output" in
    *"실행별 승인 ID"*) ;;
    *) echo "실행별 승인 경계 안내가 없습니다." >&2; exit 1 ;;
esac

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
printf 'not-a-real-private-key\n' > "$work_dir/fireblocks.key"
set +e
output="$(
    BCM_FIREBLOCKS_CONTRACT_TEST_APPROVAL_ID=local-gate-test \
    BCM_FIREBLOCKS_API_KEY=local-gate-test \
    BCM_FIREBLOCKS_PRIVATE_KEY_FILE="$work_dir/fireblocks.key" \
    BCM_FIREBLOCKS_CONTRACT_BLOCKCHAIN_ID=local-gate-test \
    BCM_FIREBLOCKS_BASE_URL=https://credentials.example.invalid \
    ./scripts/fireblocks-contract-test.sh 2>&1
)"
status=$?
set -e

[ "$status" -ne 0 ] || {
    echo "비공식 HTTPS 호스트로 실 Fireblocks 자격증명을 보낼 수 있습니다." >&2
    exit 1
}
case "$output" in
    *"승인된 Fireblocks API 호스트"*) ;;
    *) echo "비공식 Fireblocks 호스트 차단 안내가 없습니다." >&2; exit 1 ;;
esac

set +e
output="$(
    BCM_FIREBLOCKS_CONTRACT_TEST_APPROVAL_ID=local-direct-task-gate-test \
    BCM_FIREBLOCKS_CONTRACT_TEST_SCOPE=READ_ONLY \
    BCM_FIREBLOCKS_API_KEY=local-direct-task-gate-test \
    BCM_FIREBLOCKS_PRIVATE_KEY_FILE="$work_dir/fireblocks.key" \
    BCM_FIREBLOCKS_CONTRACT_BLOCKCHAIN_ID=local-direct-task-gate-test \
    BCM_FIREBLOCKS_BASE_URL=https://credentials.example.invalid \
    BCM_VENDOR_MODE=FIREBLOCKS \
    BCM_CHAIN_MODE=TESTNET \
    ./gradlew --no-daemon :blockchain-manager-infra:client:fireblocksContractTest --console=plain 2>&1
)"
status=$?
set -e

[ "$status" -ne 0 ] || {
    echo "Gradle task 직접 실행으로 비공식 호스트를 사용할 수 있습니다." >&2
    exit 1
}
case "$output" in
    *"official Fireblocks API origin https://api.fireblocks.io"*) ;;
    *) echo "Gradle task 내부의 공식 origin 차단 안내가 없습니다." >&2; exit 1 ;;
esac

for contract in \
    'BCM_VENDOR_MODE=FIREBLOCKS' \
    'BCM_CHAIN_MODE=TESTNET' \
    'BCM_FIREBLOCKS_CONTRACT_TEST_SCOPE=READ_ONLY' \
    'fireblocksContractTest'; do
    grep -q "$contract" scripts/fireblocks-contract-test.sh || {
        echo "실벤더 읽기 계약 경계가 없습니다: $contract" >&2
        exit 1
    }
done

if grep -E 'submitTransaction|submitContractCall|createVault|createDepositAddress|activateWebhook|resendFailed' \
    blockchain-manager-infra/client/src/fireblocksContractTest -R >/dev/null; then
    echo "읽기 전용 golden test에 mutation 호출이 포함됐습니다." >&2
    exit 1
fi

echo "fireblocks contract test gate passed"
