#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent.parent
SPEC = importlib.util.spec_from_file_location("system_test_runner", ROOT / "scripts/internal/system-test-runner.py")
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)


class FakeLedger:
    def __init__(self, root: Path) -> None:
        self.run_id = "unit-run"
        self.root = root
        self.run_dir = root / "run"
        (self.run_dir / "logs").mkdir(parents=True)
        self.snapshot = {"suite": "SMOKE", "components": [], "retainedEnvironment": False}
        self.states: dict[str, str] = {}
        self.messages: list[str] = []

    def set_component(self, name: str, state: str) -> None:
        self.states[name] = state
        component = next((item for item in self.snapshot["components"] if item["name"] == name), None)
        if component is None:
            self.snapshot["components"].append({"name": name, "state": state})
        else:
            component["state"] = state

    def write_snapshot(self) -> None:
        pass

    def append_event(self, event_type: str, **details: object) -> None:
        pass

    def set_retained_environment(self, retained: bool) -> None:
        self.snapshot["retainedEnvironment"] = retained

    def write_runner_log(self, message: str) -> None:
        self.messages.append(message)


def environment(root: Path) -> tuple[object, FakeLedger]:
    ledger = FakeLedger(root)
    target = runner.SmokeEnvironment.__new__(runner.SmokeEnvironment)
    target.ledger = ledger
    target.keep_on_failure = False
    target.repo = ROOT
    target.compose_project = "bcm-system-test-unit"
    target.state_dir = root / "system-test-runtime" / "unit-run"
    target.state_dir.mkdir(parents=True)
    target.started = True
    target.capture_component_logs = lambda: None
    return target, ledger


def completed(returncode: int = 0, output: str = "") -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess([], returncode, output)


def test_start_failure_records_observed_component_states() -> None:
    with tempfile.TemporaryDirectory() as directory:
        target, ledger = environment(Path(directory))
        target.run_command = lambda *args, **kwargs: (_ for _ in ()).throw(
            runner.StepFailure("COMPONENT_COMMAND_FAILED", "failed", "inspect", True)
        )
        target.component_readiness = lambda: {
            "postgres": True,
            "kafka": True,
            "anvil": False,
            "fireblocks-stub": False,
            "bcm-api": False,
            "bcm-webhook": False,
            "bcm-admin": False,
        }
        try:
            target.start()
        except runner.StepFailure:
            pass
        else:
            raise AssertionError("부분 기동 실패가 성공으로 처리됐습니다.")
        assert ledger.states == {
            "postgres": "UP",
            "kafka": "UP",
            "anvil": "FAILED",
            "fireblocks-stub": "DOWN",
            "bcm-api": "DOWN",
            "bcm-webhook": "DOWN",
            "bcm-admin": "DOWN",
            "bcm-bat": "DOWN",
        }


def test_bat_job_records_ephemeral_component_lifecycle() -> None:
    with tempfile.TemporaryDirectory() as directory:
        target, ledger = environment(Path(directory))
        target.run_command = lambda *args, **kwargs: completed()

        target.run_bat_job(["./gradlew", "bcm-bat:bootRun"], "bat-success")

        assert ledger.states["bcm-bat"] == "DOWN"


def test_bat_job_failure_is_preserved_for_diagnostics() -> None:
    with tempfile.TemporaryDirectory() as directory:
        target, ledger = environment(Path(directory))
        target.run_command = lambda *args, **kwargs: (_ for _ in ()).throw(
            runner.StepFailure("COMPONENT_COMMAND_FAILED", "failed", "inspect", True)
        )

        try:
            target.run_bat_job(["./gradlew", "bcm-bat:bootRun"], "bat-failure")
        except runner.StepFailure:
            pass
        else:
            raise AssertionError("BAT 실패가 성공으로 처리됐습니다.")

        assert ledger.states["bcm-bat"] == "FAILED"


def test_cleanup_failure_is_not_reported_as_down() -> None:
    with tempfile.TemporaryDirectory() as directory:
        target, ledger = environment(Path(directory))
        target.run_command = lambda *args, **kwargs: completed(1 if args[1] == "local-down" else 0)
        target.active_managed_resources = lambda: ["bcm-api"]
        try:
            target.cleanup(failed=False)
        except runner.StepFailure as failure:
            assert failure.code == "COMPONENT_CLEANUP_FAILED"
        else:
            raise AssertionError("정리 실패가 성공으로 처리됐습니다.")
        assert ledger.states["bcm-api"] == "FAILED"
        assert set(ledger.states.values()) != {"DOWN"}
        assert ledger.snapshot["retainedEnvironment"] is True
        assert target.state_dir.exists()


def test_verified_cleanup_marks_every_component_down() -> None:
    with tempfile.TemporaryDirectory() as directory:
        target, ledger = environment(Path(directory))
        target.run_command = lambda *args, **kwargs: completed()
        target.active_managed_resources = lambda: []
        target.cleanup(failed=False)
        assert set(ledger.states.values()) == {"DOWN"}
        assert ledger.snapshot["retainedEnvironment"] is False
        assert not target.state_dir.exists()


