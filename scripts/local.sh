#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
ENV_FILE="${BCM_LOCAL_ENV_FILE:-$REPO_ROOT/.env}"
STATE_DIR="${BCM_LOCAL_STATE_DIR:-$REPO_ROOT/build/local}"
ACTIVE_MODE_FILE="$STATE_DIR/active-mode"
COMPOSE_FILE="$REPO_ROOT/config/local-compose.yaml"
COMPOSE_PROJECT="${BCM_LOCAL_COMPOSE_PROJECT:-bcm-local}"
POSTGRES_PORT="${BCM_LOCAL_POSTGRES_PORT:-15432}"
KAFKA_PORT="${BCM_LOCAL_KAFKA_PORT:-9092}"
CHAIN_PROFILE="${BCM_LOCAL_CHAIN_PROFILE:-catalog}"
ANVIL_PORT="${BCM_LOCAL_ANVIL_PORT:-8545}"
ETHEREUM_ANVIL_PORT="${BCM_LOCAL_ETHEREUM_ANVIL_PORT:-38545}"
BASE_ANVIL_PORT="${BCM_LOCAL_BASE_ANVIL_PORT:-38546}"
STUB_PORT="${BCM_LOCAL_STUB_PORT:-18080}"
STUB_MANAGEMENT_PORT="${BCM_LOCAL_STUB_MANAGEMENT_PORT:-18090}"
API_PORT="${BCM_LOCAL_API_PORT:-38080}"
API_MANAGEMENT_PORT="${BCM_LOCAL_API_MANAGEMENT_PORT:-9090}"
WEBHOOK_PORT="${BCM_LOCAL_WEBHOOK_PORT:-38081}"
WEBHOOK_MANAGEMENT_PORT="${BCM_LOCAL_WEBHOOK_MANAGEMENT_PORT:-9091}"
ADMIN_PORT="${BCM_LOCAL_ADMIN_PORT:-9080}"
DEFAULT_BASE_URL="https://api.fireblocks.io"
DEFAULT_JWKS_URL="https://keys.fireblocks.io/.well-known/jwks.json"
DEFAULT_LOCAL_STUB_BASE_URL="http://127.0.0.1:$STUB_PORT"

usage() {
    cat <<'EOF'
Blockchain Manager 로컬 실행기

사용법:
  ./scripts/local.sh configure [fireblocks]
  ./scripts/local.sh up [fireblocks|stub]
  ./scripts/local.sh restart [fireblocks|stub]
  ./scripts/local.sh status
  ./scripts/local.sh sync assets
  ./scripts/local.sh stop [api|webhook|admin]
  ./scripts/local.sh test deposit
  ./scripts/local.sh logs [api|webhook|admin|chain|stub|infra]
  ./scripts/local.sh down
  ./scripts/local.sh reset
  ./scripts/local.sh purge

기본 모드는 fireblocks다. stub은 실 Fireblocks 자격증명 없이 결정적 로컬 체인을 사용한다.
EOF
}

fail() {
    echo "오류: $*" >&2
    exit 1
}

component_display_name() {
    case "$1" in
        chain) printf '%s' '로컬 블록체인 (Anvil)' ;;
        stub) printf '%s' 'Fireblocks 로컬 Stub' ;;
        api) printf '%s' 'Blockchain Manager API' ;;
        webhook) printf '%s' 'Blockchain Manager Webhook' ;;
        admin) printf '%s' 'Blockchain Manager Admin' ;;
        *) printf '%s' "$1" ;;
    esac
}

[[ "$COMPOSE_PROJECT" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]] ||
    fail "안전하지 않은 Docker Compose 프로젝트 이름입니다: $COMPOSE_PROJECT"
for local_port in \
    "$POSTGRES_PORT" "$KAFKA_PORT" "$ANVIL_PORT" "$ETHEREUM_ANVIL_PORT" "$BASE_ANVIL_PORT" "$STUB_PORT" \
    "$STUB_MANAGEMENT_PORT" "$API_PORT" "$API_MANAGEMENT_PORT" \
    "$WEBHOOK_PORT" "$WEBHOOK_MANAGEMENT_PORT" "$ADMIN_PORT"; do
    [[ "$local_port" =~ ^[0-9]{1,5}$ ]] && ((10#$local_port >= 1 && 10#$local_port <= 65535)) ||
        fail "안전하지 않은 로컬 포트입니다: $local_port"
done

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "$1 명령을 찾을 수 없습니다."
}

compose() {
    docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" "$@"
}

active_local_mode() {
    if [ -f "$ACTIVE_MODE_FILE" ]; then
        tr -d '\r\n' < "$ACTIVE_MODE_FILE"
    else
        printf '%s' 'fireblocks'
    fi
}

use_active_dataset() {
    local active_mode
    active_mode="$(active_local_mode)"
    case "$active_mode" in
        fireblocks|stub) export BCM_LOCAL_DATASET="$active_mode" ;;
        *) fail "알 수 없는 로컬 실행 모드가 기록되어 있습니다: $active_mode" ;;
    esac
}

select_local_mode() {
    local requested_mode="$1"
    local active_mode
    case "$requested_mode" in
        fireblocks|stub) ;;
        *) fail "실행 모드는 fireblocks 또는 stub이어야 합니다." ;;
    esac
    active_mode="$(active_local_mode)"
    if [ "$active_mode" != "$requested_mode" ] && command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        export BCM_LOCAL_DATASET="$active_mode"
        if [ -n "$(compose ps -q 2>/dev/null || true)" ]; then
            fail "$active_mode 모드의 PostgreSQL·Kafka가 남아 있습니다. down 후 $requested_mode 모드로 전환하세요."
        fi
    fi
    mkdir -p "$STATE_DIR"
    printf '%s\n' "$requested_mode" > "$ACTIVE_MODE_FILE"
    export BCM_LOCAL_DATASET="$requested_mode"
}

