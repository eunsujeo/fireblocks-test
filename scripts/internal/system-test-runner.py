#!/usr/bin/env python3

from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import secrets
import shutil
import signal
import socket
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Callable


RUN_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
COMPONENT_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
TERMINAL_STATES = {"PASSED", "FAILED", "ABORTED"}
ACTIVE_STATES = {"PENDING", "RUNNING"}
DEFAULT_ADMIN_BASE_URL = "http://127.0.0.1:9080/admin/test-runs"
MAX_RETAINED_RUNS = 20
STOP_REQUEST_FILE = ".stop-requested"
INTERNAL_SCENARIOS = {"PASS", "FAIL", "HOLD"}
LOCAL_CLASSIFICATIONS = {"REAL_LOCAL", "SIMULATED_VENDOR"}
LOCAL_NETWORK = "LOCAL"
LOCAL_SYMBOL = "TUSD"
LOCAL_VENDOR_ASSET_ID = "TUSD_LOCAL"
LONG_LIVED_COMPONENTS = (
    "postgres",
    "kafka",
    "anvil",
    "fireblocks-stub",
    "bcm-api",
    "bcm-webhook",
    "bcm-admin",
)
SMOKE_COMPONENTS = (*LONG_LIVED_COMPONENTS, "bcm-bat")
SMOKE_STEP_COUNT = 10
FULL_STEP_COUNT = 18
HTTP_TIMEOUT_SECONDS = 10
SMOKE_POSTGRES_PORT = 25432
SMOKE_KAFKA_PORT = 29092
SMOKE_ANVIL_PORT = 28545
SMOKE_STUB_PORT = 28080
SMOKE_STUB_MANAGEMENT_PORT = 28090
SMOKE_API_PORT = 28081
SMOKE_API_MANAGEMENT_PORT = 29090
SMOKE_WEBHOOK_PORT = 28082
SMOKE_WEBHOOK_MANAGEMENT_PORT = 29091
SMOKE_ADMIN_PORT = 29080


class RunnerError(Exception):
    pass


@dataclass
class StepFailure(Exception):
    code: str
    message: str
    next_action: str
    retryable: bool


@dataclass
class StepAborted(Exception):
    code: str = "STOP_REQUESTED"
    message: str = "사용자가 테스트 실행 중단을 요청했습니다."
    next_action: str = "component 정리 상태를 확인한 뒤 새 run으로 다시 실행하세요."
    retryable: bool = True


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def elapsed_seconds(started: float) -> str:
    return f"{time.monotonic() - started:.1f}s"


def validate_identifier(value: str, pattern: re.Pattern[str], label: str) -> str:
    if not pattern.fullmatch(value):
        raise RunnerError(f"오류: 안전하지 않은 {label}입니다: {value!r}")
    return value


def repository_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent


def artifact_root() -> Path:
    configured = os.environ.get("BCM_SYSTEM_TEST_ROOT")
    root = Path(configured).expanduser() if configured else repository_root() / "build" / "system-test"
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if root.is_symlink() or not root.is_dir():
        raise RunnerError(f"오류: 실행 artifact root가 안전한 디렉터리가 아닙니다: {root}")
    return root.resolve()


def run_directory(root: Path, run_id: str, *, must_exist: bool) -> Path:
    validate_identifier(run_id, RUN_ID_PATTERN, "runId")
    candidate = root / run_id
    if candidate.exists():
        if candidate.is_symlink() or not candidate.is_dir() or candidate.resolve().parent != root:
            raise RunnerError(f"오류: 안전하지 않은 실행 artifact 경로입니다: {candidate}")
    elif must_exist:
        raise RunnerError(f"오류: 테스트 실행을 찾을 수 없습니다: {run_id}")
    return candidate


def atomic_write_json(path: Path, payload: dict[str, Any]) -> None:
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}-{secrets.token_hex(4)}")
    encoded = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def no_follow_flag() -> int:
    return getattr(os, "O_NOFOLLOW", 0)


def load_snapshot(run_dir: Path) -> dict[str, Any]:
    snapshot_path = run_dir / "run.json"
    if snapshot_path.is_symlink() or not snapshot_path.is_file():
        raise RunnerError(f"오류: 실행 snapshot을 찾을 수 없습니다: {run_dir.name}")
    try:
        snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RunnerError(f"오류: 실행 snapshot을 읽을 수 없습니다: {run_dir.name}") from error
    if snapshot.get("schemaVersion") != 1 or snapshot.get("runId") != run_dir.name:
        raise RunnerError(f"오류: 실행 snapshot 계약이 올바르지 않습니다: {run_dir.name}")
    return snapshot


def latest_run_id(root: Path) -> str:
    candidates: list[tuple[str, str]] = []
    for child in root.iterdir():
        if child.is_symlink() or not child.is_dir() or not RUN_ID_PATTERN.fullmatch(child.name):
            continue
        try:
            snapshot = load_snapshot(child)
        except RunnerError:
            continue
        candidates.append((str(snapshot.get("startedAt", "")), child.name))
    if not candidates:
        raise RunnerError("오류: 저장된 테스트 실행이 없습니다.")
    return max(candidates)[1]


def prune_completed_runs(root: Path) -> None:
    lock_path = root / ".retention.lock"
    descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | no_follow_flag(), 0o600)
    try:
        with os.fdopen(descriptor, "r+") as lock_file:
            fcntl.flock(lock_file, fcntl.LOCK_EX)
            completed: list[tuple[str, str, Path]] = []
            for child in root.iterdir():
                if child.is_symlink() or not child.is_dir() or not RUN_ID_PATTERN.fullmatch(child.name):
                    continue
                try:
                    snapshot = load_snapshot(child)
                except RunnerError:
                    continue
                if snapshot.get("state") in TERMINAL_STATES:
                    completed.append((str(snapshot.get("startedAt", "")), child.name, child))
            completed.sort(reverse=True)
            for _, _, expired in completed[MAX_RETAINED_RUNS - 1 :]:
                if expired.parent == root and not expired.is_symlink():
                    launcher_log = root / ".launcher" / f"{expired.name}.log"
                    shutil.rmtree(expired)
                    if launcher_log.is_file() and not launcher_log.is_symlink():
                        launcher_log.unlink()
    finally:
        # os.fdopen owns and closes descriptor on the normal path.
        pass


class RunLedger:
    def __init__(self, root: Path, run_id: str, suite: str, total_steps: int) -> None:
        self.root = root
        self.run_id = run_id
        self.run_dir = run_directory(root, run_id, must_exist=False)
        try:
            self.run_dir.mkdir(mode=0o700)
        except FileExistsError as error:
            raise RunnerError(f"오류: 같은 runId의 실행이 이미 있습니다: {run_id}") from error
        (self.run_dir / "logs").mkdir(mode=0o700)
        (self.run_dir / "results").mkdir(mode=0o700)
        self.events_path = self.run_dir / "events.jsonl"
        self.events_path.touch(mode=0o600)
        self.sequence = 0
        started_at = utc_now()
        self.snapshot: dict[str, Any] = {
            "schemaVersion": 1,
            "runId": run_id,
            "suite": suite,
            "state": "PENDING",
            "startedAt": started_at,
            "updatedAt": started_at,
            "completedAt": None,
            "currentStep": None,
            "lastSuccessfulStep": None,
            "progress": {
                "completedSteps": 0,
                "totalSteps": total_steps,
                "percent": 0,
            },
            "steps": [],
            "components": [],
            "classification": ["REAL_LOCAL", "SIMULATED_VENDOR"],
            "relatedIds": {},
            "failure": None,
            "retainedEnvironment": False,
            "runnerPid": os.getpid(),
        }
        self.write_snapshot()
        self.append_event("RUN_CREATED")

    def write_snapshot(self) -> None:
        self.snapshot["updatedAt"] = utc_now()
        atomic_write_json(self.run_dir / "run.json", self.snapshot)

    def append_event(self, event_type: str, **details: Any) -> None:
        self.sequence += 1
        event = {
            "schemaVersion": 1,
            "sequence": self.sequence,
            "occurredAt": utc_now(),
            "runId": self.run_id,
            "type": event_type,
            "runState": self.snapshot["state"],
            **details,
        }
        descriptor = os.open(self.events_path, os.O_WRONLY | os.O_APPEND | no_follow_flag(), 0o600)
        with os.fdopen(descriptor, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
            handle.flush()
            os.fsync(handle.fileno())

    def start(self) -> None:
        self.snapshot["state"] = "RUNNING"
        self.write_snapshot()
        self.append_event("RUN_STARTED")

    def start_step(self, step_id: str, name: str, classification: tuple[str, ...]) -> dict[str, Any]:
        classifications = list(dict.fromkeys(classification))
        if not classifications or any(value not in LOCAL_CLASSIFICATIONS for value in classifications):
            raise RunnerError(f"오류: 단계 검증 분류가 올바르지 않습니다: {step_id}")
        step = {
            "id": step_id,
            "name": name,
            "state": "RUNNING",
            "startedAt": utc_now(),
            "completedAt": None,
            "durationMs": None,
            "failure": None,
            "classification": classifications,
            "observations": [],
        }
        self.snapshot["steps"].append(step)
        self.snapshot["currentStep"] = step_id
        self.write_snapshot()
        self.append_event("STEP_STARTED", stepId=step_id, stepState="RUNNING", classification=classifications)
        return step

    def complete_step(self, step: dict[str, Any], started: float) -> None:
        step["state"] = "PASSED"
        step["completedAt"] = utc_now()
        step["durationMs"] = round((time.monotonic() - started) * 1000)
        progress = self.snapshot["progress"]
        progress["completedSteps"] += 1
        progress["percent"] = int(progress["completedSteps"] * 100 / progress["totalSteps"])
        self.snapshot["lastSuccessfulStep"] = step["id"]
        self.snapshot["currentStep"] = None
        self.write_snapshot()
        self.append_event(
            "STEP_COMPLETED",
            stepId=step["id"],
            stepState="PASSED",
            classification=step["classification"],
        )

    def fail_step(self, step: dict[str, Any], failure: StepFailure | StepAborted, started: float) -> None:
        state = "ABORTED" if isinstance(failure, StepAborted) else "FAILED"
        failure_payload = {
            "code": failure.code,
            "message": failure.message,
            "failedStep": step["id"],
            "retryable": failure.retryable,
            "nextAction": failure.next_action,
        }
        step["state"] = state
        step["completedAt"] = utc_now()
        step["durationMs"] = round((time.monotonic() - started) * 1000)
        step["failure"] = failure_payload
        self.snapshot["state"] = state
        self.snapshot["failure"] = failure_payload
        self.snapshot["currentStep"] = None
        self.snapshot["completedAt"] = utc_now()
        self.write_snapshot()
        self.append_event(
            f"STEP_{state}",
            stepId=step["id"],
            stepState=state,
            classification=step["classification"],
            failure=failure_payload,
        )
        self.append_event(f"RUN_{state}", failure=failure_payload)

    def finish(self) -> None:
        self.snapshot["state"] = "PASSED"
        self.snapshot["completedAt"] = utc_now()
        self.snapshot["currentStep"] = None
        self.write_snapshot()
        self.append_event("RUN_COMPLETED")

    def write_runner_log(self, message: str) -> None:
        log_path = self.run_dir / "logs" / "runner.log"
        descriptor = os.open(
            log_path,
            os.O_WRONLY | os.O_APPEND | os.O_CREAT | no_follow_flag(),
            0o600,
        )
        with os.fdopen(descriptor, "a", encoding="utf-8") as handle:
            handle.write(f"{utc_now()} {message}\n")

    def set_component(self, name: str, state: str) -> None:
        validate_identifier(name, COMPONENT_PATTERN, "component")
        if state not in {"STARTING", "UP", "DOWN", "FAILED"}:
            raise RunnerError(f"오류: 알 수 없는 component 상태입니다: {state}")
        components = self.snapshot["components"]
        component = next((item for item in components if item["name"] == name), None)
        if component is None:
            component = {"name": name, "state": state, "observedAt": utc_now()}
            components.append(component)
        else:
            component["state"] = state
            component["observedAt"] = utc_now()
        self.write_snapshot()
        self.append_event("COMPONENT_STATE_CHANGED", component=name, componentState=state)

    def set_retained_environment(self, retained: bool) -> None:
        self.snapshot["retainedEnvironment"] = retained
        self.write_snapshot()
        self.append_event("ENVIRONMENT_RETENTION_CHANGED", retainedEnvironment=retained)

    def set_related_id(self, name: str, value: str | None) -> None:
        allowed = {
            "requestId",
            "externalTxId",
            "submissionId",
            "vendorTxId",
            "txHash",
            "eventId",
            "sweepRequestId",
            "executionId",
            "jobRunId",
            "accountId",
            "address",
        }
        if name not in allowed:
            raise RunnerError(f"오류: 허용되지 않은 관련 식별자입니다: {name}")
        if value is None:
            return
        safe_value = str(value)
        if not safe_value or len(safe_value) > 256 or any(ord(character) < 32 for character in safe_value):
            raise RunnerError(f"오류: 안전하지 않은 관련 식별자입니다: {name}")
        self.snapshot["relatedIds"][name] = safe_value
        current_step_id = self.snapshot.get("currentStep")
        current_step = next((step for step in reversed(self.snapshot["steps"]) if step["id"] == current_step_id), None)
        if current_step is not None:
            current_step["observations"].append(
                {
                    "type": name,
                    "value": safe_value,
                    "observedAt": utc_now(),
                }
            )
        self.write_snapshot()
        self.append_event(
            "RELATED_ID_OBSERVED",
            stepId=current_step_id,
            identifierType=name,
            classification=current_step["classification"] if current_step is not None else [],
        )


stop_signal_received = False


def handle_stop_signal(_signal_number: int, _frame: Any) -> None:
    global stop_signal_received
    stop_signal_received = True


def stop_requested(run_dir: Path) -> bool:
    return stop_signal_received or (run_dir / STOP_REQUEST_FILE).is_file()


def run_step(
    ledger: RunLedger,
    index: int,
    step_id: str,
    name: str,
    action: Any,
    *,
    classification: tuple[str, ...],
) -> None:
    started = time.monotonic()
    step = ledger.start_step(step_id, name, classification)
    try:
        if stop_requested(ledger.run_dir):
            raise StepAborted()
        action()
        if stop_requested(ledger.run_dir):
            raise StepAborted()
    except (StepFailure, StepAborted) as failure:
        ledger.fail_step(step, failure, started)
        print(f"[{index}/{ledger.snapshot['progress']['totalSteps']}] {name} {step['state']} {elapsed_seconds(started)}", flush=True)
        raise
    except Exception as error:
        failure = StepFailure(
            code="UNEXPECTED_STEP_FAILURE",
            message=f"예상하지 못한 로컬 테스트 오류가 발생했습니다: {type(error).__name__}",
            next_action="runner와 해당 component 로그를 확인하세요.",
            retryable=True,
        )
        ledger.fail_step(step, failure, started)
        print(f"[{index}/{ledger.snapshot['progress']['totalSteps']}] {name} FAILED {elapsed_seconds(started)}", flush=True)
        raise failure from error
    ledger.complete_step(step, started)
    print(f"[{index}/{ledger.snapshot['progress']['totalSteps']}] {name} OK {elapsed_seconds(started)}", flush=True)


def internal_test_action(ledger: RunLedger, scenario: str) -> None:
    ledger.write_runner_log(f"scenario={scenario}")
    if scenario == "PASS":
        return
    if scenario == "FAIL":
        raise StepFailure(
            code="INTERNAL_TEST_FAILURE",
            message="테스트 실행기 실패 경로를 검증했습니다.",
            next_action="runner.log와 실행 원장을 확인한 뒤 다시 실행하세요.",
            retryable=True,
        )
    while not stop_requested(ledger.run_dir):
        time.sleep(0.05)
    raise StepAborted()


def response_data(document: dict[str, Any], operation: str) -> Any:
    if "data" not in document:
        raise StepFailure(
            code="INVALID_API_RESPONSE",
            message=f"{operation} 응답에 data가 없습니다.",
            next_action="bcm-api 로그와 API 계약을 확인하세요.",
            retryable=False,
        )
    return document["data"]


def http_json(
    method: str,
    url: str,
    *,
    payload: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
    expected_statuses: set[int] | None = None,
) -> tuple[dict[str, Any], dict[str, str]]:
    encoded = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request_headers = {"Accept": "application/json", **(headers or {})}
    if encoded is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=encoded, method=method, headers=request_headers)
    expected = expected_statuses or {200}
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            status = response.status
            body = response.read(1_048_577)
            response_headers = {name.lower(): value for name, value in response.headers.items()}
    except urllib.error.HTTPError as error:
        status = error.code
        body = error.read(1_048_577)
        response_headers = {name.lower(): value for name, value in error.headers.items()}
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise StepFailure(
            code="HTTP_UNAVAILABLE",
            message=f"로컬 component HTTP 요청에 실패했습니다: {type(error).__name__}",
            next_action="해당 component 로그와 readiness를 확인하세요.",
            retryable=True,
        ) from error
    if len(body) > 1_048_576:
        raise StepFailure(
            code="HTTP_RESPONSE_TOO_LARGE",
            message="로컬 component 응답이 1 MiB 제한을 초과했습니다.",
            next_action="component 응답과 로그를 확인하세요.",
            retryable=False,
        )
    if status not in expected:
        raise StepFailure(
            code="HTTP_REJECTED",
            message=f"로컬 component가 예상하지 않은 HTTP {status}를 반환했습니다.",
            next_action="해당 component 로그와 요청 단계의 API 계약을 확인하세요.",
            retryable=status >= 500,
        )
    if not body:
        return {}, response_headers
    try:
        document = json.loads(body)
    except json.JSONDecodeError as error:
        raise StepFailure(
            code="INVALID_JSON_RESPONSE",
            message="로컬 component가 올바르지 않은 JSON을 반환했습니다.",
            next_action="해당 component 로그와 응답 계약을 확인하세요.",
            retryable=False,
        ) from error
    if not isinstance(document, dict):
        raise StepFailure(
            code="INVALID_JSON_RESPONSE",
            message="로컬 component JSON 응답이 객체가 아닙니다.",
            next_action="해당 component 로그와 응답 계약을 확인하세요.",
            retryable=False,
        )
    return document, response_headers


