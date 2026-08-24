#!/usr/bin/env python3

from __future__ import annotations

import fcntl
import importlib.util
import os
import signal
import socket
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


REPO = Path(__file__).resolve().parent.parent.parent
SCENARIOS = {"asset-catalog", "customer-vault", "deposit-success"}


def load_module(name: str, path: Path) -> Any:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"모듈을 읽을 수 없습니다: {path.name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def ready_http(url: str) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=1) as response:
            return 200 <= response.status < 300
    except (urllib.error.URLError, TimeoutError, OSError):
        return False


def ready_tcp(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return True
    except OSError:
        return False


def preflight(runner: Any, ledger: Any) -> None:
    if os.environ.get("BCM_VENDOR_MODE") != "STUB" or os.environ.get("BCM_CHAIN_MODE") != "LOCAL":
        raise runner.StepFailure(
            "LOCAL_SCENARIO_MODE_REQUIRED",
            "로컬 시나리오는 STUB+LOCAL 환경에서만 실행할 수 있습니다.",
            "./scripts/local.sh down 후 ./scripts/local.sh up stub을 실행하세요.",
            False,
        )
    checks = {
        "postgres": ready_tcp(int(os.environ.get("BCM_LOCAL_POSTGRES_PORT", "15432"))),
        "kafka": ready_tcp(int(os.environ.get("BCM_LOCAL_KAFKA_PORT", "9092"))),
        "ethereum-anvil": ready_tcp(int(os.environ.get("BCM_LOCAL_ETHEREUM_ANVIL_PORT", "38545"))),
        "base-anvil": ready_tcp(int(os.environ.get("BCM_LOCAL_BASE_ANVIL_PORT", "38546"))),
        "fireblocks-stub": ready_http(
            f"http://127.0.0.1:{int(os.environ.get('BCM_LOCAL_STUB_MANAGEMENT_PORT', '18090'))}/actuator/health"
        ),
        "bcm-api": ready_http(
            f"http://127.0.0.1:{int(os.environ.get('BCM_LOCAL_API_MANAGEMENT_PORT', '9090'))}/actuator/health"
        ),
        "bcm-webhook": ready_http(
            f"http://127.0.0.1:{int(os.environ.get('BCM_LOCAL_WEBHOOK_MANAGEMENT_PORT', '9091'))}/actuator/health"
        ),
        "bcm-admin": ready_http(
            f"http://127.0.0.1:{int(os.environ.get('BCM_LOCAL_ADMIN_PORT', '9080'))}/actuator/health"
        ),
    }
    for component, ready in checks.items():
        ledger.set_component(component, "UP" if ready else "FAILED")
    unavailable = [component for component, ready in checks.items() if not ready]
    if unavailable:
        raise runner.StepFailure(
            "LOCAL_COMPONENT_NOT_READY",
            f"준비되지 않은 로컬 component가 있습니다: {', '.join(unavailable)}",
            "./scripts/local.sh status와 해당 component 로그를 확인하세요.",
            True,
        )


def complete_components(ledger: Any) -> None:
    components = ("postgres", "kafka", "ethereum-anvil", "base-anvil", "fireblocks-stub", "bcm-api", "bcm-webhook", "bcm-admin")
    for component in components:
        ledger.set_component(component, "UP")
    ledger.set_component("bcm-bat", "DOWN")


def step(runner: Any, ledger: Any, index: int, step_id: str, name: str, action: Any, classification: tuple[str, ...]) -> None:
    runner.run_step(ledger, index, step_id, name, action, classification=classification)


def run_asset_catalog(runner: Any, helper: Any, ledger: Any) -> None:
    step(runner, ledger, 1, "local-readiness", "기동 중인 STUB+LOCAL component 확인", lambda: preflight(runner, ledger), ("REAL_LOCAL", "SIMULATED_VENDOR"))
    step(runner, ledger, 2, "catalog-sync", "BAT 블록체인 카탈로그 1회 동기화", helper.sync_catalog, ("SIMULATED_VENDOR",))
    step(runner, ledger, 3, "asset-mapping", "자산 카탈로그 동기화와 ETHEREUM·BASE의 USDC/KRWK 매핑 확인", lambda: helper.ensure_local_asset(runner, ledger), ("REAL_LOCAL", "SIMULATED_VENDOR"))


def run_customer_vault(runner: Any, helper: Any, ledger: Any, ref: str, symbol: str) -> None:
    step(runner, ledger, 1, "local-readiness", "기동 중인 STUB+LOCAL component 확인", lambda: preflight(runner, ledger), ("REAL_LOCAL", "SIMULATED_VENDOR"))
    step(runner, ledger, 2, "catalog-sync", "BAT 블록체인 카탈로그 1회 동기화", helper.sync_catalog, ("SIMULATED_VENDOR",))
    step(runner, ledger, 3, "asset-mapping", "자산 카탈로그 동기화와 ETHEREUM·BASE의 USDC/KRWK 매핑 확인", lambda: helper.ensure_local_asset(runner, ledger), ("REAL_LOCAL", "SIMULATED_VENDOR"))

    def create() -> None:
        account_id, address = runner.create_account(ledger, "CUSTOMER", ref, symbol)
        ledger.set_related_id("accountId", account_id)
        ledger.set_related_id("address", address)

    step(runner, ledger, 4, "customer-vault", "고객 account(vault)와 입금 주소 생성", create, ("SIMULATED_VENDOR",))


def run_deposit_success(runner: Any, helper: Any, ledger: Any) -> None:
    context: dict[str, str] = {}
    step(runner, ledger, 1, "local-readiness", "기동 중인 STUB+LOCAL component 확인", lambda: preflight(runner, ledger), ("REAL_LOCAL", "SIMULATED_VENDOR"))
    step(runner, ledger, 2, "catalog-sync", "BAT 블록체인 카탈로그 1회 동기화", helper.sync_catalog, ("SIMULATED_VENDOR",))
    step(runner, ledger, 3, "asset-mapping", "자산 카탈로그 동기화와 ETHEREUM·BASE의 USDC/KRWK 매핑 확인", lambda: helper.ensure_local_asset(runner, ledger), ("REAL_LOCAL", "SIMULATED_VENDOR"))

    def create() -> None:
        account_id, address = runner.create_deposit_destination(ledger)
        context.update(accountId=account_id, address=address)
        ledger.set_related_id("accountId", account_id)
        ledger.set_related_id("address", address)

    step(runner, ledger, 4, "customer-vault", "고객 account(vault)와 ETHEREUM/USDC 주소 생성", create, ("SIMULATED_VENDOR",))

    def inject() -> None:
        context["vendorTxId"] = runner.inject_local_deposit(ledger, context["address"])

    step(runner, ledger, 5, "anvil-deposit", "Anvil 입금과 서명 Webhook 주입", inject, ("REAL_LOCAL", "SIMULATED_VENDOR"))

    def finalize() -> None:
        runner.finalize_local_deposit(context["vendorTxId"])
        runner.await_bcm_finalized(ledger, context["vendorTxId"])

    step(runner, ledger, 6, "finalized-transaction", "BCM 거래 FINALIZED 확인", finalize, ("REAL_LOCAL", "SIMULATED_VENDOR"))
    step(
        runner,
        ledger,
        7,
        "kafka-customer-event",
        "Kafka 고객 FINALIZED 이벤트 확인",
        lambda: helper.verify_kafka_event(context["accountId"], ledger.run_id),
        ("REAL_LOCAL",),
    )
    step(runner, ledger, 8, "admin-investigation", "Admin 거래 조사 연결 확인", lambda: runner.await_admin_investigation(ledger, context["vendorTxId"]), ("REAL_LOCAL",))


def main(arguments: list[str]) -> int:
    if len(arguments) < 2 or arguments[0] not in SCENARIOS:
        print("사용법: local-scenario-runner.py <asset-catalog|customer-vault|deposit-success> <runId> [ref symbol]", file=sys.stderr)
        return 2
    scenario, run_id, *inputs = arguments
    if scenario == "customer-vault" and len(inputs) != 2:
        print("customer-vault에는 ref와 symbol이 필요합니다.", file=sys.stderr)
        return 2
    helper = load_module("bcm_local_deposit_test", REPO / "scripts/internal/local-deposit-test.py")
    runner = helper.load_runner()
    total_steps = {"asset-catalog": 3, "customer-vault": 4, "deposit-success": 8}[scenario]
    artifact_root = runner.artifact_root()
    runner.prune_completed_runs(artifact_root)
    ledger = runner.RunLedger(artifact_root, run_id, "SCENARIO", total_steps)
    ledger.start()
    lock_path = runner.artifact_root() / ".local-scenario.lock"
    with lock_path.open("a+", encoding="utf-8") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            failure = runner.StepFailure("LOCAL_SCENARIO_ALREADY_RUNNING", "다른 로컬 시나리오가 진행 중입니다.", "진행 중 실행이 끝난 뒤 다시 실행하세요.", True)

            def reject() -> None:
                raise failure

            step(runner, ledger, 1, "local-scenario-lock", "로컬 시나리오 단일 실행 확인", reject, ("REAL_LOCAL",))
            return 1
        try:
            if scenario == "asset-catalog":
                run_asset_catalog(runner, helper, ledger)
            elif scenario == "customer-vault":
                run_customer_vault(runner, helper, ledger, inputs[0], inputs[1])
            else:
                run_deposit_success(runner, helper, ledger)
            complete_components(ledger)
            ledger.finish()
            return 0
        except (runner.StepFailure, runner.StepAborted) as failure:
            runner.print_failure_diagnostics(ledger, failure)
            return 1


if __name__ == "__main__":
    signal.signal(signal.SIGINT, lambda *_: None)
    raise SystemExit(main(sys.argv[1:]))
