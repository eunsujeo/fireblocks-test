#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/../.."

work_dir="$(mktemp -d)"
hold_pid=""
cleanup() {
    if [ -n "$hold_pid" ] && kill -0 "$hold_pid" 2>/dev/null; then
        kill "$hold_pid" 2>/dev/null || true
        wait "$hold_pid" 2>/dev/null || true
    fi
    rm -rf "$work_dir"
}
trap cleanup EXIT

run_root="$work_dir/runs"
run_scenario() {
    local run_id="$1"
    local scenario="$2"
    shift 2
    BCM_SYSTEM_TEST_ROOT="$run_root" \
        BCM_SYSTEM_TEST_RUN_ID="$run_id" \
        BCM_SYSTEM_TEST_INTERNAL_TESTING=true \
        BCM_SYSTEM_TEST_INTERNAL_SCENARIO="$scenario" \
        "$@" ./scripts/system-test.sh smoke
}

help_output="$(./scripts/system-test.sh help)"
for command in 'smoke' 'full' 'status [runId]' 'logs [runId] [component]' 'stop [runId]'; do
    case "$help_output" in
        *"$command"*) ;;
        *) echo "system-test 도움말에 명령이 없습니다: $command" >&2; exit 1 ;;
    esac
done
case "$help_output" in
    *'T12.3'*|*'순차 활성화'*)
        echo "완료된 smoke가 아직 미구현으로 안내됩니다." >&2
        exit 1
        ;;
esac
if grep -q 'SUITE_NOT_IMPLEMENTED' scripts/internal/system-test-runner.py; then
    echo "smoke 실행기가 아직 미구현으로 닫혀 있습니다." >&2
    exit 1
fi
if grep -q 'FULL_SCENARIOS_PENDING' scripts/internal/system-test-runner.py; then
    echo "full 실행기가 아직 미구현으로 닫혀 있습니다." >&2
    exit 1
fi
for contract in \
    'postgres' \
    'kafka' \
    'anvil' \
    'fireblocks-stub' \
    'bcm-api' \
    'bcm-webhook' \
    'bcm-bat' \
    'bcm-admin' \
    'catalog-sync' \
    'deposit-finalized' \
    'kafka-event' \
    'admin-investigation'; do
    grep -q "$contract" scripts/internal/system-test-runner.py || {
        echo "smoke 세로줄 계약이 없습니다: $contract" >&2
        exit 1
    }
done
for contract in \
    'FULL_STEP_COUNT' \
    'cleanup-components' \
    'COMPONENT_CLEANUP_FAILED' \
    'postgres_database_digest' \
    'gasless-withdrawal' \
    'rate-limit-recovery' \
    'timeout-recovery' \
    'response-loss-recovery' \
    'webhook-duplicate' \
    'webhook-recovery' \
    'webhook-out-of-order' \
    'daw-sweep-event-completion' \
    'sweep-events' \
    'complete_daw_event' \
    'reset-isolation'; do
    grep -q "$contract" scripts/internal/system-test-runner.py || {
        echo "full 실패·복구 계약이 없습니다: $contract" >&2
        exit 1
    }
done
grep -q 'sweep-execution-once' scripts/internal/system-test-runner.py || {
    echo "full 실행기에 실제 sweep 제출 one-shot이 없습니다." >&2
    exit 1
}
if grep -q ':blockchain-manager-app:bcm-bat:test' scripts/internal/system-test-runner.py; then
    echo "full sweep 검증이 현재 stack 대신 별도 Gradle 통합테스트를 실행합니다." >&2
    exit 1
fi
if grep -q 'set_related_id("jobRunId", f' scripts/internal/system-test-runner.py; then
    echo "실제로 생성되지 않은 합성 jobRunId가 실행 원장에 기록됩니다." >&2
    exit 1
fi
python3 ./scripts/tests/system-test-runner-unit-test.py
for dedicated_port in 25432 29092 28545 28080 28090 28081 29090 28082 29091 29080; do
    grep -q "$dedicated_port" scripts/internal/system-test-runner.py || {
        echo "시스템 테스트 전용 포트가 없습니다: $dedicated_port" >&2
        exit 1
    }