def smoke_compose_project(run_id: str) -> str:
    digest = hashlib.sha256(run_id.encode("ascii")).hexdigest()[:12]
    return f"bcm-system-test-{digest}"


def smoke_runtime_state_directory(run_id: str) -> Path:
    return repository_root() / "build" / "system-test-runtime" / run_id


def smoke_process_environment(root: Path, run_id: str) -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(
        {
            "BCM_LOCAL_STATE_DIR": str(smoke_runtime_state_directory(run_id)),
            "BCM_LOCAL_COMPOSE_PROJECT": smoke_compose_project(run_id),
            "BCM_LOCAL_DATASET": "stub",
            "BCM_SYSTEM_TEST_ROOT": str(root),
            "BCM_LOCAL_POSTGRES_PORT": str(SMOKE_POSTGRES_PORT),
            "BCM_LOCAL_KAFKA_PORT": str(SMOKE_KAFKA_PORT),
            "BCM_LOCAL_ANVIL_PORT": str(SMOKE_ANVIL_PORT),
            "BCM_LOCAL_CHAIN_PROFILE": "legacy",
            "BCM_LOCAL_STUB_PORT": str(SMOKE_STUB_PORT),
            "BCM_LOCAL_STUB_MANAGEMENT_PORT": str(SMOKE_STUB_MANAGEMENT_PORT),
            "BCM_LOCAL_API_PORT": str(SMOKE_API_PORT),
            "BCM_LOCAL_API_MANAGEMENT_PORT": str(SMOKE_API_MANAGEMENT_PORT),
            "BCM_LOCAL_WEBHOOK_PORT": str(SMOKE_WEBHOOK_PORT),
            "BCM_LOCAL_WEBHOOK_MANAGEMENT_PORT": str(SMOKE_WEBHOOK_MANAGEMENT_PORT),
            "BCM_LOCAL_ADMIN_PORT": str(SMOKE_ADMIN_PORT),
        }
    )
    return environment


