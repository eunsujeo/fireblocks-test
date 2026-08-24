import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  adminRouteFromPath,
  assetCandidateEmptyState,
  assetDiscoverySymbol,
  changeRequestIdFromPath,
  filtersFromUrl,
  filtersToUrl,
  formatAdminTime,
  formatCoreTime,
  isGlobalSearchShortcut,
  registeredAssetMapping,
  resolveViewState,
  runSingleFlight,
  shouldRefreshTestRun,
  testRunIdFromPath,
  transactionIdentifierFromPath,
} from "../../main/resources/static/admin/app-state.js";

const staticRoot = new URL("../../main/resources/static/admin/", import.meta.url);
const shellSource = readFileSync(new URL("index.html", staticRoot), "utf8");
const appSource = readFileSync(new URL("app.js", staticRoot), "utf8");
const styleSource = readFileSync(new URL("admin.css", staticRoot), "utf8");

test("밴드S 경로는 독립 운영 원장 화면으로 해석된다", () => {
  assert.equal(adminRouteFromPath("/admin/band-s"), "bandS");
  assert.equal(adminRouteFromPath("/admin/band-s/"), "bandS");
  assert.equal(adminRouteFromPath("/admin/policies"), "policies");
});

test("비상 운영 경로는 실행 게이트 원장 화면으로 해석된다", () => {
  assert.equal(adminRouteFromPath("/admin/emergency"), "emergency");
  assert.equal(adminRouteFromPath("/admin/emergency/"), "emergency");
});

test("로컬 테스트 실행 목록과 상세 경로를 구분한다", () => {
  assert.equal(adminRouteFromPath("/admin/test-runs"), "testRuns");
  assert.equal(adminRouteFromPath("/admin/test-runs/run%3A2026"), "testRun");
  assert.equal(testRunIdFromPath("/admin/test-runs/run%3A2026"), "run:2026");
  assert.equal(testRunIdFromPath("/admin/test-runs/%E0%A4%A"), null);
});

test("테스트 실행 자동 갱신은 진행 상태에서만 유지한다", () => {
  assert.equal(shouldRefreshTestRun("PENDING"), true);
  assert.equal(shouldRefreshTestRun("RUNNING"), true);
  assert.equal(shouldRefreshTestRun("PASSED"), false);
  assert.equal(shouldRefreshTestRun("FAILED"), false);
  assert.equal(shouldRefreshTestRun("ABORTED"), false);
});

test("네트워크 필터는 URL 왕복 뒤에도 보존된다", () => {
  const initial = new URL("http://localhost/admin/networks?q=base&chainId=8453&adopted=true&testnet=false");
  const filters = filtersFromUrl(initial);

  assert.deepEqual(filters, { q: "base", chainId: "8453", adopted: "true", testnet: "false" });
  assert.equal(filtersToUrl("/admin/networks", filters), "/admin/networks?q=base&chainId=8453&adopted=true&testnet=false");
});

test("자산 매핑 검색은 검색어와 정확 필터를 URL에 함께 보존한다", () => {
  const initial = new URL("http://localhost/admin/assets?q=0x8335&network=BASE&symbol=USDC");
  const filters = filtersFromUrl(initial);

  assert.deepEqual(filters, { q: "0x8335", network: "BASE", symbol: "USDC" });
  assert.equal(filtersToUrl("/admin/assets", filters), "/admin/assets?q=0x8335&network=BASE&symbol=USDC");
  assert.match(appSource, /찾을 자산/);
  assert.match(appSource, /<summary>Advanced<\/summary>/);
});

test("일반 검색어에서 안전한 자산 후보 심볼만 찾아낸다", () => {
  assert.equal(assetDiscoverySymbol("usdc"), "USDC");
  assert.equal(assetDiscoverySymbol(" KRWK "), "KRWK");
  assert.equal(assetDiscoverySymbol("USD Coin"), null);
  assert.equal(assetDiscoverySymbol("0x8335cafe"), null);
});

