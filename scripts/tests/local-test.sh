#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/../.."

bash -n scripts/local.sh
bash -n scripts/internal/local-db-init.sh

for contract in \
    '../blockchain-manager-infra/persistence/src/main/resources/db/migration:/opt/bcm/db/migration:ro' \
    '../scripts/internal/local-db-init.sh:/docker-entrypoint-initdb.d/10-bcm-schema.sh:ro'; do
    grep -Fq "$contract" config/local-compose.yaml || {
        echo "로컬 PostgreSQL 최초 스키마 초기화 mount가 없습니다: $contract" >&2
        exit 1
    }
done

for contract in \
    '/opt/bcm/db/migration/manifest.txt' \
    'psql --single-transaction' \
    'ON_ERROR_STOP=1'; do
    grep -Fq "$contract" scripts/internal/local-db-init.sh || {
        echo "로컬 PostgreSQL SQL 실행 계약이 없습니다: $contract" >&2
        exit 1
    }
done

python3 - <<'PY'
from pathlib import Path
import re

directory = Path("blockchain-manager-infra/persistence/src/main/resources/db/migration")
manifest = directory / "manifest.txt"
entries = [line.strip() for line in manifest.read_text().splitlines() if line.strip() and not line.startswith("#")]
files = sorted((path.name for path in directory.glob("V*__*.sql")), key=lambda name: int(re.match(r"V(\d+)__", name).group(1)))
assert entries == files, f"DB SQL manifest가 실제 파일의 버전 순서와 다릅니다: manifest={entries}, files={files}"
PY

for contract in \
    'API_PORT="${BCM_LOCAL_API_PORT:-38080}"' \
    'WEBHOOK_PORT="${BCM_LOCAL_WEBHOOK_PORT:-38081}"' \
    'module.SMOKE_API_PORT = port("BCM_LOCAL_API_PORT", 38080)'; do
    grep -Fq "$contract" scripts/local.sh scripts/internal/local-deposit-test.py || {
        echo "충돌을 피한 로컬 BCM 기본 포트 계약이 없습니다: $contract" >&2
        exit 1
    }
done

grep -Fq 'echo "$(component_display_name "$name"): 이미 실행 중입니다."' scripts/local.sh || {
    echo "macOS Bash에서 한글 조사 앞 변수 경계가 명시되지 않았습니다." >&2
    exit 1
}

for contract in \
    "chain) printf '%s' '로컬 블록체인 (Anvil)'" \
    "stub) printf '%s' 'Fireblocks 로컬 Stub'" \
    "api) printf '%s' 'Blockchain Manager API'" \
    "webhook) printf '%s' 'Blockchain Manager Webhook'" \
    "admin) printf '%s' 'Blockchain Manager Admin'" \
    'echo "$(component_display_name "$name") 종료 중..."' \
    'echo "$(component_display_name "$name"): RUNNING' \
    'echo "$(component_display_name "$name"): STOPPED"'; do
    grep -Fq "$contract" scripts/local.sh || {
        echo "로컬 component 표시명 계약이 없습니다: $contract" >&2
        exit 1
    }
done

if grep -q 'read -r -p' scripts/local.sh || ! grep -q 'read -e -r -p' scripts/local.sh; then
    echo "대화형 입력에 readline 편집이 적용되지 않았습니다." >&2
    exit 1
fi

help_output="$(./scripts/local.sh help)"
case "$help_output" in
    *"up [fireblocks|stub]"*) ;;
    *) echo "local.sh 도움말에 실행 모드가 없습니다." >&2; exit 1 ;;
esac
case "$help_output" in
    *"test deposit"*) ;;
    *) echo "local.sh 도움말에 로컬 입금 점검 명령이 없습니다." >&2; exit 1 ;;
esac

for contract in \
    'test_local_deposit()' \
    'python3 "$SCRIPT_DIR/internal/local-deposit-test.py"' \
    'for name in chain stub api webhook admin' \
    ':blockchain-manager-infra:messaging:awaitLocalKafkaEvent'; do
    grep -Fq "$contract" scripts/local.sh scripts/internal/local-deposit-test.py || {
        echo "로컬 입금 점검 계약이 없습니다: $contract" >&2
        exit 1
    }