done

pass_output="$(run_scenario run-pass PASS env BCM_FIREBLOCKS_PRIVATE_KEY='do-not-record-this-secret')"
case "$pass_output" in
    *'[1/2] 실행 원장 초기화 OK'*'[2/2] 테스트 시나리오 PASS OK'*) ;;
    *) echo "정상 시나리오 진행 출력이 올바르지 않습니다." >&2; exit 1 ;;
esac

python3 - "$run_root/run-pass/run.json" "$run_root/run-pass/events.jsonl" <<'PY'
import json
import pathlib
import sys

snapshot_path = pathlib.Path(sys.argv[1])
events_path = pathlib.Path(sys.argv[2])
snapshot = json.loads(snapshot_path.read_text())
assert snapshot["schemaVersion"] == 1
assert snapshot["runId"] == "run-pass"
assert snapshot["suite"] == "SMOKE"
assert snapshot["state"] == "PASSED"
assert snapshot["progress"]["completedSteps"] == 2
assert snapshot["progress"]["totalSteps"] == 2
assert snapshot["progress"]["percent"] == 100
assert snapshot["currentStep"] is None
assert snapshot["lastSuccessfulStep"] == "internal-scenario"
assert snapshot["startedAt"].endswith("Z")
assert snapshot["updatedAt"].endswith("Z")
assert snapshot["completedAt"].endswith("Z")
assert snapshot["classification"] == ["REAL_LOCAL", "SIMULATED_VENDOR"]
events = [json.loads(line) for line in events_path.read_text().splitlines()]
assert len(events) >= 6
assert events[0]["sequence"] == 1
assert [event["sequence"] for event in events] == list(range(1, len(events) + 1))
assert events[-1]["runState"] == "PASSED"
PY

if grep -R -F 'do-not-record-this-secret' "$run_root/run-pass" >/dev/null; then
    echo "실행 artifact에 Secret이 기록됐습니다." >&2
    exit 1
fi

status_output="$(BCM_SYSTEM_TEST_ROOT="$run_root" ./scripts/system-test.sh status run-pass)"
case "$status_output" in
    *'run-pass'*'PASSED'*'100%'*) ;;
    *) echo "완료 상태 출력이 올바르지 않습니다." >&2; exit 1 ;;
esac

logs_output="$(BCM_SYSTEM_TEST_ROOT="$run_root" ./scripts/system-test.sh logs run-pass runner)"
case "$logs_output" in
    *'scenario=PASS'*) ;;
    *) echo "component 로그를 조회하지 못했습니다." >&2; exit 1 ;;
esac

set +e
fail_output="$(run_scenario run-fail FAIL env 2>&1)"
fail_status=$?
set -e
[ "$fail_status" -ne 0 ] || {
    echo "실패 시나리오가 성공으로 끝났습니다." >&2
    exit 1
}
for expected in \
    '[2/2] 테스트 시나리오 FAIL FAILED' \
    'runId: run-fail' \
    '실패 단계: internal-scenario' \
    'Admin: http://127.0.0.1:9080/admin/test-runs/run-fail' \
    'artifact:' \
    '다음 조치:'; do
    case "$fail_output" in
        *"$expected"*) ;;
        *) echo "실패 진단 출력이 없습니다: $expected" >&2; exit 1 ;;
    esac
done

python3 - "$run_root/run-fail/run.json" <<'PY'
import json
import pathlib
import sys

snapshot = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert snapshot["state"] == "FAILED"
assert snapshot["failure"]["code"] == "INTERNAL_TEST_FAILURE"
assert snapshot["failure"]["failedStep"] == "internal-scenario"
assert snapshot["failure"]["retryable"] is True
assert snapshot["failure"]["nextAction"]
assert snapshot["progress"]["percent"] == 50
PY

