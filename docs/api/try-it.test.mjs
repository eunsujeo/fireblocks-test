import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import test from "node:test";

const source = fs.readFileSync(new URL("./try-it.js", import.meta.url), "utf8");
const viewerSource = fs.readFileSync(new URL("./index.html", import.meta.url), "utf8");
const context = { URL, URLSearchParams };
context.globalThis = context;
vm.runInNewContext(source, context);
const api = context.BCM_API_TRY;

test("named OpenAPI example을 generic schema sample보다 우선한다", () => {
  const value = api.firstMediaExample(
    { examples: { success: { value: { accountId: "acct-local-001" } } }, schema: { type: "object" } },
    () => ({ accountId: "string" }),
  );
  assert.deepEqual(value, { accountId: "acct-local-001" });
});

test("패키지 경로에서 BCM base URL을 추론한다", () => {
  assert.equal(api.inferBaseUrl({ protocol: "http:", origin: "http://127.0.0.1:8080", pathname: "/api-docs/index.html" }), "http://127.0.0.1:8080");
  assert.equal(api.inferBaseUrl({ protocol: "https:", origin: "https://bcm.example.com", pathname: "/blockchain/manage-api/api-docs/" }), "https://bcm.example.com/blockchain/manage-api");
});

test("path는 encode하고 빈 query는 보내지 않는다", () => {
  assert.equal(
    api.requestTarget("http://127.0.0.1:8080/", "/accounts/{accountId}/addresses", [
      { name: "accountId", in: "path", value: "acct/A" },
      { name: "symbol", in: "query", value: "USDC" },
      { name: "network", in: "query", value: "" },
    ]),
    "http://127.0.0.1:8080/accounts/acct%2FA/addresses?symbol=USDC",
  );
});

test("userinfo URL과 file 문맥은 실행 대상으로 받지 않는다", () => {
  assert.equal(api.validBaseUrl("http://127.0.0.1:8080"), true);
  assert.equal(api.validBaseUrl("http://user@127.0.0.1:8080"), false);
  assert.equal(api.inferBaseUrl({ protocol: "file:", origin: "null", pathname: "/tmp/api.html" }), "");
});

test("loopback 요청에만 로컬 상태 확인과 모드별 재시작 명령을 제공한다", () => {
  assert.deepEqual(
    Array.from(api.localRecoveryCommands("http://127.0.0.1:8080/accounts")),
    [
      "./scripts/local.sh status",
      "./scripts/local.sh down && ./scripts/local.sh up stub",
      "./scripts/local.sh down && ./scripts/local.sh up fireblocks",
    ],
  );
  assert.equal(api.localRecoveryCommands("https://bcm.example.com/accounts").length, 0);
  assert.equal(api.localRecoveryCommands("not-a-url").length, 0);
});

test("현재 실행 값으로 복사 가능한 curl을 만든다", () => {
  assert.equal(
    api.curlCommand(
      "post",
      "http://127.0.0.1:8080/accounts",
      { Accept: "application/json", "Content-Type": "application/json" },
      { accountType: "CUSTOMER", ref: "daw-local-001" },
    ),
    "curl -sS \\\n  -X POST \\\n  'http://127.0.0.1:8080/accounts' \\\n  -H 'Accept: application/json' \\\n  -H 'Content-Type: application/json' \\\n  --data-raw '{\"accountType\":\"CUSTOMER\",\"ref\":\"daw-local-001\"}'",
  );
});

test("JSON 응답은 원문을 보존하면서 읽기 좋은 들여쓰기로 표시한다", () => {
  const body = api.responseBody(
    '{"data":{"network":"BASE","assets":["USDC","KRWK"]}}',
    "application/json; charset=utf-8",
  );
  assert.equal(body.kind, "json");
  assert.equal(body.formatted, '{\n  "data": {\n    "network": "BASE",\n    "assets": [\n      "USDC",\n      "KRWK"\n    ]\n  }\n}');
  assert.equal(body.raw, '{"data":{"network":"BASE","assets":["USDC","KRWK"]}}');
  assert.equal(api.responseBody("upstream unavailable", "text/plain").kind, "text");
  assert.equal(api.responseBody("", "application/json").kind, "empty");
});

test("카테고리 이름을 안정적인 문서 경로로 바꾼다", () => {
  assert.equal(api.categorySlug("Accounts"), "accounts");
  assert.equal(api.categorySlug("데이터 타입"), "api");
});

test("카테고리와 API 앵커를 해석하고 알 수 없는 경로는 기본값으로 닫는다", () => {
  const selected = api.categoryRoute("#transactions/op-post-transactions", ["accounts", "transactions", "types"], "accounts");
  assert.equal(selected.category, "transactions");
  assert.equal(selected.anchor, "op-post-transactions");

  const fallback = api.categoryRoute("#unknown", ["accounts", "transactions", "types"], "accounts");
  assert.equal(fallback.category, "accounts");
  assert.equal(fallback.anchor, "");

  const malformed = api.categoryRoute("#%E0%A4%A", ["accounts", "transactions", "types"], "accounts");
  assert.equal(malformed.category, "accounts");
  assert.equal(malformed.anchor, "");
});

test("뷰어는 전체 설명을 펼치지 않고 선택한 API 카테고리만 렌더링한다", () => {
  assert.match(viewerSource, /categoryRoute\(window\.location\.hash/);
  assert.match(viewerSource, /renderCurrentCategory/);
  assert.doesNotMatch(viewerSource, /markdown\(spec\.info\.description/);
  assert.doesNotMatch(viewerSource, /Markdown ↓/);
});

test("실행 결과는 가독성 높은 본문과 HTTP 원문을 함께 제공하고 응답 없는 실패를 구분한다", () => {
  assert.match(viewerSource, /HTTP \$\{response\.status\}/);
  assert.match(viewerSource, /Array\.from\(response\.headers\.entries\(\)\)/);
  assert.match(viewerSource, /tryApi\.responseBody/);
  assert.match(viewerSource, /response-body/);
  assert.match(viewerSource, /응답 헤더와 HTTP 원문/);
  assert.match(viewerSource, /응답 본문 복사/);
  assert.match(viewerSource, /HTTP 원문 복사/);
  assert.match(viewerSource, /응답 없음 · 실행 여부 확인 불가/);
  assert.match(viewerSource, /브라우저가 HTTP 응답을 받지 못했습니다/);
  assert.match(viewerSource, /LOCAL RECOVERY/);
  assert.doesNotMatch(viewerSource, /요청 실패: \$\{error\.message\}/);
});
