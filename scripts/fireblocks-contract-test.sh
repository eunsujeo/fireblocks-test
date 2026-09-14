#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/.."

fail() {
    echo "오류: $*" >&2
    exit 1
}

[ -n "${BCM_FIREBLOCKS_CONTRACT_TEST_APPROVAL_ID:-}" ] ||
    fail "실 Fireblocks 호출에는 이번 실행의 사용자 명시 승인에 대응하는 실행별 승인 ID가 필요합니다."
[ -n "${BCM_FIREBLOCKS_API_KEY:-}" ] || fail "BCM_FIREBLOCKS_API_KEY가 필요합니다."
[ -n "${BCM_FIREBLOCKS_PRIVATE_KEY_FILE:-}" ] || fail "BCM_FIREBLOCKS_PRIVATE_KEY_FILE이 필요합니다."
[ -r "$BCM_FIREBLOCKS_PRIVATE_KEY_FILE" ] || fail "Fireblocks private key 파일을 읽을 수 없습니다."
[ -n "${BCM_FIREBLOCKS_CONTRACT_BLOCKCHAIN_ID:-}" ] || fail "조회할 blockchain ID가 필요합니다."

export BCM_PROVIDER=fireblocks
export BCM_VENDOR_MODE=FIREBLOCKS
export BCM_CHAIN_MODE=TESTNET
export BCM_FIREBLOCKS_CONTRACT_TEST_SCOPE=READ_ONLY
export BCM_FIREBLOCKS_BASE_URL="${BCM_FIREBLOCKS_BASE_URL:-https://api.fireblocks.io}"

case "${BCM_FIREBLOCKS_BASE_URL%/}" in
    https://api.fireblocks.io) BCM_FIREBLOCKS_BASE_URL=https://api.fireblocks.io ;;
    *) fail "실 계약 검사는 승인된 Fireblocks API 호스트 https://api.fireblocks.io만 허용합니다." ;;
esac
export BCM_FIREBLOCKS_BASE_URL

echo "실 Fireblocks 읽기 전용 계약 검사를 시작합니다."
echo "승인 ID: $BCM_FIREBLOCKS_CONTRACT_TEST_APPROVAL_ID"
echo "범위: GET /v1/blockchains, GET /v1/assets (mutation·자금·정책·Webhook 변경 없음)"
echo "대상 blockchain: $BCM_FIREBLOCKS_CONTRACT_BLOCKCHAIN_ID"

./gradlew :blockchain-manager-infra:client:fireblocksContractTest --console=plain