test("후보와 같은 네트워크·주소의 기존 등록을 대소문자와 네이티브 여부까지 대조한다", () => {
  const mappings = [
    { network: "BASE", symbol: "USDC", fireblocksAssetId: "USDC_BASE", contractAddress: "0xAbCd" },
    { network: "ETHEREUM", symbol: "ETH", fireblocksAssetId: "ETH", contractAddress: null },
  ];

  assert.equal(
    registeredAssetMapping({ network: "BASE", fireblocksAssetId: "USDC_BASE", contractAddress: "0xabcd" }, mappings)?.symbol,
    "USDC",
  );
  assert.equal(
    registeredAssetMapping({ network: "ETHEREUM", fireblocksAssetId: "ETH", contractAddress: null }, mappings)?.symbol,
    "ETH",
  );
  assert.equal(registeredAssetMapping({ network: "BASE", fireblocksAssetId: "USDC_BASE", contractAddress: "0x9999" }, mappings), undefined);
  assert.equal(registeredAssetMapping({ network: "BASE", fireblocksAssetId: "USDC_BASE_V2", contractAddress: "0xabcd" }, mappings), undefined);
});

test("자산 후보 없음은 로컬 준비 실패와 catalog 미동기화와 실제 검색 결과 없음을 구분한다", () => {
  assert.deepEqual(assetCandidateEmptyState("USDC", []), {
    kind: "CATALOG_SETUP_REQUIRED",
    message: "지원 Network catalog가 준비되지 않았습니다.",
    detail: "./scripts/local.sh restart fireblocks로 지원 Network와 asset catalog를 다시 준비하세요.",
    actionHref: null,
    actionLabel: null,
  });
  assert.deepEqual(assetCandidateEmptyState("USDC", [
    { network: "BASE_SEPOLIA", state: "NEVER_SYNCED" },
  ]), {
    kind: "CATALOG_REQUIRED",
    message: "BASE_SEPOLIA catalog가 아직 동기화되지 않았습니다.",
    detail: "./scripts/local.sh sync assets를 실행한 뒤 다시 검색하세요.",
    actionHref: null,
    actionLabel: null,
  });
  assert.deepEqual(assetCandidateEmptyState("USDC", [
    { network: "BASE_SEPOLIA", state: "READY" },
  ]), {
    kind: "NO_MATCH",
    message: "‘USDC’ 후보가 없습니다.",
    detail: "검색어를 바꾸거나 해당 Network의 Fireblocks asset catalog를 확인하세요.",
    actionHref: null,
    actionLabel: null,
  });
});

test("거래 상세 경로는 전체 식별자를 손실 없이 복원한다", () => {
  assert.equal(
    transactionIdentifierFromPath("/admin/transactions/tx%3Aroot%2B2026-08-17"),
    "tx:root+2026-08-17",
  );
  assert.equal(transactionIdentifierFromPath("/admin/transactions/%E0%A4%A"), null);
  assert.equal(transactionIdentifierFromPath("/admin/search"), null);
});

test("변경 요청 상세 경로는 요청 ID를 손실 없이 복원한다", () => {
  assert.equal(changeRequestIdFromPath("/admin/change-requests/PCR%3A2026%2B0817"), "PCR:2026+0817");
  assert.equal(changeRequestIdFromPath("/admin/change-requests/%E0%A4%A"), null);
  assert.equal(changeRequestIdFromPath("/admin/policies"), null);
});

test("화면 상태는 loading empty forbidden error stale partial completed를 구분한다", () => {
  assert.equal(resolveViewState({ loading: true }), "loading");
  assert.equal(resolveViewState({ status: 403 }), "forbidden");
  assert.equal(resolveViewState({ error: true }), "error");
  assert.equal(resolveViewState({ state: "STALE", data: [{}] }), "stale");
  assert.equal(resolveViewState({ state: "PARTIAL", data: [{}] }), "partial");
  assert.equal(resolveViewState({ state: "FRESH", data: [] }), "empty");
  assert.equal(resolveViewState({ state: "FRESH", data: [{}] }), "completed");
});

test("운영 시각은 로컬 표시와 손실 없는 UTC 원문을 함께 유지한다", () => {
  assert.deepEqual(
    formatAdminTime("2026-08-19T01:02:03Z", () => "2026. 8. 19. 10:02:03"),
    { local: "2026. 8. 19. 10:02:03", utc: "2026-08-19T01:02:03Z" },
  );
  assert.deepEqual(formatAdminTime("invalid", () => "사용되지 않음"), { local: "invalid", utc: "invalid" });
  assert.equal(formatAdminTime(null), null);
  assert.deepEqual(
    formatCoreTime("20260819010203", () => "2026. 8. 19. 10:02:03"),
    { local: "2026. 8. 19. 10:02:03", utc: "2026-08-19T01:02:03Z" },
  );
});