done

grep -Fq 'runner.prune_completed_runs(artifact_root)' scripts/internal/local-scenario-runner.py || {
    echo "로컬 시나리오 artifact 보존 한도가 적용되지 않았습니다." >&2
    exit 1
}

for contract in \
    'local-process-launcher.py' \
    'os.setsid()' \
    'stop_managed_group()' \
    'kill -TERM "-$process_group"'; do
    grep -Fq "$contract" scripts/local.sh scripts/internal/local-process-launcher.py || {
        echo "독립 process group 관리 계약이 없습니다: $contract" >&2
        exit 1
    }
done

for contract in \
    'stop [api|webhook|admin]' \
    'api|webhook|admin) stop_process "$1"'; do
    grep -Fq "$contract" scripts/local.sh || {
        echo "독립 component 종료 계약이 없습니다: $contract" >&2
        exit 1
    }
done

set +e
isolated_state="$(mktemp -d)"
deposit_output="$(BCM_LOCAL_STATE_DIR="$isolated_state" ./scripts/local.sh test deposit 2>&1)"
deposit_status=$?
rm -rf "$isolated_state"
set -e
[ "$deposit_status" -ne 0 ] || {
    echo "중지된 환경에서 입금 점검이 성공했습니다." >&2
    exit 1
}
case "$deposit_output" in
    *"Stub 로컬 환경이 필요합니다"*) ;;
    *) echo "입금 점검의 실행 모드 안내가 없습니다." >&2; exit 1 ;;
esac

set +e
isolated_state="$(mktemp -d)"
sync_output="$(BCM_LOCAL_STATE_DIR="$isolated_state" ./scripts/local.sh sync assets 2>&1)"
sync_status=$?
rm -rf "$isolated_state"
set -e
[ "$sync_status" -ne 0 ] || {
    echo "중지된 환경에서 자산 카탈로그 동기화가 성공했습니다." >&2
    exit 1
}
case "$sync_output" in
    *"up fireblocks를 먼저 실행하세요"*) ;;
    *) echo "자산 카탈로그 동기화의 실행 모드 안내가 없습니다." >&2; exit 1 ;;
esac

if grep -q 'stub.*T11\.3.*구현 뒤' scripts/local.sh; then
    echo "완료된 Stub 모드가 아직 미구현으로 안내됩니다." >&2
    exit 1
fi

for contract in \
    './scripts/local.sh restart [fireblocks|stub]' \
    'restart_local_environment()' \
    'mode="${1:-$(active_local_mode)}"'; do
    grep -Fq "$contract" scripts/local.sh || {
        echo "현재 모드 재시작 계약이 없습니다: $contract" >&2
        exit 1
    }
done

restart_state="$(mktemp -d)"
set +e
restart_output="$(BCM_LOCAL_STATE_DIR="$restart_state" ./scripts/local.sh restart invalid 2>&1)"
restart_status=$?
set -e
rm -rf "$restart_state"
[ "$restart_status" -ne 0 ] || {
    echo "잘못된 재시작 모드가 허용됐습니다." >&2
    exit 1
}
case "$restart_output" in
    *"재시작 모드는 fireblocks 또는 stub이어야 합니다"*) ;;
    *) echo "잘못된 재시작 모드 안내가 없습니다." >&2; exit 1 ;;
esac

for contract in \
    'local_mode_runtime_state()' \
    '현재 로컬 모드: $active_mode ($runtime_state)' \
    '데이터셋: $active_mode' \
    '로컬 실행 모드: fireblocks' \
    '로컬 실행 모드: stub'; do
    grep -Fq "$contract" scripts/local.sh || {
        echo "로컬 단일 모드와 로그 식별 계약이 없습니다: $contract" >&2
        exit 1
    }
done