env_value() {
    local wanted="$1"
    [ -f "$ENV_FILE" ] || return 0
    while IFS='=' read -r key value || [ -n "$key" ]; do
        key="${key%$'\r'}"
        value="${value%$'\r'}"
        [ "$key" = "$wanted" ] && {
            printf '%s' "$value"
            return 0
        }
    done < "$ENV_FILE"
}

load_env() {
    [ -f "$ENV_FILE" ] || fail "$ENV_FILE 파일이 없습니다. configure를 먼저 실행하세요."
    while IFS='=' read -r key value || [ -n "$key" ]; do
        key="${key%$'\r'}"
        value="${value%$'\r'}"
        case "$key" in
            ''|'#'*) continue ;;
            SPRING_DATASOURCE_URL|SPRING_DATASOURCE_USERNAME|SPRING_DATASOURCE_PASSWORD|KAFKA_BOOTSTRAP_SERVERS|BCM_HTTP_MAX_CONNECTIONS|BCM_FIREBLOCKS_BASE_URL|BCM_FIREBLOCKS_API_KEY|BCM_FIREBLOCKS_PRIVATE_KEY_FILE|FIREBLOCKS_JWKS_URL|BCM_VENDOR_MODE|BCM_CHAIN_MODE)
                export "$key=$value"
                ;;
        esac
    done < "$ENV_FILE"
}

absolute_file() {
    local path="$1"
    local directory
    local filename
    directory="$(dirname "$path")"
    filename="$(basename "$path")"
    [ -d "$directory" ] || return 1
    directory="$(cd "$directory" && pwd -P)"
    printf '%s/%s' "$directory" "$filename"
}

prompt() {
    local label="$1"
    local default_value="$2"
    local answer
    if [ ! -t 0 ]; then
        printf '%s' "$default_value"
        return 0
    fi
    if [ -n "$default_value" ]; then
        read -e -r -p "$label [$default_value]: " answer
        printf '%s' "${answer:-$default_value}"
    else
        read -e -r -p "$label: " answer
        printf '%s' "$answer"
    fi
}

write_env() {
    local base_url="$1"
    local api_key="$2"
    local private_key_file="$3"
    local jwks_url="$4"
    local temporary="$ENV_FILE.tmp.$$"

    umask 077
    mkdir -p "$(dirname "$ENV_FILE")"
    {
        echo "# scripts/local.sh가 생성한 로컬 설정 — Git 커밋 금지"
        echo "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/bcm"
        echo "SPRING_DATASOURCE_USERNAME=postgres"
        echo "SPRING_DATASOURCE_PASSWORD=bcm"
        echo "KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:$KAFKA_PORT"
        echo "BCM_HTTP_MAX_CONNECTIONS=100"
        echo "BCM_FIREBLOCKS_BASE_URL=$base_url"
        echo "BCM_FIREBLOCKS_API_KEY=$api_key"
        echo "BCM_FIREBLOCKS_PRIVATE_KEY_FILE=$private_key_file"
        echo "FIREBLOCKS_JWKS_URL=$jwks_url"
        echo "BCM_VENDOR_MODE=FIREBLOCKS"
        echo "BCM_CHAIN_MODE=TESTNET"
    } > "$temporary"
    mv "$temporary" "$ENV_FILE"
    chmod 600 "$ENV_FILE"
}

configure_fireblocks() {
    local base_url api_key private_key_file jwks_url resolved_key
    local current_base_url current_jwks_url
    current_base_url="$(env_value BCM_FIREBLOCKS_BASE_URL || true)"
    current_jwks_url="$(env_value FIREBLOCKS_JWKS_URL || true)"
    base_url="$(prompt "Fireblocks Base URL" "${current_base_url:-$DEFAULT_BASE_URL}")"
    api_key="$(prompt "Fireblocks API Key" "$(env_value BCM_FIREBLOCKS_API_KEY || true)")"
    private_key_file="$(prompt "Fireblocks Private Key 파일 경로" "$(env_value BCM_FIREBLOCKS_PRIVATE_KEY_FILE || true)")"
    jwks_url="$(prompt "Webhook JWKS URL" "${current_jwks_url:-$DEFAULT_JWKS_URL}")"

    [ -n "$api_key" ] || fail "Fireblocks API Key가 필요합니다."
    [ -n "$private_key_file" ] || fail "Fireblocks Private Key 파일 경로가 필요합니다."
    resolved_key="$(absolute_file "$private_key_file")" || fail "Private Key 디렉터리를 찾을 수 없습니다: $private_key_file"
    [ -f "$resolved_key" ] || fail "Private Key 파일을 찾을 수 없습니다: $resolved_key"
    [ -r "$resolved_key" ] || fail "Private Key 파일을 읽을 수 없습니다: $resolved_key"

    write_env "$base_url" "$api_key" "$resolved_key" "$jwks_url"
    echo "로컬 설정을 생성했습니다: $ENV_FILE"
}

ensure_fireblocks_config() {
    if [ ! -f "$ENV_FILE" ]; then
        echo "$ENV_FILE 파일이 없어 초기 설정을 시작합니다."
        configure_fireblocks
    fi
    load_env
    if [ -z "${BCM_FIREBLOCKS_API_KEY:-}" ] || [ -z "${BCM_FIREBLOCKS_PRIVATE_KEY_FILE:-}" ]; then
        echo "Fireblocks 필수 설정이 없어 다시 설정합니다."
        configure_fireblocks
        load_env
    fi
    [ -r "$BCM_FIREBLOCKS_PRIVATE_KEY_FILE" ] || fail "Private Key 파일을 읽을 수 없습니다: $BCM_FIREBLOCKS_PRIVATE_KEY_FILE"
    case "${BCM_FIREBLOCKS_BASE_URL%/}" in
        https://api.fireblocks.io) export BCM_FIREBLOCKS_BASE_URL=https://api.fireblocks.io ;;
        *) fail "실 Fireblocks 자격증명은 공식 API 주소 https://api.fireblocks.io에만 전송할 수 있습니다." ;;
    esac
}