test("장시간 동작을 중복 클릭해도 첫 요청 하나만 실행하고 완료 뒤 다시 활성화한다", async () => {
  const attributes = new Map();
  const control = {
    disabled: false,
    setAttribute(name, value) { attributes.set(name, value); },
    removeAttribute(name) { attributes.delete(name); },
  };
  let release;
  let calls = 0;
  const action = () => {
    calls += 1;
    return new Promise((resolve) => { release = resolve; });
  };

  const first = runSingleFlight(control, action);
  const duplicate = await runSingleFlight(control, action);

  assert.equal(duplicate, false);
  assert.equal(calls, 1);
  assert.equal(control.disabled, true);
  assert.equal(attributes.get("aria-busy"), "true");

  release();
  assert.equal(await first, true);
  assert.equal(control.disabled, false);
  assert.equal(attributes.has("aria-busy"), false);
});

test("Admin 셸은 키보드와 스크린리더 접근성 경계를 정적으로 유지한다", () => {
  assert.match(shellSource, /<html lang="ko">/);
  assert.match(shellSource, /class="skip-link" href="#app-content"/);
  assert.match(shellSource, /<nav class="sidebar" aria-label="주 메뉴">/);
  assert.match(shellSource, /<label class="sr-only" for="global-query">/);
  assert.match(shellSource, /id="live-region"[^>]*aria-live="polite"/);
  assert.match(styleSource, /:focus-visible/);
  assert.match(styleSource, /@media \(prefers-reduced-motion: reduce\)/);
});

test("Admin 메뉴와 기술 라벨은 익숙한 영어 용어를 사용하고 설명과 action은 한국어로 유지한다", () => {
  assert.match(shellSource, />Dashboard</);
  assert.match(shellSource, />Networks</);
  assert.match(shellSource, />Assets</);
  assert.match(shellSource, />Contracts</);
  assert.match(shellSource, />Policies</);
  assert.match(shellSource, />Transactions</);
  assert.match(appSource, /<summary>Advanced<\/summary>/);
  assert.match(appSource, /<th>Network<\/th><th>Symbol<\/th><th>Fireblocks Asset ID<\/th><th>Contract address<\/th><th>Registered at \(UTC\)<\/th>/);
  assert.match(appSource, /<div><dt>Decimals<\/dt>/);
  assert.match(appSource, /<h1>Transaction investigation<\/h1>/);
  assert.match(appSource, /<h1>Contract registry<\/h1>/);
  assert.match(appSource, /<h1>Execution policies<\/h1>/);
  assert.match(appSource, /<h1>Band S ledger<\/h1>/);
  assert.match(appSource, /<h1>Emergency operations<\/h1>/);
  assert.doesNotMatch(appSource, /<summary>고급 조건<\/summary>|<div><dt>소수 자릿수<\/dt>|<th>컨트랙트 주소<\/th>/);
});

test("전역 검색 단축키는 입력 중이 아닐 때만 검색창으로 이동한다", () => {
  const shortcut = { key: "/", ctrlKey: false, metaKey: false, altKey: false, target: { tagName: "MAIN" } };

  assert.equal(isGlobalSearchShortcut(shortcut), true);
  assert.equal(isGlobalSearchShortcut({ ...shortcut, target: { tagName: "INPUT" } }), false);
  assert.equal(isGlobalSearchShortcut({ ...shortcut, target: { tagName: "TEXTAREA" } }), false);
  assert.equal(isGlobalSearchShortcut({ ...shortcut, metaKey: true }), false);
  assert.match(shellSource, /id="global-query"[^>]*aria-keyshortcuts="\/"/);
  assert.match(shellSource, /<kbd class="search-shortcut"[^>]*>\/</);
});