for contract in \
    'up_stub()' \
    'verify_fireblocks_api_authentication()' \
    'bootstrap_fireblocks_asset_catalog()' \
    'local-deposit-test.py" --bootstrap-fireblocks-catalog' \
    'Fireblocks 지원 네트워크·자산 카탈로그 준비 완료' \
    'export BCM_JOB=catalog-sync-once' \
    'asset-catalog-sync-once' \
    'sync_asset_catalog_now()' \
    '자산 카탈로그 동기화 완료' \
    ':blockchain-manager-app:bcm-bat:bootRun' \
    '실 Fireblocks 자격증명은 공식 API 주소 https://api.fireblocks.io에만 전송할 수 있습니다.' \
    'fireblocks-preflight.log' \
    'Fireblocks API 인증 성공 — 블록체인 목록 읽기 완료' \
    'starting_process_alive()' \
    'start_gradle_process chain' \
    'start_gradle_process stub' \
    'BCM_STUB_WEBHOOK_DELIVERY_URL=' \
    'BCM_STUB_RESET_ENABLED=true' \
    'BCM_ADMIN_SYSTEM_TEST_ENABLED=true' \
    'BCM_ADMIN_SYSTEM_TEST_STATE_DIRECTORY=' \
    'BCM_ADMIN_LOCAL_SCENARIO_ENABLED=true' \
    'BCM_ADMIN_LOCAL_SCENARIO_REPOSITORY=' \
    'BCM_ADMIN_LOCAL_ASSET_MANAGEMENT_ENABLED=true' \
    'BCM_ADMIN_WEBHOOK_MANAGEMENT_BASE_URL=' \
    'BCM_SYSTEM_TEST_ROOT' \
    'BCM_LOCAL_COMPOSE_PROJECT' \
    'BCM_LOCAL_POSTGRES_PORT' \
    'BCM_LOCAL_KAFKA_PORT' \
    'BCM_LOCAL_ANVIL_PORT' \
    'BCM_LOCAL_ETHEREUM_ANVIL_PORT' \
    'BCM_LOCAL_BASE_ANVIL_PORT' \
    'BCM_LOCAL_STUB_PORT' \
    'BCM_LOCAL_STUB_MANAGEMENT_PORT' \
    'BCM_LOCAL_API_PORT' \
    'BCM_LOCAL_API_MANAGEMENT_PORT' \
    'BCM_LOCAL_WEBHOOK_PORT' \
    'BCM_LOCAL_WEBHOOK_MANAGEMENT_PORT' \
    'BCM_LOCAL_ADMIN_PORT' \
    'BCM_FINALITY_CONFIRMATIONS_LOCAL=2' \
    'BCM_FIREBLOCKS_API_KEY=bcm-local-stub'; do
    grep -q "$contract" scripts/local.sh || {
        echo "Stub 로컬 조립 계약이 없습니다: $contract" >&2
        exit 1
    }
done

for contract in \
    '("ETHEREUM_SEPOLIA", 11155111)' \
    '("BASE_SEPOLIA", 84532)' \
    '지원 Fireblocks TESTNET을 찾지 못했습니다' \
    'asset-catalog-supported-sync-once' \
    'sync_asset_catalog()'; do
    grep -Fq "$contract" scripts/internal/local-deposit-test.py || {
        echo "Fireblocks 자산 우선 검색 bootstrap 계약이 없습니다: $contract" >&2
        exit 1
    }
done

preflight_line="$(grep -n 'verify_fireblocks_api_authentication$' scripts/local.sh | tail -1 | cut -d: -f1)"
api_start_line="$(grep -n 'start_gradle_process api ' scripts/local.sh | head -1 | cut -d: -f1)"
[ -n "$preflight_line" ] && [ -n "$api_start_line" ] && [ "$preflight_line" -lt "$api_start_line" ] || {
    echo "Fireblocks 인증 확인이 BCM API 기동보다 먼저 실행되지 않습니다." >&2
    exit 1
}

fireblocks_bootstrap_line="$(grep -n 'bootstrap_fireblocks_asset_catalog$' scripts/local.sh | tail -1 | cut -d: -f1)"
admin_start_line="$(grep -n 'start_gradle_process admin ' scripts/local.sh | head -1 | cut -d: -f1)"
[ -n "$fireblocks_bootstrap_line" ] && [ -n "$admin_start_line" ] && [ "$fireblocks_bootstrap_line" -lt "$admin_start_line" ] || {
    echo "Fireblocks 지원 Network와 자산 catalog가 Admin 준비 전에 초기화되지 않습니다." >&2
    exit 1
}