pid_file() {
    printf '%s/%s.pid' "$STATE_DIR" "$1"
}

pid_token_file() {
    printf '%s/%s.pid.token' "$STATE_DIR" "$1"
}

log_file() {
    printf '%s/%s.log' "$STATE_DIR" "$1"
}

valid_pid() {
    local pid="$1"
    [[ "$pid" =~ ^[0-9]+$ ]] && ((10#$pid > 1))
}

expected_gradle_task() {
    case "$1" in
        chain|stub) printf '%s' ':blockchain-manager-test-support:bootRun' ;;
        api) printf '%s' ':blockchain-manager-app:bcm-api:bootRun' ;;
        webhook) printf '%s' ':blockchain-manager-app:bcm-webhook:bootRun' ;;
        admin) printf '%s' ':blockchain-manager-app:bcm-admin:bootRun' ;;
        *) return 1 ;;
    esac
}

process_cwd() {
    local pid="$1"
    if [ -e "/proc/$pid/cwd" ]; then
        readlink "/proc/$pid/cwd" 2>/dev/null
    elif command -v lsof >/dev/null 2>&1; then
        lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
    else
        return 1
    fi
}

process_identity_matches() {
    local name="$1"
    local pid="$2"
    local command cwd task
    valid_pid "$pid" || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    command="$(ps -ww -p "$pid" -o command= 2>/dev/null)" || return 1
    cwd="$(process_cwd "$pid")" || return 1
    task="$(expected_gradle_task "$name")" || return 1
    [ "$cwd" = "$REPO_ROOT" ] || return 1
    [[ "$command" == *"$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar"* ]] || return 1
    [[ "$command" == *"$task"* ]] || return 1
    if [ "$name" = chain ]; then
        [[ "$command" == *'--args=chain'* || "$command" == *'--args=chain-cluster'* ]] || return 1
    fi
    if [ "$name" = stub ]; then
        [[ "$command" != *'--args=chain'* ]] || return 1
    fi
}

managed_process() {
    local name="$1"
    local file token_file pid token command
    file="$(pid_file "$name")"
    token_file="$(pid_token_file "$name")"
    [ -f "$file" ] && [ ! -L "$file" ] || return 1
    [ -f "$token_file" ] && [ ! -L "$token_file" ] || return 1
    pid="$(cat "$file")"
    token="$(cat "$token_file")"
    [[ "$token" =~ ^[A-Za-z0-9._-]+$ ]] || return 1
    process_identity_matches "$name" "$pid" || return 1
    command="$(ps -ww -p "$pid" -o command= 2>/dev/null)" || return 1
    [[ "$command" == *"-Dbcm.local.process.token=$token"* ]]
}

legacy_managed_process() {
    local name="$1"
    local file token_file pid
    file="$(pid_file "$name")"
    token_file="$(pid_token_file "$name")"
    [ -f "$file" ] && [ ! -L "$file" ] || return 1
    [ ! -e "$token_file" ] || return 1
    pid="$(cat "$file")"
    process_identity_matches "$name" "$pid"
}

running() {
    managed_process "$1" || legacy_managed_process "$1"
}

local_mode_runtime_state() {
    local mode="${1:-$(active_local_mode)}"
    case "$mode" in
        fireblocks)
            if running chain || running stub; then
                printf '%s' 'CONFLICT'
            elif running api && running webhook && running admin; then
                printf '%s' 'RUNNING'
            elif ! running api && ! running webhook && ! running admin; then
                printf '%s' 'STOPPED'
            else
                printf '%s' 'PARTIAL'
            fi
            ;;
        stub)
            if running chain && running stub && running api && running webhook && running admin; then
                printf '%s' 'RUNNING'
            elif ! running chain && ! running stub && ! running api && ! running webhook && ! running admin; then
                printf '%s' 'STOPPED'
            else
                printf '%s' 'PARTIAL'
            fi
            ;;
        *) fail "알 수 없는 로컬 실행 모드가 기록되어 있습니다: $mode" ;;
    esac
}

print_local_context() {
    local active_mode runtime_state
    active_mode="$(active_local_mode)"
    runtime_state="$(local_mode_runtime_state "$active_mode")"
    echo "현재 로컬 모드: $active_mode ($runtime_state)"
    echo "데이터셋: $active_mode"
}

# wrapper shell이 Java Gradle client로 exec 되기 전의 짧은 구간에는 command identity가 아직 완성되지 않는다.
# 이 함수는 readiness 대기 유예에만 쓰고, 종료 신호를 보낼 근거로는 사용하지 않는다.
starting_process_alive() {
    local file pid
    file="$(pid_file "$1")"
    [ -f "$file" ] && [ ! -L "$file" ] || return 1
    pid="$(cat "$file")"
    valid_pid "$pid" && kill -0 "$pid" 2>/dev/null
}

assert_port_free() {
    local port="$1"
    local owner="$2"
    if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        fail "$owner 포트 $port가 이미 사용 중입니다."
    fi
}

start_gradle_process() {
    local name="$1"
    local task="$2"
    local token
    shift 2
    if running "$name"; then
        echo "$(component_display_name "$name"): 이미 실행 중입니다."
        return 0
    fi
    mkdir -p "$STATE_DIR"
    token="$name-$$-$(date +%s)-$RANDOM$RANDOM"
    printf '%s\n' "$token" > "$(pid_token_file "$name")"
    echo "$(component_display_name "$name") 시작 중..."
    (
        cd "$REPO_ROOT"
        nohup python3 "$SCRIPT_DIR/internal/local-process-launcher.py" ./gradlew --no-daemon "-Dbcm.local.process.token=$token" "$task" "$@" > "$(log_file "$name")" 2>&1 < /dev/null &
        echo $! > "$(pid_file "$name")"
    )
}

