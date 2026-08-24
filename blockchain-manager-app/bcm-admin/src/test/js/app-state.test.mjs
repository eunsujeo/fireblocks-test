import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  adminRouteFromPath,
  changeRequestIdFromPath,
  filtersFromUrl,
  filtersToUrl,
  formatAdminTime,
  formatCoreTime,
  resolveViewState,
  runSingleFlight,
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

test("네트워크 필터는 URL 왕복 뒤에도 보존된다", () => {
  const initial = new URL("http://localhost/admin/networks?q=base&chainId=8453&adopted=true&testnet=false");
  const filters = filtersFromUrl(initial);

  assert.deepEqual(filters, { q: "base", chainId: "8453", adopted: "true", testnet: "false" });
  assert.equal(filtersToUrl("/admin/networks", filters), "/admin/networks?q=base&chainId=8453&adopted=true&testnet=false");
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

test("브라우저 번들은 BFF 상대경로만 호출하고 인증정보나 원문 민감 필드를 담지 않는다", () => {
  const fetchArguments = [...appSource.matchAll(/fetch\(([^,)]+)/g)].map((match) => match[1].trim());
  assert.deepEqual(fetchArguments, ["url"]);
  assert.match(appSource, /request\(`?\/bff\/admin\//);
  assert.doesNotMatch(appSource, /https?:\/\/|Authorization|X-Employee-No|privateKey|rawPayload|signature/i);
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