for contract in \
    'CHAIN_PROFILE="${BCM_LOCAL_CHAIN_PROFILE:-catalog}"' \
    '"--args=chain-cluster"' \
    'BCM_LOCAL_CHAIN_CLUSTER_MANIFEST_FILE=' \
    'local-deposit-test.py" --bootstrap-catalog' \
    '기본 네트워크·자산 준비 완료 (ETHEREUM·BASE / USDC·KRWK)' \
    '"BCM_LOCAL_CHAIN_PROFILE": "legacy"'; do
    grep -Fq "$contract" scripts/local.sh scripts/internal/system-test-runner.py || {
        echo "두 체인 기본 카탈로그 조립 계약이 없습니다: $contract" >&2
        exit 1
    }
done

grep -Fq '"BCM_LOCAL_DATASET": "stub"' scripts/internal/system-test-runner.py || {
    echo "전용 system test가 Stub 데이터 볼륨까지 삭제하도록 dataset을 고정하지 않았습니다." >&2
    exit 1
}

for contract in \
    'API 문서: http://127.0.0.1:$API_PORT/api-docs/' \
    'local-scenario-runner.py'; do
    grep -Fq "$contract" scripts/local.sh scripts/internal/local-scenario-runner.py || {
        echo "API 문서 또는 로컬 시나리오 진입점이 없습니다: $contract" >&2
        exit 1
    }
done

for contract in \
    "webhook) printf '%s' ':blockchain-manager-app:bcm-webhook:bootRun'" \
    'start_gradle_process webhook' \
    'wait_http webhook' \
    'stop_process webhook' \
    'chain stub api webhook admin' \
    'chain|stub|api|webhook|admin'; do
    grep -Fq "$contract" scripts/local.sh || {
        echo "독립 Webhook 로컬 프로세스 계약이 없습니다: $contract" >&2
        exit 1
    }
done

grep -Fq 'BCM_STUB_WEBHOOK_DELIVERY_URL="http://127.0.0.1:$WEBHOOK_PORT/webhook"' scripts/local.sh || {
    echo "Stub callback이 독립 Webhook listener를 가리키지 않습니다." >&2
    exit 1
}

for contract in 'BCM_LOCAL_POSTGRES_PORT' 'BCM_LOCAL_KAFKA_PORT'; do
    grep -q "$contract" config/local-compose.yaml || {
        echo "Compose 포트 주입 계약이 없습니다: $contract" >&2
        exit 1
    }
done

for contract in \
    '${BCM_LOCAL_DATASET:-fireblocks}-postgres-data:/var/lib/postgresql/data' \
    '${BCM_LOCAL_DATASET:-fireblocks}-kafka-data:/tmp/kraft-combined-logs' \
    'stub-postgres-data:' \
    'stub-kafka-data:' \
    'ACTIVE_MODE_FILE="$STATE_DIR/active-mode"' \
    'select_local_mode fireblocks' \
    'select_local_mode stub'; do
    grep -Fq "$contract" config/local-compose.yaml scripts/local.sh || {
        echo "Fireblocks와 Stub의 로컬 데이터 격리 계약이 없습니다: $contract" >&2
        exit 1
    }
done

if grep -Fq 'compose down --volumes --remove-orphans' scripts/local.sh && \
    ! grep -Fq '현재 선택된 $active_mode 모드' scripts/local.sh; then
    echo "purge가 선택 모드의 데이터만 삭제한다는 안내가 없습니다." >&2
    exit 1
fi

grep -q 'BCM_LOCAL_STUB_BASE_URL' scripts/local.sh || {
    echo "reset 대상 Stub URL 설정이 없습니다." >&2
    exit 1
}
grep -q '/__stub/reset' scripts/local.sh || {
    echo "Stub reset endpoint 호출이 없습니다." >&2
    exit 1
}

set +e
reset_output="$(BCM_LOCAL_STUB_BASE_URL=http://127.0.0.1:9 ./scripts/local.sh reset 2>&1)"
reset_status=$?
set -e