wait_chain() {
    local attempt=0
    while [ "$attempt" -lt 120 ]; do
        if [ "$CHAIN_PROFILE" = legacy ]; then
            if chain_id_ready "$ANVIL_PORT" '0x7a69'; then
                echo "로컬 블록체인 (Anvil) 준비 완료"
                return 0
            fi
        elif chain_id_ready "$ETHEREUM_ANVIL_PORT" '0x7a69' && chain_id_ready "$BASE_ANVIL_PORT" '0x7a6a'; then
            echo "로컬 블록체인 (Ethereum·Base Anvil) 준비 완료"
            return 0
        fi
        if ! running chain && { [ "$attempt" -ge 10 ] || ! starting_process_alive chain; }; then
            tail -n 40 "$(log_file chain)" >&2 || true
            fail "로컬 블록체인 (Anvil) 프로세스가 기동 중 종료됐습니다."
        fi
        attempt=$((attempt + 1))
        sleep 1
    done
    tail -n 40 "$(log_file chain)" >&2 || true
    fail "로컬 블록체인 (Anvil) health check 시간이 초과됐습니다."
}

chain_id_ready() {
    local port="$1"
    local expected="$2"
    curl -fsS \
        -H 'Content-Type: application/json' \
        --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
        "http://127.0.0.1:$port" 2>/dev/null | grep -q "\"result\":\"$expected\""
}

prepare_local_api_key() {
    local key_file="$STATE_DIR/stub/fireblocks-api-private-key.pem"
    if [ ! -f "$key_file" ]; then
        umask 077
        mkdir -p "$(dirname "$key_file")"
        openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$key_file" >/dev/null 2>&1
        chmod 600 "$key_file"
    fi
    printf '%s' "$key_file"
}

wait_http() {
    local name="$1"
    local url="$2"
    local attempt=0
    while [ "$attempt" -lt 120 ]; do
        if curl -fsS "$url" >/dev/null 2>&1; then
            echo "$(component_display_name "$name") 준비 완료"
            return 0
        fi
        if ! running "$name" && { [ "$attempt" -ge 10 ] || ! starting_process_alive "$name"; }; then
            tail -n 40 "$(log_file "$name")" >&2 || true
            fail "$(component_display_name "$name") 프로세스가 기동 중 종료됐습니다."
        fi
        attempt=$((attempt + 1))
        sleep 1
    done
    tail -n 40 "$(log_file "$name")" >&2 || true
    fail "$(component_display_name "$name") health check 시간이 초과됐습니다: $url"
}

verify_fireblocks_api_authentication() {
    local preflight_log="$STATE_DIR/fireblocks-preflight.log"
    mkdir -p "$STATE_DIR"
    : > "$preflight_log"
    chmod 600 "$preflight_log"

    echo "Fireblocks API 인증 확인 중..."
    if ! (
        cd "$REPO_ROOT"
        unset BCM_FIREBLOCKS_PRIVATE_KEY_PEM
        export BCM_JOB=catalog-sync-once
        ./gradlew --no-daemon :blockchain-manager-app:bcm-bat:bootRun
    ) > "$preflight_log" 2>&1; then
        echo "상세 로그: $preflight_log" >&2
        fail "Fireblocks API 인증 또는 연결 확인에 실패했습니다. API Key, Private Key 파일, Base URL과 로컬 시각을 확인하세요."
    fi
    echo "Fireblocks API 인증 성공 — 블록체인 목록 읽기 완료"
}

bootstrap_fireblocks_asset_catalog() {
    local bootstrap_log="$STATE_DIR/fireblocks-asset-catalog-bootstrap.log"
    mkdir -p "$STATE_DIR"
    : > "$bootstrap_log"
    chmod 600 "$bootstrap_log"

    echo "Fireblocks 지원 네트워크·자산 카탈로그 준비 중..."
    if ! python3 "$SCRIPT_DIR/internal/local-deposit-test.py" --bootstrap-fireblocks-catalog > "$bootstrap_log" 2>&1; then
        echo "상세 로그: $bootstrap_log" >&2
        fail "Fireblocks 지원 네트워크·자산 카탈로그 준비에 실패했습니다. 로그에서 chainId와 API 응답을 확인하세요."
    fi
    echo "Fireblocks 지원 네트워크·자산 카탈로그 준비 완료 (Ethereum Sepolia·Base Sepolia)"
}

sync_asset_catalog_now() {
    require_command docker
    local active_mode
    active_mode="$(active_local_mode)"
    use_active_dataset
    running api || fail "자산 카탈로그 동기화는 실행 중인 로컬 환경이 필요합니다. up ${active_mode}를 먼저 실행하세요."
    docker info >/dev/null 2>&1 || fail "Docker가 실행 중이지 않습니다."
    compose ps --status running --services | grep -Fxq postgres || fail "PostgreSQL이 실행 중이지 않습니다."

    case "$active_mode" in
        fireblocks)
            ensure_fireblocks_config
            ;;
        stub)
            running stub || fail "Stub 모드 자산 카탈로그 동기화에는 Fireblocks 로컬 Stub이 필요합니다."
            export BCM_VENDOR_MODE=STUB
            export BCM_CHAIN_MODE=LOCAL
            export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/bcm"
            export SPRING_DATASOURCE_USERNAME=postgres
            export SPRING_DATASOURCE_PASSWORD=bcm
            export KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$KAFKA_PORT"
            export BCM_HTTP_MAX_CONNECTIONS=100
            export BCM_FIREBLOCKS_BASE_URL="http://127.0.0.1:$STUB_PORT"
            export BCM_FIREBLOCKS_API_KEY=bcm-local-stub
            export BCM_FIREBLOCKS_PRIVATE_KEY_FILE="$(prepare_local_api_key)"
            export FIREBLOCKS_JWKS_URL="http://127.0.0.1:$STUB_PORT/.well-known/jwks.json"
            unset BCM_FIREBLOCKS_PRIVATE_KEY_PEM
            ;;
        *) fail "알 수 없는 로컬 실행 모드가 기록되어 있습니다: $active_mode" ;;
    esac

    local sync_log="$STATE_DIR/asset-catalog-sync.log"
    : > "$sync_log"
    chmod 600 "$sync_log"
    echo "전체 Fireblocks 네트워크의 읽기 전용 자산 카탈로그 동기화 중..."
    if ! (
        cd "$REPO_ROOT"
        export BCM_JOB=asset-catalog-sync-once
        ./gradlew --no-daemon :blockchain-manager-app:bcm-bat:bootRun
    ) > "$sync_log" 2>&1; then
        echo "상세 로그: $sync_log" >&2
        fail "자산 카탈로그 동기화에 실패했습니다."
    fi
    echo "전체 자산 카탈로그 동기화 완료 — 미지원 후보는 읽기 전용으로 표시됩니다."
}