python3 - "$run_root/run-fail/run.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
snapshot = json.loads(path.read_text())
snapshot["components"] = [
    {"name": "postgres", "state": "UP", "observedAt": "2026-08-20T00:00:00.000Z"},
    {"name": "bcm-api", "state": "UP", "observedAt": "2026-08-20T00:00:00.000Z"},
]
snapshot["retainedEnvironment"] = True
path.write_text(json.dumps(snapshot))
PY
retained_stop_output="$(
    BCM_SYSTEM_TEST_ROOT="$run_root" \
        BCM_SYSTEM_TEST_INTERNAL_TESTING=true \
        ./scripts/system-test.sh stop run-fail
)"
case "$retained_stop_output" in
    *'run-fail 보존 component를 정리했습니다.'*) ;;
    *) echo "실패 뒤 보존 component 정리 출력이 올바르지 않습니다." >&2; exit 1 ;;
esac
python3 - "$run_root/run-fail/run.json" <<'PY'
import json
import pathlib
import sys

snapshot = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert {component["state"] for component in snapshot["components"]} == {"DOWN"}
PY

set +e
BCM_SYSTEM_TEST_ROOT="$run_root" ./scripts/system-test.sh status '../escape' >/dev/null 2>&1
invalid_run_status=$?
BCM_SYSTEM_TEST_ROOT="$run_root" ./scripts/system-test.sh logs run-pass '../runner' >/dev/null 2>&1
invalid_component_status=$?
set -e
[ "$invalid_run_status" -ne 0 ] || {
    echo "안전하지 않은 runId가 허용됐습니다." >&2
    exit 1
}
[ "$invalid_component_status" -ne 0 ] || {
    echo "안전하지 않은 component가 허용됐습니다." >&2
    exit 1
}

hold_output="$work_dir/hold-output"
run_scenario run-hold HOLD env >"$hold_output" 2>&1 &
hold_pid=$!
for _ in $(seq 1 100); do
    if [ -f "$run_root/run-hold/run.json" ] && \
        grep -q '"state": "RUNNING"' "$run_root/run-hold/run.json"; then
        break
    fi
    sleep 0.05
done
[ -f "$run_root/run-hold/run.json" ] || {
    echo "중단 테스트 실행 원장이 생성되지 않았습니다." >&2
    exit 1
}
stop_output="$(BCM_SYSTEM_TEST_ROOT="$run_root" ./scripts/system-test.sh stop run-hold)"
case "$stop_output" in
    *'run-hold 중단을 요청했습니다.'*) ;;
    *) echo "중단 요청 출력이 올바르지 않습니다." >&2; exit 1 ;;
esac
set +e
wait "$hold_pid"
hold_status=$?
set -e
hold_pid=""
[ "$hold_status" -ne 0 ] || {
    echo "중단된 실행이 성공으로 끝났습니다." >&2
    exit 1
}
python3 - "$run_root/run-hold/run.json" <<'PY'
import json
import pathlib
import sys

snapshot = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert snapshot["state"] == "ABORTED"
assert snapshot["failure"]["code"] == "STOP_REQUESTED"
PY

retention_root="$work_dir/retention"
for index in $(seq -w 1 22); do
    BCM_SYSTEM_TEST_ROOT="$retention_root" \
        BCM_SYSTEM_TEST_RUN_ID="retention-$index" \
        BCM_SYSTEM_TEST_INTERNAL_TESTING=true \
        BCM_SYSTEM_TEST_INTERNAL_SCENARIO=PASS \
        ./scripts/system-test.sh smoke >/dev/null
done
retained_count="$(find "$retention_root" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
[ "$retained_count" -eq 20 ] || {
    echo "완료 실행 보존 개수가 20건이 아닙니다: $retained_count" >&2
    exit 1
}
[ ! -d "$retention_root/retention-01" ] && [ -d "$retention_root/retention-22" ] || {
    echo "최근 실행 보존 순서가 올바르지 않습니다." >&2
    exit 1
}

echo "system test runner tests passed"