[ "$reset_status" -ne 0 ] || {
    echo "실행 중이지 않은 Stub reset이 성공으로 끝났습니다." >&2
    exit 1
}
case "$reset_output" in
    *"BCM DB·Kafka는 reset 대상이 아닙니다"*) ;;
    *) echo "reset 소유권 안내가 없습니다." >&2; exit 1 ;;
esac

work_dir="$(mktemp -d)"
unrelated_pid=""
cleanup() {
    if [ -n "$unrelated_pid" ] && kill -0 "$unrelated_pid" 2>/dev/null; then
        kill "$unrelated_pid" 2>/dev/null || true
        wait "$unrelated_pid" 2>/dev/null || true
    fi
    rm -rf "$work_dir"
}
trap cleanup EXIT
mkdir -p "$work_dir/bin"
printf '#!/usr/bin/env sh\ntouch "$BCM_TEST_CURL_CALLED"\n' > "$work_dir/bin/curl"
chmod +x "$work_dir/bin/curl"

preflight_repo="$work_dir/preflight-repo"
preflight_state="$work_dir/preflight-state"
preflight_marker="$work_dir/preflight-gradle-called"
mkdir -p "$preflight_repo/scripts" "$preflight_repo/config" "$preflight_state"
cp scripts/local.sh "$preflight_repo/scripts/local.sh"
chmod +x "$preflight_repo/scripts/local.sh"
: > "$preflight_repo/config/local-compose.yaml"
: > "$preflight_repo/fireblocks-test.key"
cat > "$preflight_repo/.env" <<EOF
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:15432/bcm
SPRING_DATASOURCE_USERNAME=bcm
SPRING_DATASOURCE_PASSWORD=bcm
KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
BCM_HTTP_MAX_CONNECTIONS=100
BCM_FIREBLOCKS_BASE_URL=https://api.fireblocks.io
BCM_FIREBLOCKS_API_KEY=local-preflight-test-key
BCM_FIREBLOCKS_PRIVATE_KEY_FILE=$preflight_repo/fireblocks-test.key
FIREBLOCKS_JWKS_URL=https://keys.fireblocks.io/.well-known/jwks.json
BCM_VENDOR_MODE=FIREBLOCKS
BCM_CHAIN_MODE=TESTNET
EOF
printf '#!/usr/bin/env sh\nexit 0\n' > "$work_dir/bin/docker"
chmod +x "$work_dir/bin/docker"
cat > "$preflight_repo/gradlew" <<'EOF'
#!/usr/bin/env sh
touch "$BCM_PREFLIGHT_TEST_MARKER"
echo 'synthetic vendor response that must stay in the protected log'
exit 41
EOF
chmod +x "$preflight_repo/gradlew"
set +e
preflight_output="$(
    cd "$preflight_repo" &&
        PATH="$work_dir/bin:$PATH" \
        BCM_LOCAL_STATE_DIR="$preflight_state" \
        BCM_PREFLIGHT_TEST_MARKER="$preflight_marker" \
        ./scripts/local.sh up fireblocks 2>&1
)"
preflight_status=$?
set -e
[ "$preflight_status" -ne 0 ] && [ -f "$preflight_marker" ] || {
    echo "Fireblocks 인증 확인 실패가 기동을 중단하지 않았습니다." >&2
    exit 1
}
case "$preflight_output" in
    *"Fireblocks API 인증 또는 연결 확인에 실패했습니다"*) ;;
    *) echo "Fireblocks 인증 실패의 안전한 안내가 없습니다." >&2; exit 1 ;;
esac
case "$preflight_output" in
    *"fireblocks-preflight.log"*) ;;
    *) echo "Fireblocks 인증 실패의 상세 로그 경로가 없습니다." >&2; exit 1 ;;
esac
case "$preflight_output" in
    *"synthetic vendor response"*) echo "Fireblocks 응답 원문이 콘솔로 노출됐습니다." >&2; exit 1 ;;
esac
grep -Fq 'synthetic vendor response' "$preflight_state/fireblocks-preflight.log" || {
    echo "Fireblocks 인증 실패 상세 로그가 보존되지 않았습니다." >&2
    exit 1
}
case "$(uname -s)" in
    Darwin) preflight_log_mode="$(stat -f '%Lp' "$preflight_state/fireblocks-preflight.log")" ;;
    *) preflight_log_mode="$(stat -c '%a' "$preflight_state/fireblocks-preflight.log")" ;;