stop_tree() {
    local pid="$1"
    local child
    valid_pid "$pid" || return 0
    if command -v pgrep >/dev/null 2>&1; then
        for child in $(pgrep -P "$pid" 2>/dev/null || true); do
            valid_pid "$child" && stop_tree "$child"
        done
    fi
    kill "$pid" 2>/dev/null || true
}

stop_managed_group() {
    local pid="$1"
    local process_group
    process_group="$(ps -p "$pid" -o pgid= 2>/dev/null | tr -d ' ')"
    if valid_pid "$process_group" && [ "$process_group" = "$pid" ]; then
        kill -TERM "-$process_group" 2>/dev/null || true
        local attempt=0
        while kill -0 "-$process_group" 2>/dev/null && [ "$attempt" -lt 20 ]; do
            attempt=$((attempt + 1))
            sleep 0.25
        done
        kill -KILL "-$process_group" 2>/dev/null || true
    else
        stop_tree "$pid"
    fi
}

stop_process() {
    local name="$1"
    local file token_file
    local pid
    file="$(pid_file "$name")"
    token_file="$(pid_token_file "$name")"
    [ -f "$file" ] || return 0
    pid="$(cat "$file")"
    if managed_process "$name" || legacy_managed_process "$name"; then
        echo "$(component_display_name "$name") 종료 중..."
        stop_managed_group "$pid"
        local attempt=0
        while kill -0 "$pid" 2>/dev/null && [ "$attempt" -lt 20 ]; do
            attempt=$((attempt + 1))
            sleep 0.25
        done
        kill -9 "$pid" 2>/dev/null || true
    elif valid_pid "$pid" && kill -0 "$pid" 2>/dev/null; then
        echo "$(component_display_name "$name") PID 파일이 현재 프로세스와 일치하지 않아 종료 신호를 보내지 않습니다: $pid" >&2
        return 0
    fi
    unlink "$file" 2>/dev/null || true
    unlink "$token_file" 2>/dev/null || true
}

up_fireblocks() {
    require_command docker
    require_command curl
    ensure_fireblocks_config
    docker info >/dev/null 2>&1 || fail "Docker가 실행 중이지 않습니다."
    docker compose version >/dev/null 2>&1 || fail "Docker Compose v2가 필요합니다."
    if running chain || running stub; then
        fail "Stub 로컬 환경이 실행 중입니다. down 후 Fireblocks 모드로 전환하세요."
    fi

    select_local_mode fireblocks
    echo "로컬 실행 모드: fireblocks"
    echo "데이터셋: fireblocks"

    mkdir -p "$STATE_DIR"
    echo "PostgreSQL·Kafka 시작 중..."
    compose up -d --wait --wait-timeout 120
    verify_fireblocks_api_authentication

    if ! running api; then
        assert_port_free "$API_PORT" "BCM API"
        assert_port_free "$API_MANAGEMENT_PORT" "BCM management"
    fi
    export BCM_ADMIN_API_ACCESS_MODE=FUNCTION_TEST
    export BCM_DAW_INTEGRATION_ENABLED=true
    export BCM_API_PORT="$API_PORT"
    export BCM_MANAGEMENT_PORT="$API_MANAGEMENT_PORT"
    export BCM_ADMIN_PORT="$ADMIN_PORT"
    export BCM_ADMIN_TARGET_BASE_URL="http://127.0.0.1:$API_PORT"
    export BCM_ADMIN_WEBHOOK_MANAGEMENT_BASE_URL="http://127.0.0.1:$WEBHOOK_MANAGEMENT_PORT"
    mkdir -p "$REPO_ROOT/build/system-test"
    export BCM_ADMIN_SYSTEM_TEST_ENABLED=true
    export BCM_ADMIN_SYSTEM_TEST_STATE_DIRECTORY="${BCM_SYSTEM_TEST_ROOT:-$REPO_ROOT/build/system-test}"
    export BCM_ADMIN_LOCAL_SCENARIO_ENABLED=false
    export BCM_ADMIN_LOCAL_SCENARIO_REPOSITORY="$REPO_ROOT"
    export BCM_ADMIN_LOCAL_ASSET_MANAGEMENT_ENABLED=true

    start_gradle_process api ":blockchain-manager-app:bcm-api:bootRun"
    wait_http api "http://127.0.0.1:$API_MANAGEMENT_PORT/actuator/health"
    bootstrap_fireblocks_asset_catalog
    if ! running webhook; then
        assert_port_free "$WEBHOOK_PORT" "BCM Webhook"
        assert_port_free "$WEBHOOK_MANAGEMENT_PORT" "BCM Webhook management"
    fi
    export BCM_WEBHOOK_PORT="$WEBHOOK_PORT"
    export BCM_WEBHOOK_MANAGEMENT_PORT="$WEBHOOK_MANAGEMENT_PORT"
    start_gradle_process webhook ":blockchain-manager-app:bcm-webhook:bootRun"
    wait_http webhook "http://127.0.0.1:$WEBHOOK_MANAGEMENT_PORT/actuator/health"
    if ! running admin; then
        assert_port_free "$ADMIN_PORT" "BCM Admin"
    fi
    start_gradle_process admin ":blockchain-manager-app:bcm-admin:bootRun"
    wait_http admin "http://127.0.0.1:$ADMIN_PORT/actuator/health"
    [ "$(local_mode_runtime_state fireblocks)" = RUNNING ] ||
        fail "Fireblocks 모드 프로세스 구성이 완전하지 않습니다. status와 logs를 확인하세요."

    echo ""
    echo "Blockchain Manager 로컬 환경이 준비됐습니다."
    echo "Admin: http://127.0.0.1:$ADMIN_PORT/admin/dashboard"
    echo "BCM:   http://127.0.0.1:$API_PORT"
    echo "API 문서: http://127.0.0.1:$API_PORT/api-docs/"
    echo "Webhook: http://127.0.0.1:$WEBHOOK_PORT/webhook"
}

