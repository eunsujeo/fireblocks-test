#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/.."

bash -n scripts/local.sh

if grep -q 'read -r -p' scripts/local.sh || ! grep -q 'read -e -r -p' scripts/local.sh; then
    echo "대화형 입력에 readline 편집이 적용되지 않았습니다." >&2
    exit 1
fi

help_output="$(./scripts/local.sh help)"
case "$help_output" in
    *"up [fireblocks|stub]"*) ;;
    *) echo "local.sh 도움말에 실행 모드가 없습니다." >&2; exit 1 ;;
esac

if grep -q 'stub.*T11\.3.*구현 뒤' scripts/local.sh; then
    echo "완료된 Stub 모드가 아직 미구현으로 안내됩니다." >&2
    exit 1
fi

for contract in \
    'up_stub()' \
    'start_gradle_process chain' \
    'start_gradle_process stub' \
    'BCM_STUB_WEBHOOK_DELIVERY_URL=http://127.0.0.1:8080/webhook' \
    'BCM_STUB_RESET_ENABLED=true' \
    'BCM_FIREBLOCKS_API_KEY=bcm-local-stub'; do
    grep -q "$contract" scripts/local.sh || {
        echo "Stub 로컬 조립 계약이 없습니다: $contract" >&2
        exit 1
    }
done

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
    *"api: STOPPED"*) ;;
    *) echo "안전하지 않은 PID 값을 실행 중으로 인정했습니다." >&2; exit 1 ;;
esac

echo "local script tests passed"