test("브라우저 번들은 BFF 상대경로만 호출하고 인증정보나 원문 민감 필드를 담지 않는다", () => {
  const fetchArguments = [...appSource.matchAll(/fetch\(([^,)]+)/g)].map((match) => match[1].trim());
  assert.deepEqual(fetchArguments, ["url"]);
  assert.match(appSource, /request\(`?\/bff\/admin\//);
  assert.doesNotMatch(appSource, /https?:\/\/|Authorization|X-Employee-No|privateKey|rawPayload|signature/i);
});

test("자산 등록은 검색·후보 선택·검증 요약을 한 모달에서 완료한다", () => {
  assert.match(appSource, /id="asset-add-dialog"/);
  assert.match(appSource, /role="listbox"/);
  assert.match(appSource, /어떤 자산을 찾으세요/);
  assert.match(appSource, /Symbol, asset name 또는 contract address/);
  assert.match(appSource, /\/bff\/admin\/asset-candidates/);
  assert.match(appSource, /asset-candidates\?q=/);
  assert.match(appSource, /asset-catalog-sources/);
  assert.match(appSource, /NEVER_SYNCED/);
  assert.match(appSource, /assetCandidateEmptyState/);
  assert.match(appSource, /data-asset-prerequisite/);
  assert.doesNotMatch(appSource, /먼저 Fireblocks Network를 선택해 BCM code를 등록하세요/);
  assert.match(appSource, /Fireblocks Asset ID/);
  assert.match(appSource, /fireblocksAssetId/);
  assert.match(appSource, /networkDisplayName/);
  assert.match(appSource, /Testnet/);
  assert.match(appSource, /Contract address 복사/);
  assert.match(appSource, /identifier\(selected\.fireblocksAssetId, "Fireblocks Asset ID"\)/);
  assert.match(appSource, /\.\/scripts\/local\.sh sync assets/);
  assert.match(appSource, /method: "POST"/);
  assert.match(appSource, /"X-BCM-Local-Asset-Management": "execute"/);
  assert.match(appSource, /등록할 자산 확인/);
  assert.match(appSource, /Fireblocks 후보에서 찾기/);
  assert.match(appSource, /이미 BCM에 등록됨/);
  assert.match(appSource, /data-discover-symbol/);
  assert.match(appSource, /<details class="asset-advanced-filters"/);
  assert.doesNotMatch(appSource, /네트워크를 몰라도 됩니다\.<\/small>/);
  assert.match(styleSource, /\.asset-dialog::backdrop/);
});

test("첫 화면은 Fireblocks 카탈로그에서 네트워크와 자산 등록으로 이어진다", () => {
  assert.match(shellSource, /id="runtime-capability"/);
  assert.match(shellSource, /id="scope-note"/);
  assert.match(appSource, /catalogNetworkCount/);
  assert.match(appSource, /adoptedNetworkCount/);
  assert.match(appSource, /catalogSyncedAt/);
  assert.match(appSource, /\/admin\/assets\?action=add/);
  assert.match(appSource, /developerPortalUrl/);
  assert.match(appSource, /지원 Network는 시작 과정에서 자동 연결/);
  assert.match(appSource, /USDC 검색·등록/);
  assert.doesNotMatch(appSource, /id="network-adopt-dialog"|data-adopt-network/);
  assert.match(appSource, /"X-BCM-Local-Asset-Management": "execute"/);
});

test("테스트 실행 진단은 숨김 메뉴에서 시작하고 서버 진행률과 다음 조치만 표시한다", () => {
  assert.match(shellSource, /data-system-test-nav[^>]*hidden/);
  assert.match(appSource, /\/bff\/admin\/test-runs/);
  assert.match(appSource, /progress\.percent/);
  assert.match(appSource, /failure\.nextAction/);
  assert.match(appSource, /relatedIds\.transactionHref/);
  assert.match(appSource, /statusBanner\(payload\)/);
  assert.match(appSource, /payload\.state === "FRESH" && shouldRefreshTestRun/);
  assert.match(appSource, /step\.classification/);
  assert.match(appSource, /step\.observations/);
  assert.match(appSource, /\/bff\/admin\/test-scenarios/);
  assert.match(appSource, /data-scenario-id/);
  assert.match(appSource, /STUB \+ LOCAL ONLY/);
  assert.match(appSource, /"X-BCM-Local-Scenario": "execute"/);
  assert.match(appSource, /"OPEN", "READY"/);
  assert.doesNotMatch(appSource, /rawPayload|privateKey|component\.log/i);
});

test("장시간 원장은 서버 단계와 관측 시각 및 금지 사유를 함께 표시한다", () => {
  assert.match(appSource, /최신 단계/);
  assert.match(appSource, /호출 \/ 결과/);
  assert.match(appSource, /latestCheckObservedAt/);
  assert.match(appSource, /disabledReasons/);
  assert.match(appSource, /retryable/);
  assert.match(appSource, /retryCondition/);
  assert.match(appSource, /statusPath/);
  assert.match(appSource, /READ ONLY/);
});

test("비상 화면은 gate event 상태가 아니라 서버의 신규 실행 판정으로 가능 문구를 표시한다", () => {
  assert.match(appSource, /gate\.newExecutionAllowed \? "신규 실행 가능" : "신규 실행 차단"/);
  assert.match(appSource, /gates\.filter\(\(gate\) => !gate\.newExecutionAllowed\)/);
});

test("거래 현재 진단은 같은 벤더 상태를 중복 표시하지 않는다", () => {
  assert.equal(appSource.match(/\["벤더 네트워크 상태"/g)?.length, 1);
});