up_stub() {
    require_command docker
    require_command curl
    require_command anvil
    require_command forge
    require_command openssl
    docker info >/dev/null 2>&1 || fail "Docker가 실행 중이지 않습니다."
    docker compose version >/dev/null 2>&1 || fail "Docker Compose v2가 필요합니다."
    if { running api || running webhook || running admin; } && { ! running chain || ! running stub; }; then
        fail "Fireblocks 로컬 환경이 실행 중입니다. down 후 Stub 모드로 전환하세요."
    fi

    select_local_mode stub
    echo "로컬 실행 모드: stub"
    echo "데이터셋: stub"
    export BCM_LOCAL_CHAIN_PROFILE="$CHAIN_PROFILE"

    mkdir -p "$STATE_DIR/stub"
    echo "PostgreSQL·Kafka 시작 중..."
    compose up -d --wait --wait-timeout 120
    echo "로컬 컨트랙트 준비 중..."
    (cd "$REPO_ROOT" && ./gradlew --no-daemon :blockchain-manager-test-support:compileLocalContracts >/dev/null)

    case "$CHAIN_PROFILE" in
        legacy)
            if ! running chain; then
                assert_port_free "$ANVIL_PORT" "Anvil"
            fi
            ;;
        catalog)
            if ! running chain; then
                assert_port_free "$ETHEREUM_ANVIL_PORT" "Ethereum Anvil"
                assert_port_free "$BASE_ANVIL_PORT" "Base Anvil"
            fi
            ;;
        *) fail "BCM_LOCAL_CHAIN_PROFILE은 catalog 또는 legacy여야 합니다." ;;
    esac
    export BCM_LOCAL_CHAIN_SEED_FILE="$STATE_DIR/stub/chain.seed"
    export BCM_LOCAL_CHAIN_RUNTIME_DIR="$STATE_DIR/stub/chain"
    export BCM_LOCAL_CONTRACT_ARTIFACT_DIR="$REPO_ROOT/blockchain-manager-test-support/build/contracts"
    export BCM_LOCAL_ANVIL_BINARY="$(command -v anvil)"
    if [ "$CHAIN_PROFILE" = legacy ]; then
        export BCM_LOCAL_ANVIL_PORT="$ANVIL_PORT"
        start_gradle_process chain ":blockchain-manager-test-support:bootRun" "--args=chain"
    else
        export BCM_LOCAL_ETHEREUM_ANVIL_PORT="$ETHEREUM_ANVIL_PORT"
        export BCM_LOCAL_BASE_ANVIL_PORT="$BASE_ANVIL_PORT"
        start_gradle_process chain ":blockchain-manager-test-support:bootRun" "--args=chain-cluster"
    fi
    wait_chain

    if ! running stub; then
        assert_port_free "$STUB_PORT" "Fireblocks Stub"
        assert_port_free "$STUB_MANAGEMENT_PORT" "Stub management"
    fi
    export BCM_VENDOR_MODE=STUB
    export BCM_CHAIN_MODE=LOCAL
    export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/bcm"
    export SPRING_DATASOURCE_USERNAME=postgres
    export SPRING_DATASOURCE_PASSWORD=bcm
    export KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$KAFKA_PORT"
    export BCM_HTTP_MAX_CONNECTIONS=100
    export BCM_STUB_ADDRESS=127.0.0.1
    export BCM_STUB_PORT="$STUB_PORT"
    export BCM_STUB_MANAGEMENT_ADDRESS=127.0.0.1
    export BCM_STUB_MANAGEMENT_PORT="$STUB_MANAGEMENT_PORT"
    export BCM_FIREBLOCKS_BASE_URL="http://127.0.0.1:$STUB_PORT"
    export BCM_FIREBLOCKS_API_KEY=bcm-local-stub
    export FIREBLOCKS_JWKS_URL="http://127.0.0.1:$STUB_PORT/.well-known/jwks.json"
    if [ "$CHAIN_PROFILE" = legacy ]; then
        export BCM_EVM_RPC_URL="http://127.0.0.1:$ANVIL_PORT"
        export BCM_LOCAL_CHAIN_MANIFEST_FILE="$STATE_DIR/stub/chain/manifest.json"
        export BCM_LOCAL_CHAIN_KEY_FILE="$STATE_DIR/stub/chain/evm-keys.json"
        unset BCM_LOCAL_CHAIN_CLUSTER_MANIFEST_FILE
    else
        export BCM_EVM_RPC_URL="http://127.0.0.1:$ETHEREUM_ANVIL_PORT"
        export BCM_LOCAL_CHAIN_MANIFEST_FILE="$STATE_DIR/stub/chain/ethereum/manifest.json"
        export BCM_LOCAL_CHAIN_KEY_FILE="$STATE_DIR/stub/chain/ethereum/evm-keys.json"
        export BCM_LOCAL_CHAIN_CLUSTER_MANIFEST_FILE="$STATE_DIR/stub/chain/manifest.json"
    fi
    export BCM_EVM_CHAIN_ID=31337
    export BCM_FINALITY_CONFIRMATIONS_LOCAL=2
    export BCM_STUB_WEBHOOK_DELIVERY_URL="http://127.0.0.1:$WEBHOOK_PORT/webhook"
    export BCM_STUB_RESET_ENABLED=true
    unset BCM_FIREBLOCKS_PRIVATE_KEY_PEM BCM_FIREBLOCKS_PRIVATE_KEY_FILE BCM_LOCAL_TEST_PRIVATE_KEY_SHA256
    start_gradle_process stub ":blockchain-manager-test-support:bootRun"
    wait_http stub "http://127.0.0.1:$STUB_MANAGEMENT_PORT/actuator/health"

    export BCM_FIREBLOCKS_PRIVATE_KEY_FILE="$(prepare_local_api_key)"
    if ! running api; then
        assert_port_free "$API_PORT" "BCM API"
        assert_port_free "$API_MANAGEMENT_PORT" "BCM management"
    fi
    export BCM_ADMIN_API_ACCESS_MODE=FUNCTION_TEST
    export BCM_DAW_INTEGRATION_ENABLED=true
    export BCM_API_PORT="$API_PORT"
    export BCM_MANAGEMENT_PORT="$API_MANAGEMENT_PORT"
    export BCM_ADMIN_PORT="$ADMIN_PORT"
    export BCM_ADMIN_TARGET_BASE_URL="http://127.0.0.1:$API_PORT"
    export BCM_ADMIN_WEBHOOK_MANAGEMENT_BASE_URL="http://127.0.0.1:$WEBHOOK_MANAGEMENT_PORT"
    mkdir -p "$REPO_ROOT/build/system-test"
    export BCM_ADMIN_SYSTEM_TEST_ENABLED=true
    export BCM_ADMIN_SYSTEM_TEST_STATE_DIRECTORY="${BCM_SYSTEM_TEST_ROOT:-$REPO_ROOT/build/system-test}"
    export BCM_ADMIN_LOCAL_SCENARIO_ENABLED=true
    export BCM_ADMIN_LOCAL_SCENARIO_REPOSITORY="$REPO_ROOT"
    export BCM_ADMIN_LOCAL_ASSET_MANAGEMENT_ENABLED=true
    start_gradle_process api ":blockchain-manager-app:bcm-api:bootRun"
    wait_http api "http://127.0.0.1:$API_MANAGEMENT_PORT/actuator/health"
    if ! running webhook; then
        assert_port_free "$WEBHOOK_PORT" "BCM Webhook"
        assert_port_free "$WEBHOOK_MANAGEMENT_PORT" "BCM Webhook management"
    fi
    export BCM_WEBHOOK_PORT="$WEBHOOK_PORT"
    export BCM_WEBHOOK_MANAGEMENT_PORT="$WEBHOOK_MANAGEMENT_PORT"
    start_gradle_process webhook ":blockchain-manager-app:bcm-webhook:bootRun"
    wait_http webhook "http://127.0.0.1:$WEBHOOK_MANAGEMENT_PORT/actuator/health"
    if ! running admin; then
        assert_port_free "$ADMIN_PORT" "BCM Admin"
    fi
    start_gradle_process admin ":blockchain-manager-app:bcm-admin:bootRun"
    wait_http admin "http://127.0.0.1:$ADMIN_PORT/actuator/health"
    [ "$(local_mode_runtime_state stub)" = RUNNING ] ||
        fail "Stub 모드 프로세스 구성이 완전하지 않습니다. status와 logs를 확인하세요."

    if [ "$CHAIN_PROFILE" = catalog ]; then
        echo "기본 네트워크·자산 준비 중..."
        python3 "$SCRIPT_DIR/internal/local-deposit-test.py" --bootstrap-catalog
        echo "기본 네트워크·자산 준비 완료 (ETHEREUM·BASE / USDC·KRWK)"
    fi

    echo ""
    echo "Blockchain Manager + Fireblocks 로컬 Stub 환경이 준비됐습니다."
    echo "Admin: http://127.0.0.1:$ADMIN_PORT/admin/dashboard"
    echo "BCM:   http://127.0.0.1:$API_PORT"
    echo "API 문서: http://127.0.0.1:$API_PORT/api-docs/"
    echo "Webhook: http://127.0.0.1:$WEBHOOK_PORT/webhook"
    echo "Stub:  http://127.0.0.1:$STUB_PORT"
}