def test_successful_auto_cleanup_clears_prior_bat_failure() -> None:
    with tempfile.TemporaryDirectory() as directory:
        target, ledger = environment(Path(directory))
        ledger.set_component("bcm-bat", "FAILED")
        target.run_command = lambda *args, **kwargs: completed()
        target.active_managed_resources = lambda: []

        target.cleanup(failed=True)

        assert ledger.states["bcm-bat"] == "DOWN"
        assert ledger.snapshot["retainedEnvironment"] is False
        assert not target.state_dir.exists()


def test_postgres_digest_does_not_log_ledger_rows() -> None:
    class FakeEnvironment:
        def __init__(self) -> None:
            self.compose_project = "bcm-system-test-unit"
            self.ledger = FakeLedger(Path(tempfile.mkdtemp()))
            self.options: dict[str, object] = {}

        def run_command(self, *args: object, **kwargs: object) -> subprocess.CompletedProcess[str]:
            self.options = kwargs
            return completed(output='TABLE:bcm_tx_l\nROW:{"ext_tx_id":"secret-ledger-row"}\n')

    target = FakeEnvironment()
    digest = runner.postgres_database_digest(target, "reset-digest-before")
    expected = hashlib.sha256('TABLE:bcm_tx_l\nROW:{"ext_tx_id":"secret-ledger-row"}\n'.encode()).hexdigest()
    assert digest == expected
    assert target.options["record_output"] is False
    assert all("secret-ledger-row" not in message for message in target.ledger.messages)


def test_step_observations_preserve_repeated_identifiers() -> None:
    with tempfile.TemporaryDirectory() as directory:
        ledger = runner.RunLedger(Path(directory), "observation-run", "FULL", total_steps=1)
        ledger.start()

        def observe() -> None:
            ledger.set_related_id("externalTxId", "external-1")
            ledger.set_related_id("externalTxId", "external-2")

        runner.run_step(ledger, 1, "vendor-submit", "벤더 제출", observe, classification=("SIMULATED_VENDOR",))

        step = ledger.snapshot["steps"][0]
        assert step["classification"] == ["SIMULATED_VENDOR"]
        assert [(item["type"], item["value"]) for item in step["observations"]] == [
            ("externalTxId", "external-1"),
            ("externalTxId", "external-2"),
        ]
        assert ledger.snapshot["relatedIds"]["externalTxId"] == "external-2"


def test_runtime_collision_is_recorded_as_environment_step_failure() -> None:
    class RuntimeCollisionEnvironment:
        def __init__(self, ledger: object, keep_on_failure: bool) -> None:
            raise runner.StepFailure("RUNTIME_STATE_EXISTS", "runtime exists", "remove runtime", True)

    with tempfile.TemporaryDirectory() as directory:
        ledger = runner.RunLedger(Path(directory), "runtime-collision", "SMOKE", total_steps=runner.SMOKE_STEP_COUNT)
        ledger.start()
        original = runner.SmokeEnvironment
        runner.SmokeEnvironment = RuntimeCollisionEnvironment
        try:
            try:
                runner.smoke_suite(ledger, keep_on_failure=False)
            except runner.StepFailure as failure:
                assert failure.code == "RUNTIME_STATE_EXISTS"
            else:
                raise AssertionError("runtime 충돌이 성공으로 처리됐습니다.")
        finally:
            runner.SmokeEnvironment = original

        assert ledger.snapshot["state"] == "FAILED"
        assert ledger.snapshot["failure"]["failedStep"] == "dedicated-environment"
        assert ledger.snapshot["failure"]["code"] == "RUNTIME_STATE_EXISTS"


def test_retention_preserves_active_runs_and_removes_matching_launcher_logs() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        launcher = root / ".launcher"
        launcher.mkdir()
        for index in range(runner.MAX_RETAINED_RUNS + 1):
            run_id = f"completed-{index:02d}"
            run_dir = root / run_id
            run_dir.mkdir()
            (run_dir / "run.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "runId": run_id,
                        "state": "PASSED",
                        "startedAt": f"2026-08-21T00:00:{index:02d}Z",
                    }
                ),
                encoding="utf-8",
            )
            (launcher / f"{run_id}.log").write_text("done", encoding="utf-8")
        active = root / "active-run"
        active.mkdir()
        (active / "run.json").write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "runId": "active-run",
                    "state": "RUNNING",
                    "startedAt": "2026-08-21T01:00:00Z",
                }
            ),
            encoding="utf-8",
        )

        runner.prune_completed_runs(root)

        completed = [path for path in root.iterdir() if path.is_dir() and path.name.startswith("completed-")]
        assert len(completed) == runner.MAX_RETAINED_RUNS - 1
        assert active.is_dir()
        assert len(list(launcher.glob("completed-*.log"))) == runner.MAX_RETAINED_RUNS - 1


test_start_failure_records_observed_component_states()
test_bat_job_records_ephemeral_component_lifecycle()
test_bat_job_failure_is_preserved_for_diagnostics()
test_cleanup_failure_is_not_reported_as_down()
test_verified_cleanup_marks_every_component_down()
test_successful_auto_cleanup_clears_prior_bat_failure()
test_postgres_digest_does_not_log_ledger_rows()
test_step_observations_preserve_repeated_identifiers()
test_runtime_collision_is_recorded_as_environment_step_failure()
test_retention_preserves_active_runs_and_removes_matching_launcher_logs()
print("system test runner unit tests passed")
