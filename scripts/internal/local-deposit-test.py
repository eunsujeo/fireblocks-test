#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


REPO = Path(__file__).resolve().parent.parent.parent


def load_runner() -> Any:
    path = REPO / "scripts/internal/system-test-runner.py"
    spec = importlib.util.spec_from_file_location("bcm_system_test_runner", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("system-test-runner를 읽을 수 없습니다.")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module.SMOKE_API_PORT = port("BCM_LOCAL_API_PORT", 38080)
    module.SMOKE_ADMIN_PORT = port("BCM_LOCAL_ADMIN_PORT", 9080)
    module.SMOKE_STUB_PORT = port("BCM_LOCAL_STUB_PORT", 18080)
    module.SMOKE_KAFKA_PORT = port("BCM_LOCAL_KAFKA_PORT", 9092)
    if os.environ.get("BCM_LOCAL_CHAIN_PROFILE", "catalog") == "catalog":
        module.LOCAL_NETWORK = "ETHEREUM"
        module.LOCAL_SYMBOL = "USDC"
        module.LOCAL_VENDOR_ASSET_ID = "USDC_ETH_LOCAL"
    return module


def port(name: str, default: int) -> int:
    value = int(os.environ.get(name, str(default)))
    if value not in range(1, 65536):
        raise RuntimeError(f"안전하지 않은 포트입니다: {name}")
    return value


class LocalLedger:
    def __init__(self) -> None:
        self.run_id = f"local-{time.strftime('%Y%m%dT%H%M%S', time.gmtime())}-{os.getpid()}"
        self.related_ids: dict[str, str] = {}

    def set_related_id(self, key: str, value: Any) -> None:
        if isinstance(value, str) and value:
            self.related_ids[key] = value


def progress(index: int, total: int, message: str) -> None:
    print(f"[{index}/{total}] {message}", flush=True)


def run_gradle(task: str, environment: dict[str, str]) -> str:
    result = subprocess.run(
        ["./gradlew", "--no-daemon", task],
        cwd=REPO,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=600,
        check=False,
    )
    if result.returncode != 0:
        tail = "\n".join(result.stdout.splitlines()[-40:])
        raise RuntimeError(f"{task} 실패\n{tail}")
    return result.stdout


def run_catalog_job(job: str) -> None:
    environment = os.environ.copy()
    environment["BCM_JOB"] = job
    environment.pop("BCM_FIREBLOCKS_PRIVATE_KEY_PEM", None)
    run_gradle(":blockchain-manager-app:bcm-bat:bootRun", environment)


def sync_catalog() -> None:
    run_catalog_job("catalog-sync-once")


def sync_asset_catalog() -> None:
    run_catalog_job("asset-catalog-sync-once")


LOCAL_CATALOG = (
    ("ETHEREUM", 31337, ("USDC", "KRWK")),
    ("BASE", 31338, ("USDC", "KRWK")),
)

FIREBLOCKS_TEST_CATALOG = (
    ("ETHEREUM_SEPOLIA", 11155111),
    ("BASE_SEPOLIA", 84532),
)


def local_catalog_contracts() -> dict[tuple[str, str], str]:
    cluster_file = os.environ.get("BCM_LOCAL_CHAIN_CLUSTER_MANIFEST_FILE", "")
    if not cluster_file:
        raise RuntimeError("로컬 체인 cluster manifest 경로가 없습니다.")
    cluster = json.loads(Path(cluster_file).read_text(encoding="utf-8"))
    expected: dict[tuple[str, str], str] = {}
    for chain in cluster.get("chains", []):
        network = chain.get("networkCode")
        manifest_file = chain.get("manifestFile")
        if not isinstance(network, str) or not isinstance(manifest_file, str):
            raise RuntimeError("로컬 체인 cluster manifest 형식이 올바르지 않습니다.")
        manifest = json.loads(Path(manifest_file).read_text(encoding="utf-8"))
        for asset in manifest.get("assets", []):
            symbol = asset.get("symbol")
            contract = asset.get("contractAddress")
            if isinstance(symbol, str) and isinstance(contract, str):
                expected[(network, symbol)] = contract.lower()
    required = {(network, symbol) for network, _, symbols in LOCAL_CATALOG for symbol in symbols}
    if expected.keys() & required != required:
        raise RuntimeError("로컬 체인 manifest에 기본 USDC·KRWK contract가 모두 없습니다.")
    return expected


def local_catalog_ready(runner: Any) -> bool:
    api_port = runner.SMOKE_API_PORT
    expected_contracts = local_catalog_contracts()
    for network, chain_id, symbols in LOCAL_CATALOG:
        networks, _ = runner.http_json("GET", f"http://127.0.0.1:{api_port}/admin/networks?chainId={chain_id}")
        if not any(item.get("code") == network for item in runner.response_data(networks, "네트워크 카탈로그 조회")):
            return False
        for symbol in symbols:
            mappings, _ = runner.http_json(
                "GET",
                f"http://127.0.0.1:{api_port}/admin/asset-mappings?network={network}&symbol={symbol}",
            )
            observed = runner.response_data(mappings, "자산 매핑 조회")
            if len(observed) != 1 or str(observed[0].get("contractAddress", "")).lower() != expected_contracts[(network, symbol)]:
                return False
    return True


def ensure_local_asset(runner: Any, ledger: LocalLedger) -> None:
    api_port = runner.SMOKE_API_PORT
    actor_headers = {"X-Employee-No": "LOCAL", "X-Branch-Code": "9999"}
    expected_contracts = local_catalog_contracts()
    adopted: list[tuple[str, tuple[str, ...]]] = []
    for network, chain_id, symbols in LOCAL_CATALOG:
        networks, headers = runner.http_json("GET", f"http://127.0.0.1:{api_port}/admin/networks?chainId={chain_id}")
        candidates = runner.response_data(networks, "네트워크 카탈로그 조회")
        candidate = next((item for item in candidates if item.get("chainId") == chain_id), None)
        if candidate is None:
            raise runner.StepFailure("LOCAL_NETWORK_NOT_FOUND", f"chainId {chain_id} 후보가 없습니다.", "catalog-sync 로그를 확인하세요.", True)
        ledger.set_related_id("requestId", headers.get("x-request-id"))
        if candidate.get("code") != network:
            runner.http_json(
                "PUT",
                f"http://127.0.0.1:{api_port}/admin/networks/{network}",
                payload={"candidateId": candidate.get("candidateId")},
                headers=actor_headers,
            )
        adopted.append((network, symbols))

    sync_asset_catalog()

    for network, symbols in adopted:
        for symbol in symbols:
            mappings, _ = runner.http_json(
                "GET",
                f"http://127.0.0.1:{api_port}/admin/asset-mappings?network={network}&symbol={symbol}",
            )
            observed = runner.response_data(mappings, "자산 매핑 조회")
            if observed:
                if len(observed) == 1 and str(observed[0].get("contractAddress", "")).lower() == expected_contracts[(network, symbol)]:
                    continue
                raise runner.StepFailure(
                    "LOCAL_ASSET_MAPPING_DRIFT",
                    f"{network}/{symbol} 매핑이 현재 로컬 contract와 다릅니다.",
                    "./scripts/local.sh purge 후 Stub 환경을 다시 준비하세요.",
                    True,
                )
            assets, _ = runner.http_json(
                "GET",
                f"http://127.0.0.1:{api_port}/admin/asset-candidates?q={symbol}&network={network}",
            )
            search_result = runner.response_data(assets, "자산 후보 조회")
            candidate_items = search_result.get("items") if isinstance(search_result, dict) else None
            if not isinstance(candidate_items, list):
                raise runner.StepFailure("INVALID_ASSET_CANDIDATE_RESPONSE", "자산 후보 응답 형식이 올바르지 않습니다.", "BCM API 로그를 확인하세요.", False)
            asset = next((item for item in candidate_items if item.get("symbol") == symbol), None)
            if asset is None:
                raise runner.StepFailure("LOCAL_ASSET_NOT_FOUND", f"{network}/{symbol} 후보가 없습니다.", "Stub 로그를 확인하세요.", True)
            if str(asset.get("contractAddress", "")).lower() != expected_contracts[(network, symbol)]:
                raise runner.StepFailure(
                    "LOCAL_ASSET_CANDIDATE_DRIFT",
                    f"{network}/{symbol} 후보 contract가 현재 로컬 체인과 다릅니다.",
                    "Stub과 BAT catalog-sync 로그를 확인하세요.",
                    True,
                )
            runner.http_json(
                "POST",
                f"http://127.0.0.1:{api_port}/admin/asset-mappings",
                payload={
                    "network": network,
                    "symbol": symbol,
                    "fireblocksAssetId": asset.get("fireblocksAssetId"),
                    "contractAddress": asset.get("contractAddress"),
                },
                headers=actor_headers,
                expected_statuses={201},
            )


def bootstrap_local_catalog() -> None:
    runner = load_runner()
    ledger = LocalLedger()
    if local_catalog_ready(runner):
        print("기본 네트워크·자산 카탈로그를 재사용합니다.")
        return
    progress(1, 2, "Fireblocks Stub 카탈로그 동기화")
    sync_catalog()
    progress(2, 2, "ETHEREUM·BASE의 USDC·KRWK 매핑 준비")
    ensure_local_asset(runner, ledger)


def bootstrap_fireblocks_catalog() -> None:
    runner = load_runner()
    api_port = runner.SMOKE_API_PORT
    actor_headers = {"X-Employee-No": "LOCAL", "X-Branch-Code": "9999"}
    total = len(FIREBLOCKS_TEST_CATALOG) + 1

    for index, (network, chain_id) in enumerate(FIREBLOCKS_TEST_CATALOG, start=1):
        progress(index, total, f"{network} 연결 상태 확인")
        document, _ = runner.http_json(
            "GET",
            f"http://127.0.0.1:{api_port}/admin/networks?chainId={chain_id}&testnet=true",
        )
        candidates = runner.response_data(document, "Fireblocks TESTNET 조회")
        supported = [
            item
            for item in candidates
            if item.get("chainId") == chain_id
            and item.get("testnet") is True
            and item.get("deprecated") is not True
        ]
        if not supported:
            raise RuntimeError(f"지원 Fireblocks TESTNET을 찾지 못했습니다: {network} (chainId {chain_id})")
        candidate = next((item for item in supported if item.get("code") == network), None)
        candidate = candidate or next((item for item in supported if item.get("code") is None), None)
        if candidate is None:
            adopted_codes = ", ".join(str(item.get("code")) for item in supported)
            raise RuntimeError(
                f"Fireblocks TESTNET 연결이 예상 BCM Network와 다릅니다: chainId {chain_id}, "
                f"expected {network}, actual {adopted_codes}"
            )
        adopted_code = candidate.get("code")
        if adopted_code is None:
            candidate_id = candidate.get("candidateId")
            if not isinstance(candidate_id, str) or not candidate_id:
                raise RuntimeError(f"Fireblocks TESTNET candidateId가 없습니다: {network}")
            runner.http_json(
                "PUT",
                f"http://127.0.0.1:{api_port}/admin/networks/{network}",
                payload={"candidateId": candidate_id},
                headers=actor_headers,
            )

    progress(total, total, "연결된 TESTNET 자산 카탈로그 동기화")
    sync_asset_catalog()


def verify_kafka_event(account_id: str, run_id: str) -> None:
    environment = os.environ.copy()
    environment.update(
        {
            "BCM_KAFKA_PROBE_BOOTSTRAP": f"127.0.0.1:{port('BCM_LOCAL_KAFKA_PORT', 9092)}",
            "BCM_KAFKA_PROBE_TOPIC": "deposit-events",
            "BCM_KAFKA_PROBE_GROUP_ID": run_id,
            "BCM_KAFKA_PROBE_TIMEOUT_MILLIS": "30000",
            "BCM_KAFKA_PROBE_ACCOUNT_ID": account_id,
            "BCM_KAFKA_PROBE_STATUS": "FINALIZED",
            "BCM_KAFKA_PROBE_AMOUNT": "1",
        },
    )
    output = run_gradle(":blockchain-manager-infra:messaging:awaitLocalKafkaEvent", environment)
    documents = []
    for line in output.splitlines():
        try:
            candidate = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(candidate, dict):
            documents.append(candidate)
    if not any(
        event.get("accountId") == account_id and event.get("status") == "FINALIZED" and event.get("amount") == "1"
        for event in documents
    ):
        raise RuntimeError("일치하는 FINALIZED Kafka 입금 event를 확인하지 못했습니다.")


def main() -> int:
    runner = load_runner()
    ledger = LocalLedger()
    total = 7
    try:
        progress(1, total, "Fireblocks Stub 카탈로그 동기화")
        sync_catalog()
        progress(2, total, "ETHEREUM·BASE 네트워크와 USDC·KRWK 매핑 확인")
        ensure_local_asset(runner, ledger)
        progress(3, total, "고객 계정과 USDC 입금 주소 생성")
        account_id, address = runner.create_deposit_destination(ledger)
        progress(4, total, "Anvil 입금과 서명 Webhook 주입")
        vendor_tx_id = runner.inject_local_deposit(ledger, address)
        progress(5, total, "CONFIRMING → COMPLETED → BCM FINALIZED 확인")
        runner.finalize_local_deposit(vendor_tx_id)
        runner.await_bcm_finalized(ledger, vendor_tx_id)
        progress(6, total, "Kafka FINALIZED 입금 event 확인")
        verify_kafka_event(account_id, ledger.run_id)
        progress(7, total, "Admin 거래 조사 연결 확인")
        runner.await_admin_investigation(ledger, vendor_tx_id)
    except runner.StepFailure as failure:
        print(f"실패 [{failure.code}] {failure.message}", file=sys.stderr)
        print(f"다음 조치: {failure.next_action}", file=sys.stderr)
        return 1
    except (OSError, RuntimeError, subprocess.SubprocessError) as failure:
        print(f"실패: {failure}", file=sys.stderr)
        print("다음 조치: ./scripts/local.sh logs webhook, api, stub 중 해당 로그를 확인하세요.", file=sys.stderr)
        return 1
    print("\n로컬 입금 점검이 완료됐습니다.")
    print(f"accountId: {account_id}")
    print(f"vendorTxId: {vendor_tx_id}")
    print(f"Admin: http://127.0.0.1:{runner.SMOKE_ADMIN_PORT}/admin/transactions/{vendor_tx_id}")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--bootstrap-catalog"]:
        bootstrap_local_catalog()
        raise SystemExit(0)
    if sys.argv[1:] == ["--bootstrap-fireblocks-catalog"]:
        bootstrap_fireblocks_catalog()
        raise SystemExit(0)
    raise SystemExit(main())