esac
[ "$preflight_log_mode" = 600 ] || {
    echo "Fireblocks 인증 상세 로그 권한이 600이 아닙니다: $preflight_log_mode" >&2
    exit 1
}

for malicious_url in \
    'http://127.0.0.1:18080@evil.example' \
    'http://localhost:18080@evil.example' \
    'http://[::1]:18080@evil.example' \
    'http://127.0.0.1:18080/path' \
    'http://127.0.0.1:18080?query=1' \
    'http://127.0.0.1:18080#fragment' \
    'http://127.0.0.1:0' \
    'http://127.0.0.1:65536'; do
    marker="$work_dir/curl-called"
    set +e
    output="$(
        PATH="$work_dir/bin:$PATH" \
        BCM_TEST_CURL_CALLED="$marker" \
        BCM_LOCAL_STUB_BASE_URL="$malicious_url" \
        ./scripts/local.sh reset 2>&1
    )"
    status=$?
    set -e
    [ "$status" -ne 0 ] || {
        echo "안전하지 않은 reset URL이 허용됐습니다: $malicious_url" >&2
        exit 1
    }
    [ ! -e "$marker" ] || {
        echo "거부해야 할 reset URL로 curl을 호출했습니다: $malicious_url" >&2
        exit 1
    }
    case "$output" in
        *"loopback Stub URL만 허용"*) ;;
        *) echo "reset URL 거부 안내가 없습니다: $malicious_url" >&2; exit 1 ;;
    esac
done

printf '#!/usr/bin/env sh\nexit 1\n' > "$work_dir/bin/docker"
chmod +x "$work_dir/bin/docker"
pid_state_dir="$work_dir/pid-state"
mkdir -p "$pid_state_dir"
sleep 30 &
unrelated_pid=$!
printf '%s\n' "$unrelated_pid" > "$pid_state_dir/api.pid"
PATH="$work_dir/bin:$PATH" BCM_LOCAL_STATE_DIR="$pid_state_dir" ./scripts/local.sh down >/dev/null
kill -0 "$unrelated_pid" 2>/dev/null || {
    echo "재사용된 PID 파일이 무관한 프로세스를 종료했습니다." >&2
    exit 1
}
[ -f "$pid_state_dir/api.pid" ] || {
    echo "종료하지 않은 프로세스의 PID 추적을 삭제했습니다." >&2
    exit 1
}
kill "$unrelated_pid"
wait "$unrelated_pid" 2>/dev/null || true
unrelated_pid=""
unlink "$pid_state_dir/api.pid"

bash -c \
    'cd "$1"; exec -a "$1/gradle/wrapper/gradle-wrapper.jar --no-daemon :blockchain-manager-app:bcm-api:bootRun" sleep 30' \
    bash "$PWD" &
unrelated_pid=$!
printf '%s\n' "$unrelated_pid" > "$pid_state_dir/api.pid"
PATH="$work_dir/bin:$PATH" BCM_LOCAL_STATE_DIR="$pid_state_dir" ./scripts/local.sh down >/dev/null
if kill -0 "$unrelated_pid" 2>/dev/null; then
    echo "이전 실행기로 시작한 BCM 프로세스를 종료하지 못했습니다." >&2
    exit 1
fi
wait "$unrelated_pid" 2>/dev/null || true
unrelated_pid=""
[ ! -e "$pid_state_dir/api.pid" ] || {
    echo "종료한 이전 BCM 프로세스의 PID 파일이 남았습니다." >&2
    exit 1
}

printf '%s\n' '-1' > "$pid_state_dir/api.pid"
status_output="$(PATH="$work_dir/bin:$PATH" BCM_LOCAL_STATE_DIR="$pid_state_dir" ./scripts/local.sh status)"
case "$status_output" in
    *"Blockchain Manager API: STOPPED"*) ;;
    *) echo "안전하지 않은 PID 값을 실행 중으로 인정했습니다." >&2; exit 1 ;;
esac

echo "local script tests passed"