down_all() {
    use_active_dataset
    stop_process admin
    stop_process webhook
    stop_process api
    stop_process stub
    stop_process chain
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        compose down --remove-orphans
    fi
    echo "로컬 환경을 종료했습니다. PostgreSQL·Kafka 데이터는 보존됩니다."
}

restart_local_environment() {
    local mode="${1:-$(active_local_mode)}"
    case "$mode" in
        fireblocks|stub) ;;
        *) fail "재시작 모드는 fireblocks 또는 stub이어야 합니다." ;;
    esac

    down_all
    case "$mode" in
        fireblocks) up_fireblocks ;;
        stub) up_stub ;;
    esac
}

status_all() {
    local name
    use_active_dataset
    print_local_context
    for name in chain stub api webhook admin; do
        if running "$name"; then
            echo "$(component_display_name "$name"): RUNNING (pid $(cat "$(pid_file "$name")"))"
        else
            echo "$(component_display_name "$name"): STOPPED"
        fi
    done
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        compose ps
    else
        echo "docker: STOPPED"
    fi
}

logs() {
    local target="${1:-api}"
    use_active_dataset
    print_local_context
    case "$target" in
        chain|stub|api|webhook|admin)
            [ -f "$(log_file "$target")" ] || fail "$target 로그가 없습니다."
            if running "$target"; then
                echo "로그 대상: $(component_display_name "$target") (RUNNING)"
            else
                echo "로그 대상: $(component_display_name "$target") (STOPPED, 이전 로그일 수 있음)"
            fi
            tail -n 100 -f "$(log_file "$target")"
            ;;
        infra)
            require_command docker
            echo "로그 대상: PostgreSQL·Kafka"
            compose logs -f --tail 100
            ;;
        *) fail "로그 대상은 chain, stub, api, webhook, admin, infra 중 하나여야 합니다." ;;
    esac
}

