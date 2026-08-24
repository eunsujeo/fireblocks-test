#!/usr/bin/env bash

set -euo pipefail

fail() {
    echo "오류: $*" >&2
    exit 1
}

archive="${1:-}"
[ -f "$archive" ] || fail "검증할 폐쇄망 tar.gz 경로가 필요합니다."
archive="$(cd "$(dirname "$archive")" && pwd -P)/$(basename "$archive")"

case "$(basename "$archive")" in
    *-linux-x86_64.tar.gz) platform="linux/amd64" ;;
    *-linux-aarch64.tar.gz) platform="linux/arm64" ;;
    *) fail "아카이브 이름에서 Linux CPU 아키텍처를 판정할 수 없습니다." ;;
esac

command -v docker >/dev/null 2>&1 || fail "격리 smoke에는 Docker가 필요합니다. 최종 서버에는 필요하지 않습니다."
docker info >/dev/null 2>&1 || fail "Docker가 실행 중이지 않습니다."
smoke_image="${BCM_LOCAL_DIST_SMOKE_IMAGE:-ubuntu:26.04@sha256:2260313b31c8c011cd2eebe728008efac1b3982be73eb71348ea2648d2c0e09b}"
docker image inspect "$smoke_image" >/dev/null 2>&1 ||
    fail "smoke 이미지를 연결 환경에서 먼저 받아야 합니다: $smoke_image"

docker run --rm --network none --platform "$platform" \
    -v "$archive:/work/distribution.tar.gz:ro" \
    "$smoke_image" \
    bash -euo pipefail -c '
        mkdir -p /tmp/package /tmp/state
        tar -xzf /work/distribution.tar.gz -C /tmp/package
        package_root="$(find /tmp/package -mindepth 1 -maxdepth 1 -type d)"
        "$package_root/install.sh" verify >/dev/null

        export BCM_LOCAL_CHAIN_SEED_FILE=/tmp/state/chain.seed
        export BCM_LOCAL_CHAIN_RUNTIME_DIR=/tmp/state/chain
        export BCM_LOCAL_CONTRACT_ARTIFACT_DIR="$package_root/contracts"
        export BCM_LOCAL_ANVIL_BINARY="$package_root/bin/anvil"
        export BCM_LOCAL_ANVIL_PORT=8545
        export BCM_EVM_RPC_URL=http://127.0.0.1:8545
        export BCM_LOCAL_CHAIN_MANIFEST_FILE=/tmp/state/chain/manifest.json
        export BCM_LOCAL_CHAIN_KEY_FILE=/tmp/state/chain/evm-keys.json
        export BCM_VENDOR_MODE=STUB
        export BCM_CHAIN_MODE=LOCAL
        export BCM_FIREBLOCKS_BASE_URL=http://127.0.0.1:18080
        export BCM_FIREBLOCKS_API_KEY=bcm-local-stub
        export FIREBLOCKS_JWKS_URL=http://127.0.0.1:18080/.well-known/jwks.json
        export BCM_STUB_RESET_ENABLED=true

        start_stack() {
            "$package_root/jre/bin/java" -jar "$package_root/lib/blockchain-manager-test-support.jar" chain \
                >/tmp/chain.log 2>&1 &
            chain_pid=$!
            "$package_root/bin/bcm-local" health-chain
            "$package_root/jre/bin/java" -jar "$package_root/lib/blockchain-manager-test-support.jar" \
                >/tmp/stub.log 2>&1 &
            stub_pid=$!
            "$package_root/bin/bcm-local" health-stub
        }

        stop_stack() {
            kill "$stub_pid" "$chain_pid"
            set +e
            wait "$stub_pid"; stub_status=$?
            wait "$chain_pid"; chain_status=$?
            set -e
            case "$stub_status:$chain_status" in
                0:0|143:143|130:130|143:130|130:143) ;;
                *) echo "비정상 종료 상태: stub=$stub_status chain=$chain_status" >&2; exit 1 ;;
            esac
        }

        start_stack
        reset_result="$("$package_root/bin/bcm-local" reset)"
        case "$reset_result" in
            *STUB*ANVIL*) ;;
            *) echo "reset 응답이 Stub+Anvil 소유권과 다릅니다." >&2; exit 1 ;;
        esac
        first_manifest="$(sha256sum /tmp/state/chain/manifest.json | cut -d " " -f 1)"
        stop_stack

        start_stack
        second_manifest="$(sha256sum /tmp/state/chain/manifest.json | cut -d " " -f 1)"
        [ "$first_manifest" = "$second_manifest" ] || {
            echo "재기동 뒤 결정적 manifest가 달라졌습니다." >&2
            exit 1
        }
        stop_stack
        echo "offline linux distribution smoke passed"
    '