class SmokeEnvironment:
    def __init__(self, ledger: RunLedger, keep_on_failure: bool) -> None:
        self.ledger = ledger
        self.keep_on_failure = keep_on_failure
        self.repo = repository_root()
        self.compose_project = smoke_compose_project(ledger.run_id)
        runtime_root = self.repo / "build" / "system-test-runtime"
        runtime_root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.state_dir = smoke_runtime_state_directory(ledger.run_id)
        if self.state_dir.exists() or self.state_dir.is_symlink():
            raise StepFailure(
                code="RUNTIME_STATE_EXISTS",
                message="같은 runId의 runtime 상태가 이미 존재합니다.",
                next_action="남은 runtime을 정리하거나 새 runId로 실행하세요.",
                retryable=True,
            )
        self.state_dir.mkdir(mode=0o700)
        self.started = False

    def environment(self) -> dict[str, str]:
        environment = smoke_process_environment(self.ledger.root, self.ledger.run_id)
        if self.ledger.snapshot["suite"] == "FULL":
            environment.update(
                {
                    "BCM_FIREBLOCKS_MAX_ATTEMPTS": "2",
                    "BCM_FIREBLOCKS_RETRY_BACKOFF_MILLIS": "50",
                    "BCM_FIREBLOCKS_MAX_BACKOFF_MILLIS": "50",
                    "BCM_VENDOR_CONNECT_TIMEOUT_MILLIS": "100",
                    "BCM_TRANSACTION_SUBMIT_TIMEOUT_MILLIS": "500",
                    "BCM_TRANSACTION_CLAIM_TTL_SECONDS": "3",
                }
            )
        return environment

    def run_command(
        self,
        command: list[str],
        log_name: str,
        *,
        environment: dict[str, str] | None = None,
        timeout: int = 600,
        check: bool = True,
        input_text: str | None = None,
        record_output: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        validate_identifier(log_name, COMPONENT_PATTERN, "component log")
        process = subprocess.run(
            command,
            cwd=self.repo,
            env=environment or self.environment(),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            input=input_text,
            timeout=timeout,
            check=False,
        )
        log_path = self.ledger.run_dir / "logs" / f"{log_name}.log"
        descriptor = os.open(log_path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | no_follow_flag(), 0o600)
        with os.fdopen(descriptor, "a", encoding="utf-8") as handle:
            if record_output:
                handle.write(process.stdout)
        if check and process.returncode != 0:
            raise StepFailure(
                code="COMPONENT_COMMAND_FAILED",
                message=f"{log_name} 명령이 종료 코드 {process.returncode}로 실패했습니다.",
                next_action=f"./scripts/system-test.sh logs {self.ledger.run_id} {log_name} 결과를 확인하세요.",
                retryable=True,
            )
        return process

    def start(self) -> None:
        for component in LONG_LIVED_COMPONENTS:
            self.ledger.set_component(component, "STARTING")
        self.ledger.set_component("bcm-bat", "DOWN")
        self.started = True
        try:
            self.run_command(["./scripts/local.sh", "up", "stub"], "local-up")
        except StepFailure:
            self.record_component_readiness(self.component_readiness(), startup_failed=True)
            raise
        readiness = self.component_readiness()
        self.record_component_readiness(readiness, startup_failed=not all(readiness.values()))
        unavailable = [name for name, ready in readiness.items() if not ready]
        if unavailable:
            raise StepFailure(
                code="COMPONENT_READINESS_FAILED",
                message=f"기동 명령 뒤 준비되지 않은 component가 있습니다: {', '.join(unavailable)}",
                next_action=f"./scripts/system-test.sh logs {self.ledger.run_id} 결과를 확인하세요.",
                retryable=True,
            )

    @staticmethod
    def tcp_ready(port: int) -> bool:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return True
        except OSError:
            return False

    @staticmethod
    def http_ready(url: str) -> bool:
        try:
            request = urllib.request.Request(url, method="GET", headers={"Accept": "application/json"})
            with urllib.request.urlopen(request, timeout=1) as response:
                return 200 <= response.status < 300
        except (urllib.error.URLError, TimeoutError, OSError):
            return False

    def component_readiness(self) -> dict[str, bool]:
        return {
            "postgres": self.tcp_ready(SMOKE_POSTGRES_PORT),
            "kafka": self.tcp_ready(SMOKE_KAFKA_PORT),
            "anvil": self.tcp_ready(SMOKE_ANVIL_PORT),
            "fireblocks-stub": self.http_ready(f"http://127.0.0.1:{SMOKE_STUB_MANAGEMENT_PORT}/actuator/health"),
            "bcm-api": self.http_ready(f"http://127.0.0.1:{SMOKE_API_MANAGEMENT_PORT}/actuator/health"),
            "bcm-webhook": self.http_ready(
                f"http://127.0.0.1:{SMOKE_WEBHOOK_MANAGEMENT_PORT}/actuator/health"
            ),
            "bcm-admin": self.http_ready(f"http://127.0.0.1:{SMOKE_ADMIN_PORT}/actuator/health"),
        }

    def record_component_readiness(self, readiness: dict[str, bool], *, startup_failed: bool) -> None:
        first_unavailable = next((name for name in LONG_LIVED_COMPONENTS if not readiness.get(name, False)), None)
        for component in LONG_LIVED_COMPONENTS:
            if readiness.get(component, False):
                state = "UP"
            elif startup_failed and component == first_unavailable:
                state = "FAILED"
            else:
                state = "DOWN"
            self.ledger.set_component(component, state)

    def bat_environment(self, job: str = "catalog-sync-once") -> dict[str, str]:
        environment = self.environment()
        environment.update(
            {
                "SPRING_DATASOURCE_URL": f"jdbc:postgresql://127.0.0.1:{SMOKE_POSTGRES_PORT}/bcm",
                "SPRING_DATASOURCE_USERNAME": "postgres",
                "SPRING_DATASOURCE_PASSWORD": "bcm",
                "KAFKA_BOOTSTRAP_SERVERS": f"127.0.0.1:{SMOKE_KAFKA_PORT}",
                "BCM_HTTP_MAX_CONNECTIONS": "100",
                "BCM_FIREBLOCKS_BASE_URL": f"http://127.0.0.1:{SMOKE_STUB_PORT}",
                "BCM_FIREBLOCKS_API_KEY": "bcm-local-stub",
                "BCM_FIREBLOCKS_PRIVATE_KEY_FILE": str(self.state_dir / "stub" / "fireblocks-api-private-key.pem"),
                "FIREBLOCKS_JWKS_URL": f"http://127.0.0.1:{SMOKE_STUB_PORT}/.well-known/jwks.json",
                "BCM_JOB": job,
            }
        )
        environment.pop("BCM_FIREBLOCKS_PRIVATE_KEY_PEM", None)
        return environment

    def sync_catalog(self) -> None:
        self.run_bat_job(
            ["./gradlew", "--no-daemon", ":blockchain-manager-app:bcm-bat:bootRun"],
            "catalog-sync",
            environment=self.bat_environment("catalog-sync-once"),
        )

    def sync_asset_catalog(self) -> None:
        self.run_bat_job(
            ["./gradlew", "--no-daemon", ":blockchain-manager-app:bcm-bat:bootRun"],
            "asset-catalog-sync",
            environment=self.bat_environment("asset-catalog-supported-sync-once"),
        )

    def run_bat_job(
        self,
        command: list[str],
        log_name: str,
        *,
        environment: dict[str, str] | None = None,
        timeout: int = 600,
    ) -> subprocess.CompletedProcess[str]:
        self.ledger.set_component("bcm-bat", "STARTING")
        try:
            result = self.run_command(command, log_name, environment=environment, timeout=timeout)
        except Exception:
            self.ledger.set_component("bcm-bat", "FAILED")
            raise
        self.ledger.set_component("bcm-bat", "DOWN")
        return result

    def capture_component_logs(self) -> None:
        mappings = {
            "chain": "anvil",
            "stub": "fireblocks-stub",
            "api": "bcm-api",
            "webhook": "bcm-webhook",
            "admin": "bcm-admin",
        }
        for source_name, target_name in mappings.items():
            source = self.state_dir / f"{source_name}.log"
            target = self.ledger.run_dir / "logs" / f"{target_name}.log"
            if source.is_file() and not source.is_symlink():
                shutil.copyfile(source, target)
                target.chmod(0o600)
        self.run_command(
            ["docker", "compose", "-p", self.compose_project, "-f", "config/local-compose.yaml", "logs", "--no-color"],
            "infra",
            timeout=60,
            check=False,
        )

    def active_managed_resources(self) -> list[str]:
        active: list[str] = []
        process_components = {
            "chain": "anvil",
            "stub": "fireblocks-stub",
            "api": "bcm-api",
            "webhook": "bcm-webhook",
            "admin": "bcm-admin",
        }
        for process_name, component in process_components.items():
            pid_path = self.state_dir / f"{process_name}.pid"
            if not pid_path.is_file() or pid_path.is_symlink():
                continue
            try:
                pid = int(pid_path.read_text(encoding="utf-8").strip())
                if pid <= 1:
                    continue
                os.kill(pid, 0)
                active.append(component)
            except (OSError, UnicodeError, ValueError):
                continue
        compose_status = subprocess.run(
            [
                "docker",
                "compose",
                "-p",
                self.compose_project,
                "-f",
                "config/local-compose.yaml",
                "ps",
                "-q",
            ],
            cwd=self.repo,
            env=self.environment(),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
            check=False,
        )
        if compose_status.returncode != 0:
            active.extend(("postgres", "kafka"))
        elif compose_status.stdout.strip():
            active.extend(("postgres", "kafka"))
        return list(dict.fromkeys(active))

    def cleanup(self, *, failed: bool, strict: bool = True) -> None:
        if failed and self.keep_on_failure and self.started:
            self.ledger.set_retained_environment(True)
            self.ledger.write_runner_log("components=KEPT_FOR_FAILURE_INVESTIGATION")
            return
        cleanup_errors: list[str] = []
        try:
            self.capture_component_logs()
        except (OSError, subprocess.SubprocessError, StepFailure) as error:
            cleanup_errors.append(f"log-capture:{type(error).__name__}")
        try:
            local_down = self.run_command(["./scripts/local.sh", "down"], "local-down", timeout=180, check=False)
            local_down_failed = local_down.returncode != 0
            if local_down_failed:
                cleanup_errors.append(f"local-down:{local_down.returncode}")
        except (OSError, subprocess.SubprocessError) as error:
            local_down_failed = True
            cleanup_errors.append(f"local-down:{type(error).__name__}")
        try:
            infra_down = self.run_command(
                [
                    "docker",
                    "compose",
                    "-p",
                    self.compose_project,
                    "-f",
                    "config/local-compose.yaml",
                    "down",
                    "--volumes",
                    "--remove-orphans",
                ],
                "infra-cleanup",
                timeout=180,
                check=False,
            )
            infra_down_failed = infra_down.returncode != 0
            if infra_down_failed:
                cleanup_errors.append(f"infra-cleanup:{infra_down.returncode}")
        except (OSError, subprocess.SubprocessError) as error:
            infra_down_failed = True
            cleanup_errors.append(f"infra-cleanup:{type(error).__name__}")
        try:
            active = self.active_managed_resources()
        except (OSError, subprocess.SubprocessError) as error:
            active = list(SMOKE_COMPONENTS)
            cleanup_errors.append(f"resource-probe:{type(error).__name__}")
        failed_components = set(active)
        if local_down_failed:
            failed_components.update(("anvil", "fireblocks-stub", "bcm-api", "bcm-webhook", "bcm-admin"))
        if infra_down_failed:
            failed_components.update(("postgres", "kafka"))
        if cleanup_errors and not failed_components:
            failed_components.update(SMOKE_COMPONENTS)
        for component in SMOKE_COMPONENTS:
            self.ledger.set_component(component, "FAILED" if component in failed_components else "DOWN")
        if cleanup_errors or active:
            self.ledger.set_retained_environment(True)
            details = ",".join(cleanup_errors + [f"active:{name}" for name in active])
            self.ledger.write_runner_log(f"component-cleanup=FAILED details={details}")
            if strict:
                raise StepFailure(
                    code="COMPONENT_CLEANUP_FAILED",
                    message="system test component 정리 명령 또는 잔존 리소스 검증이 실패했습니다.",
                    next_action=f"./scripts/system-test.sh logs {self.ledger.run_id} local-down 결과를 확인한 뒤 stop을 실행하세요.",
                    retryable=True,
                )
            return
        self.ledger.set_retained_environment(False)
        if self.state_dir.exists() and self.state_dir.parent.name == "system-test-runtime":
            shutil.rmtree(self.state_dir)


def adopt_local_asset(ledger: RunLedger, sync_asset_catalog: Callable[[], None]) -> None:
    actor_headers = {"X-Employee-No": "TST001", "X-Branch-Code": "9999"}
    networks, headers = http_json("GET", f"http://127.0.0.1:{SMOKE_API_PORT}/admin/networks?chainId=31337")
    candidates = response_data(networks, "네트워크 카탈로그 조회")
    local = next((item for item in candidates if item.get("chainId") == 31337), None)
    if local is None:
        raise StepFailure(
            code="LOCAL_NETWORK_NOT_FOUND",
            message="동기화한 Fireblocks 카탈로그에서 chainId 31337을 찾지 못했습니다.",
            next_action="catalog-sync와 fireblocks-stub 로그를 확인하세요.",
            retryable=True,
        )
    ledger.set_related_id("requestId", headers.get("x-request-id"))
    http_json(
        "PUT",
        f"http://127.0.0.1:{SMOKE_API_PORT}/admin/networks/LOCAL",
        payload={"candidateId": local.get("candidateId")},
        headers=actor_headers,
    )
    sync_asset_catalog()
    for symbol in ("TUSD", "ETH"):
        assets, _ = http_json(
            "GET",
            f"http://127.0.0.1:{SMOKE_API_PORT}/admin/asset-candidates?q={symbol}&network=LOCAL",
        )
        search_result = response_data(assets, "자산 후보 조회")
        candidate_items = search_result.get("items") if isinstance(search_result, dict) else None
        if not isinstance(candidate_items, list):
            raise StepFailure(
                code="INVALID_ASSET_CANDIDATE_RESPONSE",
                message="자산 후보 응답 형식이 올바르지 않습니다.",
                next_action="BCM API 로그를 확인하세요.",
                retryable=False,
            )
        asset = next((item for item in candidate_items if item.get("symbol") == symbol), None)
        if asset is None:
            raise StepFailure(
                code="LOCAL_ASSET_NOT_FOUND",
                message=f"로컬 {symbol} 자산 후보를 찾지 못했습니다.",
                next_action="fireblocks-stub 자산 카탈로그 응답을 확인하세요.",
                retryable=True,
            )
        _, mapping_headers = http_json(
            "POST",
            f"http://127.0.0.1:{SMOKE_API_PORT}/admin/asset-mappings",
            payload={
                "network": "LOCAL",
                "symbol": symbol,
                "fireblocksAssetId": asset.get("fireblocksAssetId"),
                "contractAddress": asset.get("contractAddress"),
            },
            headers=actor_headers,
            expected_statuses={201},
        )
        ledger.set_related_id("requestId", mapping_headers.get("x-request-id"))


def create_account(
    ledger: RunLedger,
    account_type: str,
    ref: str,
    symbol: str | None = None,
) -> tuple[str, str | None]:
    account_document, account_headers = http_json(
        "POST",
        f"http://127.0.0.1:{SMOKE_API_PORT}/accounts",
        payload={"accountType": account_type, "ref": ref},
        expected_statuses={201},
    )
    account = response_data(account_document, "계정 생성")
    account_id = account.get("accountId")
    if not isinstance(account_id, str) or not account_id:
        raise StepFailure("INVALID_ACCOUNT_RESPONSE", "계정 생성 응답에 accountId가 없습니다.", "bcm-api 로그를 확인하세요.", False)
    ledger.set_related_id("requestId", account_headers.get("x-request-id"))
    if symbol is None:
        return account_id, None
    address_document, address_headers = http_json(
        "POST",
        f"http://127.0.0.1:{SMOKE_API_PORT}/accounts/{urllib.parse.quote(account_id, safe='')}/addresses",
        payload={"symbol": symbol, "networks": [LOCAL_NETWORK]},
    )
    addresses = response_data(address_document, "입금 주소 생성")
    address = addresses[0].get("address") if isinstance(addresses, list) and addresses else None
    if not isinstance(address, str) or not address:
        raise StepFailure("INVALID_ADDRESS_RESPONSE", "입금 주소 생성이 성공 주소를 반환하지 않았습니다.", "bcm-api 로그를 확인하세요.", False)
    ledger.set_related_id("requestId", address_headers.get("x-request-id") or account_headers.get("x-request-id"))
    return account_id, address


def create_deposit_destination(ledger: RunLedger) -> tuple[str, str]:
    account_id, address = create_account(ledger, "CUSTOMER", f"SYSTEM-TEST-{ledger.run_id}", LOCAL_SYMBOL)
    if address is None:
        raise StepFailure("INVALID_ADDRESS_RESPONSE", "입금 주소 생성 응답이 비었습니다.", "bcm-api 로그를 확인하세요.", False)
    return account_id, address


def inject_local_deposit(
    ledger: RunLedger,
    address: str,
    external_label: str = "smoke-deposit",
) -> str:
    encoded_address = urllib.parse.quote(address, safe="")
    lookup, _ = http_json(
        "GET",
        f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/vaults/by-address/{LOCAL_VENDOR_ASSET_ID}?address={encoded_address}",
    )
    vault_id = lookup.get("vaultId")
    if not isinstance(vault_id, str) or not vault_id:
        raise StepFailure("STUB_VAULT_NOT_FOUND", "Stub에서 입금 주소의 vault를 찾지 못했습니다.", "fireblocks-stub 로그를 확인하세요.", False)
    http_json("POST", f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/webhooks/local-webhook/activate")
    external_tx_id = f"{external_label}-{ledger.run_id}"
    transaction, _ = http_json(
        "POST",
        f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/deposits",
        payload={"externalTxId": external_tx_id, "assetId": LOCAL_VENDOR_ASSET_ID, "destinationVaultId": vault_id, "amount": "1"},
    )
    vendor_tx_id = transaction.get("id")
    if not isinstance(vendor_tx_id, str) or not vendor_tx_id:
        raise StepFailure("INVALID_DEPOSIT_RESPONSE", "Stub 입금 응답에 거래 ID가 없습니다.", "fireblocks-stub 로그를 확인하세요.", False)
    ledger.set_related_id("externalTxId", external_tx_id)
    ledger.set_related_id("vendorTxId", vendor_tx_id)
    ledger.set_related_id("txHash", transaction.get("txHash"))
    return vendor_tx_id


def finalize_local_deposit(vendor_tx_id: str) -> None:
    encoded = urllib.parse.quote(vendor_tx_id, safe="")
    first, _ = http_json("POST", f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/transactions/{encoded}/advance")
    second, _ = http_json("POST", f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/transactions/{encoded}/advance")
    if first.get("status") != "CONFIRMING" or second.get("status") != "COMPLETED":
        raise StepFailure(
            code="DEPOSIT_NOT_COMPLETED",
            message="로컬 입금이 CONFIRMING→COMPLETED로 전이하지 않았습니다.",
            next_action="anvil과 fireblocks-stub 로그를 확인하세요.",
            retryable=True,
        )


def await_bcm_finalized(ledger: RunLedger, vendor_tx_id: str, expected_amount: str = "1") -> None:
    encoded = urllib.parse.quote(vendor_tx_id, safe="")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            document, headers = http_json("GET", f"http://127.0.0.1:{SMOKE_API_PORT}/transactions/{encoded}")
            transaction = response_data(document, "BCM 거래 조회")
            if transaction.get("status") == "FINALIZED":
                if transaction.get("txId") != vendor_tx_id or transaction.get("amount") != expected_amount:
                    raise StepFailure(
                        "BCM_TRANSACTION_MISMATCH",
                        "BCM FINALIZED 거래의 식별자 또는 금액이 일치하지 않습니다.",
                        "bcm-api 로그를 확인하세요.",
                        False,
                    )
                ledger.set_related_id("requestId", headers.get("x-request-id"))
                return
        except StepFailure as failure:
            if failure.code not in {"HTTP_REJECTED"}:
                raise
        if stop_requested(ledger.run_dir):
            raise StepAborted()
        time.sleep(0.5)
    raise StepFailure(
        code="BCM_FINALIZED_TIMEOUT",
        message="30초 안에 BCM 거래가 FINALIZED로 수렴하지 않았습니다.",
        next_action="bcm-api와 fireblocks-stub 로그를 확인하세요.",
        retryable=True,
    )


def await_admin_investigation(
    ledger: RunLedger,
    vendor_tx_id: str,
    expected_amount: str = "1",
) -> dict[str, Any]:
    encoded = urllib.parse.quote(vendor_tx_id, safe="")
    deadline = time.monotonic() + 30
    last_document: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        try:
            document, headers = http_json(
                "GET",
                f"http://127.0.0.1:{SMOKE_ADMIN_PORT}/bff/admin/transactions/{encoded}",
            )
            last_document = document
            summary = document.get("data", {}).get("summary", {})
            if summary.get("status") == "FINALIZED":
                if summary.get("rootTransactionId") != vendor_tx_id or summary.get("amount") != expected_amount:
                    raise StepFailure(
                        "ADMIN_INVESTIGATION_MISMATCH",
                        "Admin 거래 조사 결과의 식별자 또는 금액이 일치하지 않습니다.",
                        "bcm-api와 bcm-admin 로그를 확인하세요.",
                        False,
                    )
                ledger.set_related_id("requestId", headers.get("x-request-id"))
                return document
        except StepFailure as failure:
            if failure.code not in {"HTTP_REJECTED"}:
                raise
        if stop_requested(ledger.run_dir):
            raise StepAborted()
        time.sleep(0.5)
    raise StepFailure(
        code="ADMIN_FINALIZED_TIMEOUT",
        message="30초 안에 Admin 거래 조사가 FINALIZED로 수렴하지 않았습니다.",
        next_action="bcm-api, fireblocks-stub, bcm-admin 로그를 확인하세요.",
        retryable=True,
    )


def verify_kafka_event(environment: SmokeEnvironment, ledger: RunLedger, account_id: str) -> None:
    probe_environment = environment.environment()
    probe_environment.update(
        {
            "BCM_KAFKA_PROBE_BOOTSTRAP": f"127.0.0.1:{SMOKE_KAFKA_PORT}",
            "BCM_KAFKA_PROBE_TOPIC": "deposit-events",
            "BCM_KAFKA_PROBE_GROUP_ID": f"bcm-system-test-{ledger.run_id}",
            "BCM_KAFKA_PROBE_MAX_MESSAGES": "3",
            "BCM_KAFKA_PROBE_TIMEOUT_MILLIS": "15000",
        }
    )
    result = environment.run_command(
        [
            "./gradlew",
            "--no-daemon",
            ":blockchain-manager-infra:messaging:consumeLocalKafka",
        ],
        "kafka-event",
        environment=probe_environment,
        timeout=30,
        check=False,
    )
    documents: list[dict[str, Any]] = []
    for line in result.stdout.splitlines():
        try:
            candidate = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(candidate, dict):
            documents.append(candidate)
    finalized = next(
        (
            event
            for event in documents
            if event.get("status") == "FINALIZED"
            and event.get("accountId") == account_id
            and event.get("amount") == "1"
        ),
        None,
    )
    if finalized is None:
        raise StepFailure(
            code="KAFKA_FINALIZED_EVENT_NOT_FOUND",
            message="고객 계정의 FINALIZED 입금 Kafka event를 찾지 못했습니다.",
            next_action=f"./scripts/system-test.sh logs {ledger.run_id} kafka-event 결과를 확인하세요.",
            retryable=True,
        )
    ledger.set_related_id("eventId", finalized.get("eventId"))


def local_manifest(environment: SmokeEnvironment) -> dict[str, Any]:
    path = environment.state_dir / "stub" / "chain" / "manifest.json"
    if path.is_symlink() or not path.is_file():
        raise StepFailure(
            "LOCAL_MANIFEST_NOT_FOUND",
            "로컬 체인 manifest를 찾지 못했습니다.",
            "anvil과 fireblocks-stub 기동 로그를 확인하세요.",
            False,
        )
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise StepFailure(
            "INVALID_LOCAL_MANIFEST",
            "로컬 체인 manifest를 읽을 수 없습니다.",
            "anvil 기동 로그를 확인하세요.",
            False,
        ) from error
    if not isinstance(document, dict):
        raise StepFailure("INVALID_LOCAL_MANIFEST", "로컬 체인 manifest 형식이 잘못됐습니다.", "anvil 로그를 확인하세요.", False)
    return document


def configure_transaction_fault(status: int, *, after_commit: bool, delay_millis: int = 0) -> None:
    http_json(
        "POST",
        f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/faults/transactions/next-response",
        payload={"status": status, "afterCommit": after_commit, "delayMillis": delay_millis},
    )


def submit_gasless_withdrawal(
    ledger: RunLedger,
    account_id: str,
    destination_address: str,
    external_tx_id: str,
    *,
    expected_statuses: set[int] | None = None,
) -> str | None:
    document, headers = http_json(
        "POST",
        f"http://127.0.0.1:{SMOKE_API_PORT}/transactions",
        payload={
            "externalTxId": external_tx_id,
            "from": {"type": "ACCOUNT", "accountId": account_id},
            "to": {"type": "ADDRESS", "address": destination_address},
            "network": "LOCAL",
            "symbol": "TUSD",
            "amount": "0.1",
            "note": "phase12 full system test",
            "travelRule": None,
        },
        expected_statuses=expected_statuses or {202},
    )
    ledger.set_related_id("requestId", headers.get("x-request-id"))
    data = document.get("data")
    if not isinstance(data, dict):
        return None
    vendor_tx_id = data.get("txId")
    if not isinstance(vendor_tx_id, str) or not vendor_tx_id:
        return None
    ledger.set_related_id("externalTxId", external_tx_id)
    ledger.set_related_id("vendorTxId", vendor_tx_id)
    return vendor_tx_id


def finalize_local_transaction(ledger: RunLedger, vendor_tx_id: str, expected_amount: str = "0.1") -> None:
    encoded = urllib.parse.quote(vendor_tx_id, safe="")
    first, _ = http_json("POST", f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/transactions/{encoded}/advance")
    second, _ = http_json("POST", f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/transactions/{encoded}/advance")
    if first.get("status") != "CONFIRMING" or second.get("status") != "COMPLETED":
        raise StepFailure(
            "WITHDRAWAL_NOT_COMPLETED",
            "로컬 출금이 CONFIRMING→COMPLETED로 전이하지 않았습니다.",
            "anvil과 fireblocks-stub 로그를 확인하세요.",
            True,
        )
    ledger.set_related_id("txHash", second.get("txHash"))
    await_bcm_finalized(ledger, vendor_tx_id, expected_amount)


def postgres_scalar(environment: SmokeEnvironment, query: str, log_name: str) -> str:
    result = environment.run_command(
        [
            "docker",
            "compose",
            "-p",
            environment.compose_project,
            "-f",
            "config/local-compose.yaml",
            "exec",
            "-T",
            "postgres",
            "psql",
            "-U",
            "postgres",
            "-d",
            "bcm",
            "-Atc",
            query,
        ],
        log_name,
    )
    values = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if not values:
        raise StepFailure("POSTGRES_PROBE_EMPTY", "PostgreSQL probe 결과가 없습니다.", "infra 로그를 확인하세요.", True)
    return values[-1]


def postgres_execute(environment: SmokeEnvironment, script: str, log_name: str) -> None:
    environment.run_command(
        [
            "docker",
            "compose",
            "-p",
            environment.compose_project,
            "-f",
            "config/local-compose.yaml",
            "exec",
            "-T",
            "postgres",
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            "postgres",
            "-d",
            "bcm",
            "-f",
            "-",
        ],
        log_name,
        input_text=script,
        record_output=False,
    )


def postgres_json(environment: SmokeEnvironment, query: str, log_name: str) -> dict[str, Any]:
    raw = postgres_scalar(environment, query, log_name)
    try:
        document = json.loads(raw)
    except json.JSONDecodeError as error:
        raise StepFailure(
            "POSTGRES_PROBE_INVALID_JSON",
            "PostgreSQL 검증 결과를 해석할 수 없습니다.",
            f"{log_name} 로그를 확인하세요.",
            False,
        ) from error
    if not isinstance(document, dict):
        raise StepFailure("POSTGRES_PROBE_INVALID_JSON", "PostgreSQL 검증 결과가 객체가 아닙니다.", f"{log_name} 로그를 확인하세요.", False)
    return document


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def anvil_rpc(method: str, params: list[Any]) -> Any:
    response, _ = http_json(
        "POST",
        f"http://127.0.0.1:{SMOKE_ANVIL_PORT}",
        payload={"jsonrpc": "2.0", "id": 1, "method": method, "params": params},
    )
    if "error" in response or "result" not in response:
        raise StepFailure(
            "ANVIL_RPC_REJECTED",
            f"Anvil {method} 호출이 실패했습니다.",
            "anvil 로그를 확인하세요.",
            True,
        )
    return response["result"]


def approve_sweep_contract(owner: str, token: str, sweep_contract: str, raw_amount: int) -> str:
    for address in (owner, token, sweep_contract):
        if not re.fullmatch(r"0x[0-9a-fA-F]{40}", address):
            raise StepFailure("INVALID_LOCAL_ADDRESS", "sweep fixture EVM 주소가 올바르지 않습니다.", "chain manifest를 확인하세요.", False)
    anvil_rpc("anvil_setBalance", [owner, hex(10**18)])
    call_data = "0x095ea7b3" + sweep_contract.removeprefix("0x").lower().rjust(64, "0") + hex(raw_amount)[2:].rjust(64, "0")
    transaction_hash = anvil_rpc(
        "eth_sendTransaction",
        [{"from": owner, "to": token, "data": call_data, "gas": hex(100_000)}],
    )
    if not isinstance(transaction_hash, str) or not transaction_hash.startswith("0x"):
        raise StepFailure("INVALID_APPROVAL_TRANSACTION", "allowance 설정 거래 hash가 없습니다.", "anvil 로그를 확인하세요.", False)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        receipt = anvil_rpc("eth_getTransactionReceipt", [transaction_hash])
        if isinstance(receipt, dict):
            if receipt.get("status") != "0x1":
                raise StepFailure("APPROVAL_TRANSACTION_FAILED", "allowance 설정 거래가 실패했습니다.", "anvil 로그를 확인하세요.", False)
            return transaction_hash
        time.sleep(0.1)
    raise StepFailure("APPROVAL_RECEIPT_TIMEOUT", "allowance 설정 receipt를 확인하지 못했습니다.", "anvil 로그를 확인하세요.", True)


def sweep_bat_environment(
    environment: SmokeEnvironment,
    job: str,
    operator_account_id: str,
    omnibus_account_id: str,
    manifest: dict[str, Any],
) -> dict[str, str]:
    sweep_contract = manifest["sweepContractAddress"]
    application = {
        "bcm": {
            "job": job,
            "fireblocks": {"contract-call-gas-asset-ids": {"LOCAL": "ETH_LOCAL"}},
            "evm-rpc": {"networks": {"LOCAL": {"url": f"http://127.0.0.1:{SMOKE_ANVIL_PORT}"}}},
            "admin-policy": {
                "hard-ceiling": {
                    "execution-enabled": True,
                    "maximum-batch-size": 2,
                    "maximum-allowance": 200,
                    "maximum-item-amount": 200,
                    "maximum-batch-amount": 400,
                    "maximum-boost-attempts": 1,
                }
            },
            "sweep": {
                "omnibus-account-id": omnibus_account_id,
                "operator-account-id": operator_account_id,
                "batch-size": 2,
                "reconciliation-batch-size": 10,
                "thresholds": [{"network": "LOCAL", "symbol": "TUSD", "minimum-amount": "10", "allowance-cap": "200"}],
                "contracts": [{"network": "LOCAL", "address": sweep_contract}],
                "security": {
                    "normal-approval-enabled": True,
                    "batch-submission-enabled": True,
                    "tap-approval-policy-verified": True,
                    "tap-batch-policy-verified": True,
                    "callback-verified": True,
                    "universal-gasless-verified": True,
                    "sweep-contract-verified": True,
                    "normal-approval-enabled-networks": ["LOCAL"],
                    "batch-submission-enabled-networks": ["LOCAL"],
                },
            },
        }
    }
    bat_environment = environment.bat_environment()
    bat_environment["BCM_JOB"] = job
    bat_environment["SPRING_APPLICATION_JSON"] = json.dumps(application, separators=(",", ":"))
    return bat_environment


def prepare_daw_sweep_fixture(
    environment: SmokeEnvironment,
    ledger: RunLedger,
    first_account_id: str,
    first_address: str,
) -> dict[str, str]:
    suffix = hashlib.sha256(ledger.run_id.encode("ascii")).hexdigest()[:12]
    second_account_id, second_address = create_account(ledger, "CUSTOMER", f"SWEEP-CUSTOMER-{suffix}", "TUSD")
    operator_account_id, operator_address = create_account(ledger, "SYSTEM", f"SWEEP-OPERATOR-{suffix}", "ETH")
    omnibus_account_id, _ = create_account(ledger, "SYSTEM", f"SWEEP-OMNIBUS-{suffix}")
    if second_address is None or operator_address is None:
        raise StepFailure("SWEEP_ACCOUNT_ADDRESS_MISSING", "sweep fixture 주소가 생성되지 않았습니다.", "bcm-api 로그를 확인하세요.", False)

    manifest = local_manifest(environment)
    customer_addresses = manifest.get("customerAddresses")
    if (
        not isinstance(customer_addresses, list)
        or len(customer_addresses) < 2
        or first_address.lower() != str(customer_addresses[0]).lower()
        or second_address.lower() != str(customer_addresses[1]).lower()
        or operator_address.lower() != str(manifest.get("operatorAddress", "")).lower()
    ):
        raise StepFailure(
            "SWEEP_FIXTURE_ADDRESS_MISMATCH",
            "BCM vault 순서와 로컬 체인 customer/operator 주소가 일치하지 않습니다.",
            "계정 생성 순서와 chain manifest를 확인하세요.",
            False,
        )
    token = str(manifest.get("tokenContractAddress", ""))
    sweep_contract = str(manifest.get("sweepContractAddress", ""))
    approve_sweep_contract(first_address, token, sweep_contract, 200_000_000)
    approve_sweep_contract(second_address, token, sweep_contract, 200_000_000)

    contract_version_id = "0198c7d5-7a30-7000-8000-000000000131"
    contract_evidence_id = "0198c7d5-7a30-7000-8000-000000000132"
    policy_version_id = "0198c7d5-7a30-7000-8000-000000000133"
    policy_snapshot_hash = "a" * 64
    now_core = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    code_hash = str(manifest.get("sweepCodeHash", "")).removeprefix("0x").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", code_hash):
        raise StepFailure("INVALID_SWEEP_CODE_HASH", "로컬 sweep code hash가 올바르지 않습니다.", "chain manifest를 확인하세요.", False)

    script = f"""
BEGIN;
INSERT INTO bcm_ctrt_vrsn_l
  (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr, release_cmit,
   artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash, deploy_blck_no,
   immut_payload, immut_hash, ceiling_payload, ceiling_hash, release_uri, reg_dttm,
   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
VALUES ({sql_literal(contract_version_id)}, 'LOCAL:SWEEP', 'LOCAL', 'SWEEP', 'local-v1', {sql_literal(sweep_contract)},
        'local-commit', '{'1' * 64}', '{'2' * 64}', {sql_literal(code_hash)}, '0xlocaldeploy', 1,
        '{{}}'::jsonb, '{'4' * 64}', '{{}}'::jsonb, '{'5' * 64}', 'local://release', {sql_literal(now_core)},
        'SYSTEM', '9999', 'SYSTEM', '9999');
INSERT INTO bcm_ctrt_evdc_l
  (evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash, exp_immut_hash, pin_blck_no,
   rpc1_id, rpc1_chain_id, rpc1_code_hash, rpc1_immut_hash, rpc1_obs_dttm,
   rpc2_id, rpc2_chain_id, rpc2_code_hash, rpc2_immut_hash, rpc2_obs_dttm,
   tap_mtch_yn, clbk_mtch_yn, gasless_pass_yn, audit_pass_yn, revoke_drill_yn,
   launch_gate_yn, evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc, doc_evdc_hash,
   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
VALUES ({sql_literal(contract_evidence_id)}, {sql_literal(contract_version_id)}, '{'6' * 64}', 31337, {sql_literal(code_hash)}, '{'4' * 64}', 1,
        'RPC_A', 31337, {sql_literal(code_hash)}, '{'4' * 64}', {sql_literal(now_core)},
        'RPC_B', 31337, {sql_literal(code_hash)}, '{'4' * 64}', {sql_literal(now_core)},
        'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'VALID', {sql_literal(now_core)}, '20991231235959', '{{}}'::jsonb, '{'7' * 64}',
        'SYSTEM', '9999', 'SYSTEM', '9999');
INSERT INTO bcm_plcy_vrsn_l
  (plcy_vrsn_id, plcy_scope_id, vrsn_no, plcy_schm_vrsn, ctrt_vrsn_id,
   plcy_payload, plcy_hash, ceiling_snps, ceiling_hash, ceiling_pass_yn, reg_dttm,
   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
VALUES ({sql_literal(policy_version_id)}, 'POLICY:LOCAL:TUSD', 1, 'local-v1', {sql_literal(contract_version_id)},
        '{{"enabled":true,"minimumAmount":10,"batchSize":2,"allowanceCap":200,"itemAmountCap":200,"batchAmountCap":400,"boostAttempts":1}}'::jsonb,
        '{'8' * 64}', '{{}}'::jsonb, '{'9' * 64}', 'Y', {sql_literal(now_core)}, 'SYSTEM', '9999', 'SYSTEM', '9999');
INSERT INTO bcm_ctrt_bind_m
  (ctrt_scope_id, ntwk_cd, use_dvcd, actv_ctrt_vrsn_id, bind_rvsn, last_evdc_id, bind_snps_hash,
   reg_dttm, last_chng_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
VALUES ('LOCAL:SWEEP', 'LOCAL', 'SWEEP', {sql_literal(contract_version_id)}, 1, {sql_literal(contract_evidence_id)},
        '{'b' * 64}', {sql_literal(now_core)}, {sql_literal(now_core)}, 'SYSTEM', '9999', 'SYSTEM', '9999');
INSERT INTO bcm_plcy_bind_m
  (plcy_scope_id, actv_plcy_vrsn_id, bind_rvsn, bind_snps_hash, reg_dttm, last_chng_dttm,
   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
VALUES ('POLICY:LOCAL:TUSD', {sql_literal(policy_version_id)}, 1, {sql_literal(policy_snapshot_hash)},
        {sql_literal(now_core)}, {sql_literal(now_core)}, 'SYSTEM', '9999', 'SYSTEM', '9999');
COMMIT;
"""
    postgres_execute(environment, script, "sweep-fixture")
    return {
        "firstAccountId": first_account_id,
        "firstAddress": first_address,
        "secondAccountId": second_account_id,
        "secondAddress": second_address,
        "operatorAccountId": operator_account_id,
        "omnibusAccountId": omnibus_account_id,
        "tokenAddress": token,
        "sweepContractAddress": sweep_contract,
        "manifest": json.dumps(manifest, separators=(",", ":")),
    }


def prepared_execution_for_request(
    environment: SmokeEnvironment,
    fixture: dict[str, str],
    request: dict[str, Any],
) -> dict[str, str]:
    request_id = request.get("sweepRequestId")
    request_items = request.get("items")
    if not isinstance(request_id, str) or not isinstance(request_items, list):
        raise StepFailure("INVALID_SWEEP_REQUEST_RESPONSE", "Sweep 요청 응답에 요청·항목 ID가 없습니다.", "bcm-api 로그를 확인하세요.", False)
    request_item_by_account = {
        item.get("accountId"): item.get("sweepItemId")
        for item in request_items
        if isinstance(item, dict) and isinstance(item.get("accountId"), str) and isinstance(item.get("sweepItemId"), str)
    }
    account_addresses = (
        (fixture["firstAccountId"], fixture["firstAddress"].lower()),
        (fixture["secondAccountId"], fixture["secondAddress"].lower()),
    )
    if set(request_item_by_account) != {account_id for account_id, _ in account_addresses}:
        raise StepFailure("SWEEP_REQUEST_ITEM_MISMATCH", "Sweep 요청 응답의 계정 항목이 일치하지 않습니다.", "bcm-api 로그를 확인하세요.", False)
    prepared = postgres_json(
        environment,
        "SELECT json_build_object("
        "'executionId', execution.swp_exec_id, 'externalTxId', execution.ext_tx_id, 'status', execution.swp_exec_stcd, "
        "'itemCount', count(DISTINCT item.item_seq), "
        "'linkedRequestItems', count(DISTINCT request_item.swp_req_item_id), "
        "'claimedTargets', (SELECT count(*) FROM bcm_swp_trgt target WHERE target.actv_swp_exec_id=execution.swp_exec_id), "
        "'readyItems', count(*) FILTER (WHERE item.swp_item_stcd='READY'), "
        "'expectedActualTotal', (max(item.req_amt) FILTER (WHERE item.acnt_id="
        + sql_literal(fixture["firstAccountId"])
        + "))::text)::text "
        "FROM bcm_swp_exec_l execution "
        "JOIN bcm_swp_item_l item ON item.swp_exec_id=execution.swp_exec_id "
        "JOIN bcm_swp_req_item_l request_item ON request_item.swp_req_item_id=item.swp_req_item_id "
        "WHERE request_item.swp_req_id=" + sql_literal(request_id) + " "
        "GROUP BY execution.swp_exec_id, execution.ext_tx_id, execution.swp_exec_stcd",
        "sweep-prepared-execution",
    )
    execution_id = prepared.get("executionId")
    external_transaction_id = prepared.get("externalTxId")
    expected_actual_total = prepared.get("expectedActualTotal")
    if (
        prepared.get("status") != "READY"
        or prepared.get("itemCount") != 2
        or prepared.get("linkedRequestItems") != 2
        or prepared.get("claimedTargets") != 2
        or prepared.get("readyItems") != 2
        or not isinstance(execution_id, str)
        or not isinstance(external_transaction_id, str)
        or not isinstance(expected_actual_total, str)
    ):
        raise StepFailure(
            "SWEEP_PREPARATION_MISMATCH",
            "실제 후보 선정과 createAndClaim이 요청 두 항목을 READY 실행으로 만들지 못했습니다.",
            "sweep-preparation-once 로그와 sweep 원장을 확인하세요.",
            False,
        )
    return {
        "executionId": execution_id,
        "externalTxId": external_transaction_id,
        "sweepRequestId": request_id,
        "expectedActualTotal": expected_actual_total,
    }


def postgres_database_digest(environment: SmokeEnvironment, log_name: str) -> str:
    snapshot_sql = r"""
\pset tuples_only on
\pset format unaligned
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT format(
    'SELECT value FROM (SELECT %L AS value UNION ALL SELECT ''ROW:'' || row_to_json(t)::text FROM %I t) snapshot ORDER BY value;',
    'TABLE:' || tablename,
    tablename
)
FROM pg_tables
WHERE schemaname = 'public'
  AND left(tablename, 4) = 'bcm_'
ORDER BY tablename
\gexec
COMMIT;
"""
    result = environment.run_command(
        [
            "docker",
            "compose",
            "-p",
            environment.compose_project,
            "-f",
            "config/local-compose.yaml",
            "exec",
            "-T",
            "postgres",
            "psql",
            "-X",
            "-Atq",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            "postgres",
            "-d",
            "bcm",
            "-f",
            "-",
        ],
        log_name,
        input_text=snapshot_sql,
        record_output=False,
    )
    digest = hashlib.sha256(result.stdout.encode("utf-8")).hexdigest()
    environment.ledger.write_runner_log(f"{log_name}=sha256:{digest}")
    return digest


def await_published_source_event(
    environment: SmokeEnvironment,
    vendor_transaction_id: str,
    account_id: str,
) -> str:
    query = (
        "SELECT json_build_object('eventId', (SELECT evnt_id FROM bcm_outbox_l "
        "WHERE topic='deposit-events' AND evnt_stcd='S' "
        "AND payload->>'type'='DEPOSIT' AND payload->>'status'='FINALIZED' "
        f"AND vndr_tx_id={sql_literal(vendor_transaction_id)} AND payload->>'accountId'={sql_literal(account_id)} "
        "ORDER BY evnt_id DESC LIMIT 1))::text"
    )
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        event_id = postgres_json(environment, query, "sweep-source-event").get("eventId")
        if isinstance(event_id, str) and event_id:
            return event_id
        time.sleep(0.5)
    raise StepFailure(
        "SWEEP_SOURCE_EVENT_TIMEOUT",
        "Sweep 요청에 사용할 FINALIZED 입금 event가 발행되지 않았습니다.",
        "bcm-webhook outbox relay와 Kafka 로그를 확인하세요.",
        True,
    )


def complete_daw_event(ledger: RunLedger, event_id: str) -> dict[str, Any]:
    encoded = urllib.parse.quote(event_id, safe="")
    document, headers = http_json("PUT", f"http://127.0.0.1:{SMOKE_API_PORT}/events/{encoded}/completion")
    completion = response_data(document, "DAW event 완료")
    if completion.get("eventId") != event_id or completion.get("consumer") != "DAW_CORE":
        raise StepFailure("EVENT_COMPLETION_MISMATCH", "DAW event 완료 응답의 식별자가 일치하지 않습니다.", "bcm-api 로그를 확인하세요.", False)
    ledger.set_related_id("eventId", event_id)
    ledger.set_related_id("requestId", headers.get("x-request-id"))
    return completion


def accept_daw_sweep_request(
    ledger: RunLedger,
    fixture: dict[str, str],
    source_event_ids: dict[str, str],
) -> dict[str, Any]:
    external_request_id = f"daw-full-sweep-{ledger.run_id}"
    payload = {
        "externalSweepRequestId": external_request_id,
        "network": "LOCAL",
        "symbol": "TUSD",
        "items": [
            {"accountId": fixture["firstAccountId"], "sourceEventIds": [source_event_ids[fixture["firstAccountId"]]]},
            {"accountId": fixture["secondAccountId"], "sourceEventIds": [source_event_ids[fixture["secondAccountId"]]]},
        ],
    }
    first, headers = http_json(
        "POST",
        f"http://127.0.0.1:{SMOKE_API_PORT}/sweeps",
        payload=payload,
        expected_statuses={202},
    )
    accepted = response_data(first, "DAW Sweep 요청")
    replay, _ = http_json(
        "POST",
        f"http://127.0.0.1:{SMOKE_API_PORT}/sweeps",
        payload=payload,
        expected_statuses={202},
    )
    replayed = response_data(replay, "DAW Sweep 멱등 재요청")
    if (
        accepted.get("sweepRequestId") != replayed.get("sweepRequestId")
        or accepted.get("externalSweepRequestId") != external_request_id
        or accepted.get("status") != "ACCEPTED"
        or len(accepted.get("items", [])) != 2
    ):
        raise StepFailure("SWEEP_REQUEST_IDEMPOTENCY_MISMATCH", "DAW Sweep 요청의 접수·멱등 응답이 일치하지 않습니다.", "bcm-api 로그를 확인하세요.", False)
    ledger.set_related_id("sweepRequestId", accepted.get("sweepRequestId"))
    ledger.set_related_id("requestId", headers.get("x-request-id"))
    return accepted


def consume_sweep_result_events(
    environment: SmokeEnvironment,
    ledger: RunLedger,
    fixture: dict[str, str],
    request_id: str,
) -> list[dict[str, Any]]:
    probe_environment = environment.environment()
    probe_environment.update(
        {
            "BCM_KAFKA_PROBE_BOOTSTRAP": f"127.0.0.1:{SMOKE_KAFKA_PORT}",
            "BCM_KAFKA_PROBE_TOPIC": "sweep-events",
            "BCM_KAFKA_PROBE_GROUP_ID": f"bcm-system-test-sweep-{ledger.run_id}",
            "BCM_KAFKA_PROBE_MAX_MESSAGES": "2",
            "BCM_KAFKA_PROBE_TIMEOUT_MILLIS": "15000",
        }
    )
    result = environment.run_command(
        ["./gradlew", "--no-daemon", ":blockchain-manager-infra:messaging:consumeLocalKafka"],
        "sweep-kafka-events",
        environment=probe_environment,
        timeout=30,
    )
    documents: list[dict[str, Any]] = []
    for line in result.stdout.splitlines():
        try:
            candidate = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(candidate, dict) and candidate.get("sweepRequestId") == request_id:
            documents.append(candidate)
    by_account = {document.get("accountId"): document for document in documents}
    expected_outcomes = {
        fixture["firstAccountId"]: "SUCCEEDED",
        fixture["secondAccountId"]: "FAILED",
    }
    if set(by_account) != set(expected_outcomes) or any(
        by_account[account_id].get("chainStatus") != "FINALIZED"
        or by_account[account_id].get("itemOutcome") != outcome
        for account_id, outcome in expected_outcomes.items()
    ):
        raise StepFailure(
            "SWEEP_KAFKA_EVENT_MISMATCH",
            "부분 성공 Sweep의 항목별 Kafka event가 chain 상태와 item 결과를 분리하지 못했습니다.",
            f"./scripts/system-test.sh logs {ledger.run_id} sweep-kafka-events 결과를 확인하세요.",
            False,
        )
    return [by_account[fixture["firstAccountId"]], by_account[fixture["secondAccountId"]]]


def verify_sweep_admin_completion(ledger: RunLedger, request_id: str) -> None:
    encoded = urllib.parse.quote(request_id, safe="")
    document, headers = http_json("GET", f"http://127.0.0.1:{SMOKE_ADMIN_PORT}/bff/admin/sweeps/{encoded}")
    data = document.get("data")
    items = data.get("items") if isinstance(data, dict) else None
    if (
        not isinstance(data, dict)
        or data.get("status") != "PARTIAL"
        or not isinstance(items, list)
        or len(items) != 2
        or any(
            not isinstance(item, dict)
            or len(item.get("resultEvents", [])) != 1
            or item["resultEvents"][0].get("dawCompletedAt") is None
            for item in items
        )
    ):
        raise StepFailure("SWEEP_ADMIN_COMPLETION_MISMATCH", "Admin이 Sweep 결과 event와 DAW 완료를 연결하지 못했습니다.", "bcm-api와 bcm-admin 로그를 확인하세요.", False)
    ledger.set_related_id("requestId", headers.get("x-request-id"))


def verify_daw_sweep_and_bat(
    environment: SmokeEnvironment,
    ledger: RunLedger,
    first_account_id: str,
    first_address: str,
) -> None:
    fixture = prepare_daw_sweep_fixture(environment, ledger, first_account_id, first_address)
    manifest = json.loads(fixture["manifest"])
    execution_environment = sweep_bat_environment(
        environment,
        "sweep-execution-once",
        fixture["operatorAccountId"],
        fixture["omnibusAccountId"],
        manifest,
    )

    source_transactions: dict[str, str] = {}
    for label, account_id, address in (
        ("daw-sweep-source-a", fixture["firstAccountId"], fixture["firstAddress"]),
        ("daw-sweep-source-b", fixture["secondAccountId"], fixture["secondAddress"]),
    ):
        vendor_transaction_id = inject_local_deposit(ledger, address, label)
        finalize_local_deposit(vendor_transaction_id)
        await_bcm_finalized(ledger, vendor_transaction_id)
        source_transactions[account_id] = vendor_transaction_id

    before = postgres_scalar(environment, "SELECT count(*) FROM bcm_swp_exec_l", "sweep-no-request-before")
    target_count = postgres_scalar(
        environment,
        "SELECT count(*) FROM bcm_swp_trgt WHERE acnt_id IN ("
        + ",".join(sql_literal(account_id) for account_id in source_transactions)
        + ") AND ntwk_cd='LOCAL' AND tkn_smbl='TUSD'",
        "sweep-no-request-targets",
    )
    environment.run_bat_job(
        ["./gradlew", "--no-daemon", ":blockchain-manager-app:bcm-bat:bootRun"],
        "sweep-no-request-once",
        environment=execution_environment,
    )
    after = postgres_scalar(environment, "SELECT count(*) FROM bcm_swp_exec_l", "sweep-no-request-after")
    if target_count != "0" or before != after:
        raise StepFailure("SWEEP_WITHOUT_DAW_REQUEST", "FINALIZED 입금만으로 신규 Sweep 실행이 생성됐습니다.", "Webhook target 생성과 BAT 후보 조건을 확인하세요.", False)

    source_event_ids = {
        account_id: await_published_source_event(environment, vendor_transaction_id, account_id)
        for account_id, vendor_transaction_id in source_transactions.items()
    }
    for event_id in source_event_ids.values():
        complete_daw_event(ledger, event_id)
    request = accept_daw_sweep_request(ledger, fixture, source_event_ids)
    preparation_environment = sweep_bat_environment(
        environment,
        "sweep-preparation-once",
        fixture["operatorAccountId"],
        fixture["omnibusAccountId"],
        manifest,
    )
    environment.run_bat_job(
        ["./gradlew", "--no-daemon", ":blockchain-manager-app:bcm-bat:bootRun"],
        "sweep-preparation-once",
        environment=preparation_environment,
    )
    execution_fixture = prepared_execution_for_request(environment, fixture, request)
    # 실제 createAndClaim 뒤 allowance가 줄어드는 경합을 재현해 두 leg 중 하나만 실패시킨다.
    approve_sweep_contract(
        fixture["secondAddress"],
        fixture["tokenAddress"],
        fixture["sweepContractAddress"],
        10_000_000,
    )
    environment.run_bat_job(
        ["./gradlew", "--no-daemon", ":blockchain-manager-app:bcm-bat:bootRun"],
        "sweep-execution-once",
        environment=execution_environment,
    )
    execution = postgres_json(
        environment,
        "SELECT json_build_object('status', swp_exec_stcd, 'externalTxId', ext_tx_id, "
        "'vendorTxId', vndr_tx_id)::text FROM bcm_swp_exec_l WHERE swp_exec_id = "
        + sql_literal(execution_fixture["executionId"]),
        "sweep-submission-state",
    )
    vendor_transaction_id = execution.get("vendorTxId")
    if execution.get("status") != "SUBMITTED" or not isinstance(vendor_transaction_id, str) or not vendor_transaction_id:
        raise StepFailure(
            "SWEEP_NOT_SUBMITTED",
            "현재 stack의 sweep 실행이 SUBMITTED로 수렴하지 않았습니다.",
            f"./scripts/system-test.sh logs {ledger.run_id} sweep-execution-once 결과를 확인하세요.",
            True,
        )
    ledger.set_related_id("executionId", execution_fixture["executionId"])
    ledger.set_related_id("externalTxId", str(execution.get("externalTxId")))
    ledger.set_related_id("vendorTxId", vendor_transaction_id)

    encoded = urllib.parse.quote(vendor_transaction_id, safe="")
    first, _ = http_json("POST", f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/transactions/{encoded}/advance")
    completed, _ = http_json("POST", f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/transactions/{encoded}/advance")
    if first.get("status") != "CONFIRMING" or completed.get("status") != "COMPLETED":
        raise StepFailure("SWEEP_VENDOR_NOT_COMPLETED", "batch sweep이 CONFIRMING→COMPLETED로 전이하지 않았습니다.", "Stub 로그를 확인하세요.", True)

    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        observed = postgres_json(
            environment,
            "SELECT json_build_object('status', swp_exec_stcd, 'txHash', tx_hash)::text "
            "FROM bcm_swp_exec_l WHERE swp_exec_id = " + sql_literal(execution_fixture["executionId"]),
            "sweep-webhook-state",
        )
        if observed.get("status") == "RECONCILING":
            ledger.set_related_id("txHash", str(observed.get("txHash")))
            break
        time.sleep(0.5)
    else:
        raise StepFailure("SWEEP_WEBHOOK_TIMEOUT", "sweep Webhook이 RECONCILING으로 수렴하지 않았습니다.", "bcm-api 로그를 확인하세요.", True)

    reconciliation_environment = sweep_bat_environment(
        environment,
        "sweep-reconciliation-once",
        fixture["operatorAccountId"],
        fixture["omnibusAccountId"],
        manifest,
    )
    environment.run_bat_job(
        ["./gradlew", "--no-daemon", ":blockchain-manager-app:bcm-bat:bootRun"],
        "sweep-reconciliation-once",
        environment=reconciliation_environment,
    )
    result = postgres_json(
        environment,
        "SELECT json_build_object("
        "'status', execution.swp_exec_stcd, 'actualTotal', execution.actl_tot_amt::text, 'txHash', execution.tx_hash, "
        "'succeeded', (SELECT count(*) FROM bcm_swp_item_l item WHERE item.swp_exec_id=execution.swp_exec_id AND item.swp_item_stcd='SUCCEEDED'), "
        "'failed', (SELECT count(*) FROM bcm_swp_item_l item WHERE item.swp_exec_id=execution.swp_exec_id AND item.swp_item_stcd='FAILED'), "
        "'claimed', (SELECT count(*) FROM bcm_swp_trgt target WHERE target.actv_swp_exec_id=execution.swp_exec_id), "
        "'submissionStatus', submission.sbmt_stcd, 'submissionType', submission.tx_dvcd, "
        "'requestStatus', (SELECT swp_req_stcd FROM bcm_swp_req_l WHERE swp_req_id="
        + sql_literal(execution_fixture["sweepRequestId"])
        + "), 'requestCompleted', (SELECT count(*) FROM bcm_swp_req_item_l WHERE swp_req_id="
        + sql_literal(execution_fixture["sweepRequestId"])
        + " AND swp_req_item_stcd='COMPLETED'), 'requestPending', (SELECT count(*) FROM bcm_swp_req_item_l WHERE swp_req_id="
        + sql_literal(execution_fixture["sweepRequestId"])
        + " AND swp_req_item_stcd='PENDING'), "
        "'customerEvents', (SELECT count(*) FROM bcm_outbox_l event WHERE event.vndr_tx_id=execution.vndr_tx_id "
        "AND event.topic='sweep-events'))::text "
        "FROM bcm_swp_exec_l execution JOIN bcm_sbmt_l submission ON submission.ext_tx_id=execution.ext_tx_id "
        "WHERE execution.swp_exec_id=" + sql_literal(execution_fixture["executionId"]),
        "sweep-reconciliation-result",
    )
    expected = {
        "status": "PARTIAL",
        "succeeded": 1,
        "failed": 1,
        "claimed": 0,
        "submissionStatus": "SUBMITTED",
        "submissionType": "SWEEP_BATCH",
        "requestStatus": "PARTIAL",
        "requestCompleted": 1,
        "requestPending": 1,
        "customerEvents": 2,
    }
    mismatches = {key: (result.get(key), value) for key, value in expected.items() if result.get(key) != value}
    try:
        if Decimal(str(result.get("actualTotal"))) != Decimal(execution_fixture["expectedActualTotal"]):
            mismatches["actualTotal"] = (result.get("actualTotal"), execution_fixture["expectedActualTotal"])
    except InvalidOperation:
        mismatches["actualTotal"] = (result.get("actualTotal"), execution_fixture["expectedActualTotal"])
    if mismatches:
        raise StepFailure(
            "SWEEP_RECONCILIATION_MISMATCH",
            "부분 성공 sweep의 실행·항목·제출·고객 이벤트 대사가 일치하지 않습니다.",
            f"./scripts/system-test.sh logs {ledger.run_id} sweep-reconciliation-result 결과를 확인하세요.",
            False,
        )
    ledger.set_related_id("txHash", str(result.get("txHash")))
    events = consume_sweep_result_events(environment, ledger, fixture, execution_fixture["sweepRequestId"])
    completions = [complete_daw_event(ledger, str(event["eventId"])) for event in events]
    replayed = complete_daw_event(ledger, str(events[0]["eventId"]))
    if (
        any(completion.get("sweepRequestId") != execution_fixture["sweepRequestId"] for completion in completions)
        or replayed.get("completedAt") != completions[0].get("completedAt")
    ):
        raise StepFailure("SWEEP_EVENT_COMPLETION_MISMATCH", "Sweep event 완료의 요청 연결 또는 멱등 시각이 일치하지 않습니다.", "bcm-api 로그를 확인하세요.", False)
    verify_sweep_admin_completion(ledger, execution_fixture["sweepRequestId"])


def kafka_total_offset(environment: SmokeEnvironment, topic: str) -> int:
    probe_environment = environment.environment()
    probe_environment.update(
        {
            "BCM_KAFKA_PROBE_BOOTSTRAP": f"127.0.0.1:{SMOKE_KAFKA_PORT}",
            "BCM_KAFKA_PROBE_TOPIC": topic,
        }
    )
    result = environment.run_command(
        ["./gradlew", "--no-daemon", ":blockchain-manager-infra:messaging:inspectLocalKafkaOffsets"],
        f"reset-offset-{topic}",
        environment=probe_environment,
        timeout=60,
    )
    for line in reversed(result.stdout.splitlines()):
        try:
            document = json.loads(line)
        except json.JSONDecodeError:
            continue
        if document.get("topic") == topic and isinstance(document.get("totalEndOffset"), int):
            return document["totalEndOffset"]
    raise StepFailure(
        "KAFKA_OFFSET_PROBE_EMPTY",
        f"{topic} Kafka end offset을 읽지 못했습니다.",
        f"reset-offset-{topic} 로그를 확인하세요.",
        True,
    )


def verify_reset_isolation(environment: SmokeEnvironment) -> None:
    pending = postgres_scalar(environment, "SELECT count(*) FROM bcm_outbox_l WHERE evnt_stcd = 'P'", "reset-pending-outbox")
    if pending != "0":
        raise StepFailure(
            "RESET_BASELINE_NOT_STABLE",
            "reset 전 미발행 outbox가 남아 있어 Kafka 불변을 판정할 수 없습니다.",
            "bcm-api relay 로그를 확인하세요.",
            True,
        )
    before = postgres_database_digest(environment, "reset-digest-before")
    topics = ("deposit-events", "withdrawal-events", "internal-events", "sweep-events")
    offsets_before = {topic: kafka_total_offset(environment, topic) for topic in topics}
    http_json("POST", f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/reset")
    time.sleep(1)
    after = postgres_database_digest(environment, "reset-digest-after")
    if before != after:
        raise StepFailure(
            "RESET_CHANGED_BCM_DATABASE",
            "Stub·Anvil reset이 BCM PostgreSQL 원장을 변경했습니다.",
            "reset 전후 probe와 infra 로그를 확인하세요.",
            False,
        )
    offsets_after = {topic: kafka_total_offset(environment, topic) for topic in topics}
    if offsets_before != offsets_after:
        raise StepFailure(
            "RESET_CHANGED_KAFKA_OFFSETS",
            "Stub·Anvil reset이 BCM Kafka end offset을 변경했습니다.",
            "reset 전후 offset probe와 bcm-api 로그를 확인하세요.",
            False,
        )


def smoke_suite(ledger: RunLedger, keep_on_failure: bool) -> None:
    environment: SmokeEnvironment | None = None
    failed = True
    context: dict[str, str] = {}
    try:
        def start_environment() -> None:
            nonlocal environment
            environment = SmokeEnvironment(ledger, keep_on_failure)
            environment.start()

        run_step(
            ledger,
            2,
            "dedicated-environment",
            "전용 PostgreSQL·Kafka와 로컬 component 기동",
            start_environment,
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )
        assert environment is not None
        run_step(
            ledger,
            3,
            "catalog-sync",
            "Fireblocks 블록체인 카탈로그 1회 동기화",
            environment.sync_catalog,
            classification=("SIMULATED_VENDOR",),
        )
        run_step(
            ledger,
            4,
            "asset-adoption",
            "Admin 로컬 네트워크 채택·자산 카탈로그 동기화·매핑 등록",
            lambda: adopt_local_asset(ledger, environment.sync_asset_catalog),
            classification=("SIMULATED_VENDOR",),
        )

        def create_destination() -> None:
            account_id, address = create_deposit_destination(ledger)
            context["accountId"] = account_id
            context["address"] = address

        run_step(
            ledger,
            5,
            "account-address",
            "BCM 계정·입금 주소 생성",
            create_destination,
            classification=("SIMULATED_VENDOR",),
        )

        def inject_deposit() -> None:
            context["vendorTxId"] = inject_local_deposit(ledger, context["address"])

        run_step(
            ledger,
            6,
            "deposit-injected",
            "Anvil ERC-20 입금과 서명 Webhook 주입",
            inject_deposit,
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )
        run_step(
            ledger,
            7,
            "deposit-finalized",
            "BCM 입금 FINALIZED 수렴",
            lambda: (finalize_local_deposit(context["vendorTxId"]), await_bcm_finalized(ledger, context["vendorTxId"])),
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )
        run_step(
            ledger,
            8,
            "kafka-event",
            "고객 Kafka FINALIZED event 검증",
            lambda: verify_kafka_event(environment, ledger, context["accountId"]),
            classification=("REAL_LOCAL",),
        )
        run_step(
            ledger,
            9,
            "admin-investigation",
            "Admin 거래 조사 식별자 연결 검증",
            lambda: await_admin_investigation(ledger, context["vendorTxId"]),
            classification=("REAL_LOCAL",),
        )
        run_step(
            ledger,
            10,
            "cleanup-components",
            "전용 component 종료와 잔존 리소스 검증",
            lambda: environment.cleanup(failed=False),
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )
        failed = False
    finally:
        if failed and environment is not None:
            environment.cleanup(failed=True, strict=False)


def verify_component_independence(
    environment: SmokeEnvironment,
    ledger: RunLedger,
    address: str,
) -> None:
    environment.run_command(["./scripts/local.sh", "stop", "api"], "api-independent-stop")
    ledger.set_component("bcm-api", "DOWN")
    if environment.http_ready(f"http://127.0.0.1:{SMOKE_API_MANAGEMENT_PORT}/actuator/health"):
        raise StepFailure("API_STOP_FAILED", "개별 종료 뒤 BCM API가 계속 응답합니다.", "api-independent-stop 로그를 확인하세요.", False)
    if not environment.http_ready(f"http://127.0.0.1:{SMOKE_WEBHOOK_MANAGEMENT_PORT}/actuator/health"):
        raise StepFailure("WEBHOOK_COUPLED_TO_API", "BCM API 종료가 Webhook health를 함께 내렸습니다.", "webhook 로그를 확인하세요.", False)
    environment.run_command(["./scripts/local.sh", "up", "stub"], "api-independent-restart")
    ledger.set_component("bcm-api", "UP")
    if not environment.http_ready(f"http://127.0.0.1:{SMOKE_API_MANAGEMENT_PORT}/actuator/health"):
        raise StepFailure("API_RESTART_FAILED", "BCM API만 재기동하지 못했습니다.", "api-independent-restart 로그를 확인하세요.", True)

    environment.run_command(["./scripts/local.sh", "stop", "webhook"], "webhook-independent-stop")
    ledger.set_component("bcm-webhook", "DOWN")
    if environment.http_ready(f"http://127.0.0.1:{SMOKE_WEBHOOK_MANAGEMENT_PORT}/actuator/health"):
        raise StepFailure("WEBHOOK_STOP_FAILED", "개별 종료 뒤 BCM Webhook이 계속 응답합니다.", "webhook-independent-stop 로그를 확인하세요.", False)
    if not environment.http_ready(f"http://127.0.0.1:{SMOKE_API_MANAGEMENT_PORT}/actuator/health"):
        raise StepFailure("API_COUPLED_TO_WEBHOOK", "Webhook 종료가 BCM API health를 함께 내렸습니다.", "api 로그를 확인하세요.", False)

    vendor_tx_id = inject_local_deposit(ledger, address, "component-restart-deposit")
    finalize_local_deposit(vendor_tx_id)
    environment.run_command(["./scripts/local.sh", "up", "stub"], "webhook-independent-restart")
    ledger.set_component("bcm-webhook", "UP")
    resent, _ = http_json(
        "POST",
        f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/webhooks/local-webhook/resend-failed",
    )
    if not isinstance(resent.get("total"), int) or resent["total"] < 1:
        raise StepFailure("WEBHOOK_BACKLOG_NOT_FOUND", "Webhook 중단 중 실패 queue가 만들어지지 않았습니다.", "Stub 로그를 확인하세요.", False)
    await_bcm_finalized(ledger, vendor_tx_id)


def full_suite(ledger: RunLedger, keep_on_failure: bool) -> None:
    environment: SmokeEnvironment | None = None
    failed = True
    context: dict[str, str] = {}
    try:
        def start_environment() -> None:
            nonlocal environment
            environment = SmokeEnvironment(ledger, keep_on_failure)
            environment.start()

        run_step(
            ledger,
            2,
            "dedicated-environment",
            "전용 전체 테스트 component 기동",
            start_environment,
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )
        assert environment is not None
        run_step(
            ledger,
            3,
            "catalog-sync",
            "Fireblocks 블록체인 카탈로그 1회 동기화",
            environment.sync_catalog,
            classification=("SIMULATED_VENDOR",),
        )
        run_step(
            ledger,
            4,
            "asset-adoption",
            "Admin 로컬 네트워크 채택·자산 카탈로그 동기화·매핑 등록",
            lambda: adopt_local_asset(ledger, environment.sync_asset_catalog),
            classification=("SIMULATED_VENDOR",),
        )

        def create_destination() -> None:
            account_id, address = create_deposit_destination(ledger)
            manifest = local_manifest(environment)
            omnibus_address = manifest.get("omnibusAddress")
            if not isinstance(omnibus_address, str) or not omnibus_address:
                raise StepFailure(
                    "LOCAL_OMNIBUS_NOT_FOUND",
                    "로컬 체인 manifest에 omnibus 주소가 없습니다.",
                    "anvil 기동 로그를 확인하세요.",
                    False,
                )
            context.update(accountId=account_id, address=address, omnibusAddress=omnibus_address)

        run_step(
            ledger,
            5,
            "account-address",
            "출금 원천 계정·주소 생성",
            create_destination,
            classification=("SIMULATED_VENDOR",),
        )

        def prepare_funded_source() -> None:
            vendor_tx_id = inject_local_deposit(ledger, context["address"])
            finalize_local_deposit(vendor_tx_id)
            await_bcm_finalized(ledger, vendor_tx_id)

        run_step(
            ledger,
            6,
            "deposit-finalized",
            "실제 ERC-20 출금 원천 입금",
            prepare_funded_source,
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )

        def gasless_withdrawal() -> None:
            http_json(
                "POST",
                f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/faults/chain/native-balance",
                payload={"address": context["address"], "balanceWei": "0"},
            )
            vendor_tx_id = submit_gasless_withdrawal(
                ledger,
                context["accountId"],
                context["omnibusAddress"],
                f"full-gasless-{ledger.run_id}",
            )
            if vendor_tx_id is None:
                raise StepFailure("INVALID_WITHDRAWAL_RESPONSE", "gasless 출금 ID가 없습니다.", "bcm-api 로그를 확인하세요.", False)
            finalize_local_transaction(ledger, vendor_tx_id)

        run_step(
            ledger,
            7,
            "component-independence",
            "API·Webhook 개별 중단·재기동과 Webhook backlog 회수",
            lambda: verify_component_independence(environment, ledger, context["address"]),
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )

        run_step(
            ledger,
            8,
            "gasless-withdrawal",
            "native 0 Universal Gasless 출금",
            gasless_withdrawal,
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )

        def rate_limit_recovery() -> None:
            configure_transaction_fault(429, after_commit=False)
            vendor_tx_id = submit_gasless_withdrawal(
                ledger,
                context["accountId"],
                context["omnibusAddress"],
                f"full-rate-limit-{ledger.run_id}",
            )
            if vendor_tx_id is None:
                raise StepFailure("RATE_LIMIT_NOT_RECOVERED", "429 재시도가 거래 ID로 수렴하지 않았습니다.", "bcm-api 로그를 확인하세요.", True)
            finalize_local_transaction(ledger, vendor_tx_id)

        run_step(
            ledger,
            9,
            "rate-limit-recovery",
            "429 백오프 재시도와 단일 제출",
            rate_limit_recovery,
            classification=("SIMULATED_VENDOR",),
        )

        def timeout_recovery() -> None:
            external_tx_id = f"full-timeout-{ledger.run_id}"
            configure_transaction_fault(504, after_commit=False, delay_millis=1_000)
            failed_tx_id = submit_gasless_withdrawal(
                ledger,
                context["accountId"],
                context["omnibusAddress"],
                external_tx_id,
                expected_statuses={500},
            )
            if failed_tx_id is not None:
                raise StepFailure("TIMEOUT_NOT_OBSERVED", "제출 전 timeout이 실패로 관찰되지 않았습니다.", "bcm-api 로그를 확인하세요.", False)
            time.sleep(3.2)
            vendor_tx_id = submit_gasless_withdrawal(
                ledger,
                context["accountId"],
                context["omnibusAddress"],
                external_tx_id,
            )
            if vendor_tx_id is None:
                raise StepFailure("TIMEOUT_NOT_RECOVERED", "claim 만료 뒤 timeout 제출이 복구되지 않았습니다.", "bcm-api 로그를 확인하세요.", True)
            finalize_local_transaction(ledger, vendor_tx_id)

        run_step(
            ledger,
            10,
            "timeout-recovery",
            "제출 전 timeout과 claim 만료 복구",
            timeout_recovery,
            classification=("SIMULATED_VENDOR",),
        )

        def response_loss_recovery() -> None:
            configure_transaction_fault(400, after_commit=True)
            vendor_tx_id = submit_gasless_withdrawal(
                ledger,
                context["accountId"],
                context["omnibusAddress"],
                f"full-response-loss-{ledger.run_id}",
            )
            if vendor_tx_id is None:
                raise StepFailure("RESPONSE_LOSS_NOT_RECOVERED", "응답 유실 거래를 externalTxId로 회수하지 못했습니다.", "bcm-api 로그를 확인하세요.", True)
            finalize_local_transaction(ledger, vendor_tx_id)

        run_step(
            ledger,
            11,
            "response-loss-recovery",
            "커밋 뒤 응답 유실과 externalTxId 회수",
            response_loss_recovery,
            classification=("SIMULATED_VENDOR",),
        )

        def create_out_of_order_webhook() -> None:
            http_json("POST", f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/faults/webhooks/next-delivery")
            vendor_tx_id = submit_gasless_withdrawal(
                ledger,
                context["accountId"],
                context["omnibusAddress"],
                f"full-webhook-recovery-{ledger.run_id}",
            )
            if vendor_tx_id is None:
                raise StepFailure("INVALID_WITHDRAWAL_RESPONSE", "Webhook 복구 거래 ID가 없습니다.", "bcm-api 로그를 확인하세요.", False)
            context["webhookVendorTxId"] = vendor_tx_id
            encoded = urllib.parse.quote(vendor_tx_id, safe="")
            confirming, _ = http_json(
                "POST",
                f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/transactions/{encoded}/advance",
            )
            if confirming.get("status") != "CONFIRMING":
                raise StepFailure("WEBHOOK_ORDER_SETUP_FAILED", "역순 Webhook 조건을 만들지 못했습니다.", "fireblocks-stub 로그를 확인하세요.", True)

        run_step(
            ledger,
            12,
            "webhook-out-of-order",
            "SUBMITTED 유실 뒤 CONFIRMING 역순 전달",
            create_out_of_order_webhook,
            classification=("SIMULATED_VENDOR",),
        )

        def recover_webhook() -> None:
            resent, _ = http_json(
                "POST",
                f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/webhooks/local-webhook/resend-failed",
            )
            if resent.get("total") != 1:
                raise StepFailure("WEBHOOK_RESEND_MISMATCH", "실패 Webhook 재전송 건수가 1이 아닙니다.", "fireblocks-stub 로그를 확인하세요.", False)
            encoded = urllib.parse.quote(context["webhookVendorTxId"], safe="")
            completed, _ = http_json(
                "POST",
                f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/transactions/{encoded}/advance",
            )
            if completed.get("status") != "COMPLETED":
                raise StepFailure("WEBHOOK_RECOVERY_NOT_COMPLETED", "복구 거래가 COMPLETED가 아닙니다.", "fireblocks-stub 로그를 확인하세요.", True)
            await_bcm_finalized(ledger, context["webhookVendorTxId"], "0.1")

        run_step(
            ledger,
            13,
            "webhook-recovery",
            "유실 Webhook resend_failed 복구",
            recover_webhook,
            classification=("SIMULATED_VENDOR",),
        )

        def verify_webhook_duplicate() -> None:
            before = postgres_scalar(environment, "SELECT count(*) FROM bcm_whk_l", "webhook-duplicate-before")
            http_json("POST", f"http://127.0.0.1:{SMOKE_STUB_PORT}/__stub/webhooks/notifications/redeliver-last")
            time.sleep(1)
            after = postgres_scalar(environment, "SELECT count(*) FROM bcm_whk_l", "webhook-duplicate-after")
            if before != after:
                raise StepFailure("WEBHOOK_DUPLICATE_INSERTED", "중복 Webhook이 새 inbox 행을 만들었습니다.", "bcm-api 로그를 확인하세요.", False)

        run_step(
            ledger,
            14,
            "webhook-duplicate",
            "동일 서명 Webhook 중복 수신 dedup",
            verify_webhook_duplicate,
            classification=("SIMULATED_VENDOR",),
        )
        run_step(
            ledger,
            15,
            "daw-sweep-event-completion",
            "DAW 요청 기반 batch sweep 부분 성공·Kafka·완료 확인",
            lambda: verify_daw_sweep_and_bat(environment, ledger, context["accountId"], context["address"]),
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )
        run_step(
            ledger,
            16,
            "admin-investigation",
            "Admin 실패·복구 거래 추적",
            lambda: await_admin_investigation(ledger, context["webhookVendorTxId"], "0.1"),
            classification=("REAL_LOCAL",),
        )
        run_step(
            ledger,
            17,
            "reset-isolation",
            "Stub·Anvil reset의 BCM 원장 격리",
            lambda: verify_reset_isolation(environment),
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )
        run_step(
            ledger,
            18,
            "cleanup-components",
            "전용 component 종료와 잔존 리소스 검증",
            lambda: environment.cleanup(failed=False),
            classification=("REAL_LOCAL", "SIMULATED_VENDOR"),
        )
        failed = False
    finally:
        if failed and environment is not None:
            environment.cleanup(failed=True, strict=False)


def run_suite(suite: str, *, keep_on_failure: bool) -> int:
    root = artifact_root()
    prune_completed_runs(root)
    configured_run_id = os.environ.get("BCM_SYSTEM_TEST_RUN_ID")
    run_id = configured_run_id or f"{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{secrets.token_hex(4)}"
    validate_identifier(run_id, RUN_ID_PATTERN, "runId")

    internal_testing = os.environ.get("BCM_SYSTEM_TEST_INTERNAL_TESTING") == "true"
    internal_scenario = os.environ.get("BCM_SYSTEM_TEST_INTERNAL_SCENARIO")
    if internal_scenario and not internal_testing:
        raise RunnerError("오류: 내부 테스트 시나리오는 계약 테스트에서만 사용할 수 있습니다.")
    if internal_testing and internal_scenario not in INTERNAL_SCENARIOS:
        raise RunnerError("오류: 알 수 없는 내부 테스트 시나리오입니다.")

    total_steps = (
        SMOKE_STEP_COUNT
        if suite == "SMOKE" and not internal_testing
        else FULL_STEP_COUNT
        if suite == "FULL" and not internal_testing
        else 2
    )
    ledger = RunLedger(root, run_id, suite, total_steps=total_steps)
    ledger.start()
    try:
        run_step(
            ledger,
            1,
            "ledger-initialized",
            "실행 원장 초기화",
            lambda: None,
            classification=("REAL_LOCAL",),
        )
        if internal_testing:
            assert internal_scenario is not None
            run_step(
                ledger,
                2,
                "internal-scenario",
                f"테스트 시나리오 {internal_scenario}",
                lambda: internal_test_action(ledger, internal_scenario),
                classification=("REAL_LOCAL",),
            )
        elif suite == "SMOKE":
            smoke_suite(ledger, keep_on_failure)
        else:
            full_suite(ledger, keep_on_failure)
        ledger.finish()
        return 0
    except StepAborted as failure:
        print_failure_diagnostics(ledger, failure)
        return 130
    except StepFailure as failure:
        print_failure_diagnostics(ledger, failure)
        return 1


def print_failure_diagnostics(ledger: RunLedger, failure: StepFailure | StepAborted) -> None:
    failure_payload = ledger.snapshot.get("failure")
    failed_step = failure_payload.get("failedStep", "unknown") if isinstance(failure_payload, dict) else "unknown"
    print(f"runId: {ledger.run_id}", file=sys.stderr)
    print(f"실패 단계: {failed_step}", file=sys.stderr)
    print(f"오류: {failure.code} — {failure.message}", file=sys.stderr)
    print(f"재시도 가능: {'예' if failure.retryable else '아니오'}", file=sys.stderr)
    print(f"다음 조치: {failure.next_action}", file=sys.stderr)
    print(f"Admin: {DEFAULT_ADMIN_BASE_URL}/{ledger.run_id}", file=sys.stderr)
    print(f"artifact: {ledger.run_dir}", file=sys.stderr)


def command_status(arguments: list[str]) -> int:
    if len(arguments) > 1:
        raise RunnerError("오류: status는 runId를 하나만 받습니다.")
    root = artifact_root()
    run_id = arguments[0] if arguments else latest_run_id(root)
    run_dir = run_directory(root, run_id, must_exist=True)
    snapshot = load_snapshot(run_dir)
    progress = snapshot.get("progress", {})
    completed = progress.get("completedSteps", 0)
    total = progress.get("totalSteps", 0)
    percent = progress.get("percent", 0)
    print(f"{run_id} {snapshot.get('state', 'UNKNOWN')} {percent}% ({completed}/{total})")
    current_step = snapshot.get("currentStep")
    if current_step:
        print(f"현재 단계: {current_step}")
    failure = snapshot.get("failure")
    if isinstance(failure, dict):
        print(f"오류: {failure.get('code')} — {failure.get('message')}")
        print(f"다음 조치: {failure.get('nextAction')}")
    print(f"artifact: {run_dir}")
    return 0


def safe_log_path(run_dir: Path, component: str) -> Path:
    validate_identifier(component, COMPONENT_PATTERN, "component")
    logs_dir = run_dir / "logs"
    path = logs_dir / f"{component}.log"
    if path.is_symlink() or not path.is_file() or path.resolve().parent != logs_dir.resolve():
        raise RunnerError(f"오류: component 로그를 찾을 수 없습니다: {component}")
    mode = path.stat().st_mode
    if not stat.S_ISREG(mode):
        raise RunnerError(f"오류: 안전하지 않은 component 로그입니다: {component}")
    return path


def command_logs(arguments: list[str]) -> int:
    if len(arguments) > 2:
        raise RunnerError("오류: logs는 runId와 component를 하나씩만 받습니다.")
    root = artifact_root()
    run_id = arguments[0] if arguments else latest_run_id(root)
    run_dir = run_directory(root, run_id, must_exist=True)
    load_snapshot(run_dir)
    if len(arguments) == 2:
        components = [validate_identifier(arguments[1], COMPONENT_PATTERN, "component")]
    else:
        logs_dir = run_dir / "logs"
        components = sorted(
            path.stem
            for path in logs_dir.glob("*.log")
            if not path.is_symlink() and COMPONENT_PATTERN.fullmatch(path.stem)
        )
    if not components:
        raise RunnerError(f"오류: 저장된 component 로그가 없습니다: {run_id}")
    for index, component in enumerate(components):
        path = safe_log_path(run_dir, component)
        if len(components) > 1:
            if index:
                print()
            print(f"== {component} ==")
        descriptor = os.open(path, os.O_RDONLY | no_follow_flag())
        with os.fdopen(descriptor, "r", encoding="utf-8", errors="replace") as handle:
            sys.stdout.write(handle.read())
    return 0


def append_cleanup_event(run_dir: Path, snapshot: dict[str, Any]) -> None:
    events_path = run_dir / "events.jsonl"
    sequence = 0
    if events_path.is_file() and not events_path.is_symlink():
        for line in events_path.read_text(encoding="utf-8").splitlines():
            try:
                sequence = max(sequence, int(json.loads(line).get("sequence", 0)))
            except (json.JSONDecodeError, TypeError, ValueError):
                continue
    event = {
        "schemaVersion": 1,
        "sequence": sequence + 1,
        "occurredAt": utc_now(),
        "runId": snapshot["runId"],
        "type": "RETAINED_ENVIRONMENT_CLEANED",
        "runState": snapshot["state"],
    }
    descriptor = os.open(events_path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | no_follow_flag(), 0o600)
    with os.fdopen(descriptor, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def cleanup_retained_environment(root: Path, run_dir: Path, snapshot: dict[str, Any], *, internal_testing: bool) -> None:
    run_id = snapshot["runId"]
    environment = smoke_process_environment(root, run_id)
    state_dir = smoke_runtime_state_directory(run_id)
    cleanup_log = run_dir / "logs" / "retained-cleanup.log"
    if not internal_testing:
        runtime_root = state_dir.parent
        if (
            runtime_root.is_symlink()
            or not runtime_root.is_dir()
            or state_dir.is_symlink()
            or not state_dir.is_dir()
            or state_dir.resolve().parent != runtime_root.resolve()
        ):
            raise RunnerError("오류: 보존된 runtime 상태 디렉터리를 안전하게 확인할 수 없습니다.")
        commands = [
            ["./scripts/local.sh", "down"],
            [
                "docker",
                "compose",
                "-p",
                smoke_compose_project(run_id),
                "-f",
                "config/local-compose.yaml",
                "down",
                "--volumes",
                "--remove-orphans",
            ],
        ]
        failures: list[int] = []
        descriptor = os.open(cleanup_log, os.O_WRONLY | os.O_APPEND | os.O_CREAT | no_follow_flag(), 0o600)
        with os.fdopen(descriptor, "a", encoding="utf-8") as handle:
            for command in commands:
                try:
                    process = subprocess.run(
                        command,
                        cwd=repository_root(),
                        env=environment,
                        text=True,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT,
                        timeout=180,
                        check=False,
                    )
                    handle.write(process.stdout)
                    if process.returncode != 0:
                        failures.append(process.returncode)
                except subprocess.TimeoutExpired:
                    handle.write("retained cleanup command timed out\n")
                    failures.append(124)
        if failures:
            raise RunnerError(f"오류: 보존 component 정리에 실패했습니다. retained-cleanup 로그를 확인하세요: {failures[0]}")
        shutil.rmtree(state_dir)
    observed_at = utc_now()
    for component in snapshot.get("components", []):
        component["state"] = "DOWN"
        component["observedAt"] = observed_at
    snapshot["retainedEnvironment"] = False
    snapshot["updatedAt"] = observed_at
    atomic_write_json(run_dir / "run.json", snapshot)
    append_cleanup_event(run_dir, snapshot)


def command_stop(arguments: list[str]) -> int:
    if len(arguments) > 1:
        raise RunnerError("오류: stop은 runId를 하나만 받습니다.")
    root = artifact_root()
    run_id = arguments[0] if arguments else latest_run_id(root)
    run_dir = run_directory(root, run_id, must_exist=True)
    snapshot = load_snapshot(run_dir)
    if snapshot.get("state") in TERMINAL_STATES:
        retained_value = snapshot.get("retainedEnvironment")
        retained = retained_value is True
        if retained_value is None:
            legacy_active = any(
                component.get("state") in {"STARTING", "UP", "FAILED"} for component in snapshot.get("components", [])
            )
            retained = legacy_active and smoke_runtime_state_directory(run_id).is_dir()
        if not retained:
            raise RunnerError(f"오류: 중단할 수 없는 실행 상태입니다: {snapshot.get('state')}")
        cleanup_retained_environment(
            root,
            run_dir,
            snapshot,
            internal_testing=(
                os.environ.get("BCM_SYSTEM_TEST_INTERNAL_TESTING") == "true"
                and root != (repository_root() / "build" / "system-test").resolve()
            ),
        )
        print(f"{run_id} 보존 component를 정리했습니다.")
        return 0
    if snapshot.get("state") not in ACTIVE_STATES:
        raise RunnerError(f"오류: 중단할 수 없는 실행 상태입니다: {snapshot.get('state')}")
    marker = run_dir / STOP_REQUEST_FILE
    try:
        descriptor = os.open(
            marker,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | no_follow_flag(),
            0o600,
        )
    except FileExistsError:
        pass
    else:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(f"requestedAt={utc_now()}\n")
    print(f"{run_id} 중단을 요청했습니다.")
    return 0


def print_help() -> None:
    print(
        """BCM 전체 시스템 통합 테스트

사용법:
  ./scripts/system-test.sh smoke [--keep-on-failure]
  ./scripts/system-test.sh full [--keep-on-failure]
  ./scripts/system-test.sh status [runId]
  ./scripts/system-test.sh logs [runId] [component]
  ./scripts/system-test.sh stop [runId]

smoke는 전용 로컬 환경에서 입금→Webhook→Kafka→Admin 세로줄을 검증합니다.
full은 출금·gasless·실패 복구·sweep/BAT·reset 격리를 추가로 검증합니다.
실제 Fireblocks API는 이 명령에서 호출하지 않습니다.
"""
    )


def main(arguments: list[str]) -> int:
    if not arguments or arguments[0] in {"help", "--help", "-h"}:
        print_help()
        return 0
    command, *rest = arguments
    if command in {"smoke", "full"}:
        unknown_options = [argument for argument in rest if argument != "--keep-on-failure"]
        if unknown_options:
            raise RunnerError(f"오류: 알 수 없는 옵션입니다: {unknown_options[0]}")
        return run_suite(command.upper(), keep_on_failure="--keep-on-failure" in rest)
    if command == "status":
        return command_status(rest)
    if command == "logs":
        return command_logs(rest)
    if command == "stop":
        return command_stop(rest)
    raise RunnerError(f"오류: 알 수 없는 명령입니다: {command}")


if __name__ == "__main__":
    signal.signal(signal.SIGINT, handle_stop_signal)
    signal.signal(signal.SIGTERM, handle_stop_signal)
    try:
        raise SystemExit(main(sys.argv[1:]))
    except RunnerError as error:
        print(error, file=sys.stderr)
        raise SystemExit(2)