purge_local_infra() {
    [ -t 0 ] || fail "purge는 대화형 터미널에서만 실행할 수 있습니다."
    local answer active_mode
    use_active_dataset
    active_mode="$(active_local_mode)"
    read -e -r -p "현재 선택된 $active_mode 모드의 PostgreSQL·Kafka 데이터를 모두 삭제할까요? [y/N]: " answer
    case "$answer" in
        y|Y|yes|YES) ;;
        *) echo "취소했습니다."; return 0 ;;
    esac
    stop_process admin
    stop_process webhook
    stop_process api
    stop_process stub
    stop_process chain
    require_command docker
    compose down --volumes --remove-orphans
    echo "$active_mode 모드의 로컬 PostgreSQL·Kafka 데이터를 초기화했습니다. 다른 모드와 .env는 보존됩니다."
}

reset_local_environment() {
    require_command curl
    local base_url="${BCM_LOCAL_STUB_BASE_URL:-$DEFAULT_LOCAL_STUB_BASE_URL}"
    local loopback_url_pattern='^http://(127\.0\.0\.1|localhost|\[::1\]):([0-9]{1,5})/?$'
    [[ "$base_url" =~ $loopback_url_pattern ]] || fail "reset 대상은 loopback Stub URL만 허용됩니다: $base_url"
    local port_number=$((10#${BASH_REMATCH[2]}))
    ((port_number >= 1 && port_number <= 65535)) || fail "reset 대상은 loopback Stub URL만 허용됩니다: $base_url"
    base_url="${base_url%/}"
    echo "Stub 상태와 Anvil snapshot만 초기화합니다. BCM DB·Kafka는 reset 대상이 아닙니다."
    local response
    response="$(
        curl --fail --silent --show-error \
            --connect-timeout 2 \
            --max-time 30 \
            --request POST \
            "$base_url/__stub/reset"
    )" || fail "로컬 Stub reset 요청에 실패했습니다: $base_url"
    printf '%s\n' "$response"
}

test_local_deposit() {
    require_command python3
    require_command docker
    local name
    for name in chain stub api webhook admin; do
        running "$name" || fail "test deposit은 실행 중인 Stub 로컬 환경이 필요합니다: $(component_display_name "$name") STOPPED"
    done
    docker info >/dev/null 2>&1 || fail "Docker가 실행 중이지 않습니다."
    compose ps --status running --services | grep -Fxq postgres || fail "PostgreSQL이 실행 중이지 않습니다."
    compose ps --status running --services | grep -Fxq kafka || fail "Kafka가 실행 중이지 않습니다."

    export BCM_VENDOR_MODE=STUB
    export BCM_CHAIN_MODE=LOCAL
    export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/bcm"
    export SPRING_DATASOURCE_USERNAME=postgres
    export SPRING_DATASOURCE_PASSWORD=bcm
    export KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$KAFKA_PORT"
    export BCM_HTTP_MAX_CONNECTIONS=100
    export BCM_FIREBLOCKS_BASE_URL="http://127.0.0.1:$STUB_PORT"
    export BCM_FIREBLOCKS_API_KEY=bcm-local-stub
    export BCM_FIREBLOCKS_PRIVATE_KEY_FILE="$(prepare_local_api_key)"
    export FIREBLOCKS_JWKS_URL="http://127.0.0.1:$STUB_PORT/.well-known/jwks.json"
    export BCM_LOCAL_API_PORT="$API_PORT"
    export BCM_LOCAL_ADMIN_PORT="$ADMIN_PORT"
    export BCM_LOCAL_STUB_PORT="$STUB_PORT"
    export BCM_LOCAL_KAFKA_PORT="$KAFKA_PORT"
    unset BCM_FIREBLOCKS_PRIVATE_KEY_PEM

    python3 "$SCRIPT_DIR/internal/local-deposit-test.py"
}

command_name="${1:-help}"
shift || true

case "$command_name" in
    configure)
        mode="${1:-fireblocks}"
        case "$mode" in
            fireblocks) configure_fireblocks ;;
            stub) echo "stub은 별도 자격증명 설정 없이 up 시 런타임 설정을 만듭니다." ;;
            *) fail "설정 모드는 fireblocks 또는 stub이어야 합니다." ;;
        esac
        ;;
    up)
        mode="${1:-fireblocks}"
        case "$mode" in
            fireblocks) up_fireblocks ;;
            stub) up_stub ;;
            *) fail "실행 모드는 fireblocks 또는 stub이어야 합니다." ;;
        esac
        ;;
    restart) restart_local_environment "${1:-}" ;;
    status) status_all ;;
    sync)
        case "${1:-}" in
            assets) sync_asset_catalog_now ;;
            *) fail "동기화 대상은 assets만 지원합니다." ;;
        esac
        ;;
    stop)
        case "${1:-}" in
            api|webhook|admin) stop_process "$1" ;;
            *) fail "개별 종료 대상은 api, webhook, admin 중 하나여야 합니다." ;;
        esac
        ;;
    test)
        case "${1:-}" in
            deposit) test_local_deposit ;;
            *) fail "테스트 대상은 deposit만 지원합니다." ;;
        esac
        ;;
    logs) logs "${1:-api}" ;;
    down) down_all ;;
    purge) purge_local_infra ;;
    reset) reset_local_environment ;;
    help|-h|--help) usage ;;
    *) usage >&2; exit 1 ;;
esac
