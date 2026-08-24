import {
  adminRouteFromPath,
  assetCandidateEmptyState,
  assetCandidateSelectable,
  assetSelectionKey,
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
  toggleAssetSelection,
} from "./app-state.js";

const app = document.querySelector("#app-content .content");
const live = document.querySelector("#live-region");
const globalSearch = document.querySelector("#global-search");
const runtimeEnvironment = document.querySelector("#runtime-environment");
let testRunRefreshTimer = null;
let adminEnvironment = null;

const escapeHtml = (value) => String(value ?? "").replace(/[&<>'"]/g, (character) => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
})[character]);

function timeMarkup(time) {
  if (!time) return '<span class="muted">—</span>';
  return `<span class="time-local">${escapeHtml(time.local)}</span><code class="time-utc">${escapeHtml(time.utc)}</code>`;
}

const coreTime = (value) => timeMarkup(formatCoreTime(value));

function route() {
  return adminRouteFromPath(window.location.pathname);
}

function dualTime(value) {
  return timeMarkup(formatAdminTime(value));
}

function identifier(value, label = "식별자") {
  if (!value) return '<span class="muted">—</span>';
  return `<span class="identifier"><code title="${escapeHtml(value)}">${escapeHtml(value)}</code><button class="copy" data-copy="${escapeHtml(value)}" aria-label="${escapeHtml(label)} 복사">복사</button></span>`;
}

function setActiveNav(current) {
  document.querySelectorAll("[data-route]").forEach((link) => {
    const active = link.dataset.route === current || (current === "testRun" && link.dataset.route === "testRuns");
    link.classList.toggle("active", active);
    if (active) link.setAttribute("aria-current", "page"); else link.removeAttribute("aria-current");
  });
}

function skeleton(title) {
  app.innerHTML = `
    <header class="page-head"><div><p class="eyebrow">불러오는 중</p><h1>${escapeHtml(title)}</h1></div></header>
    <div class="skeleton-grid" aria-label="데이터를 불러오는 중">
      <div class="skeleton block"></div><div class="skeleton block"></div><div class="skeleton block"></div>
    </div><div class="skeleton table"></div>`;
}

function statePanel(kind, retry) {
  const content = {
    empty: ["조회 결과가 없습니다", "필터를 지우고 전체 목록을 다시 확인하세요.", "필터 초기화"],
    forbidden: ["조회 권한이 없습니다", "BCM_VIEWER 역할과 BFF 접근 경계를 확인하세요.", "다시 시도"],
    error: ["데이터를 불러오지 못했습니다", "대상 BCM이 실행 중인지와 BFF 연결 설정을 확인하세요.", "다시 시도"],
  }[kind];
  app.innerHTML = `<section class="state-panel" role="status"><span class="state-mark" aria-hidden="true">!</span><h1>${content[0]}</h1><p>${content[1]}</p><button class="button" id="state-action">${content[2]}</button></section>`;
  document.querySelector("#state-action").addEventListener("click", retry);
}

function statusBanner(payload) {
  if (payload.state === "FRESH") return "";
  const stale = payload.state === "STALE";
  const details = payload.issues?.map((issue) => issue.source).join(", ") || "동기화 시각";
  return `<div class="banner ${stale ? "warning" : "danger"}" role="status"><strong>${stale ? "오래된 데이터" : "부분 조회"}</strong><span>${escapeHtml(details)} 상태를 확인하세요. 성공한 값과 실패한 값을 섞어 최신으로 표시하지 않습니다.</span></div>`;
}

async function request(url, options = {}) {
  const response = await fetch(url, { ...options, headers: { Accept: "application/json", ...(options.headers || {}) } });
  let body = null;
  try { body = await response.json(); } catch { body = null; }
  if (!response.ok) {
    const error = new Error(body?.error?.message || "request failed");
    error.status = response.status;
    error.code = body?.error?.code || "REQUEST_FAILED";
    error.requestId = body?.meta?.requestId || null;
    error.details = body?.error?.details || null;
    throw error;
  }
  return body;
}

async function loadEnvironment() {
  try {
    const environment = await request("/bff/admin/environment");
    adminEnvironment = environment;
    applyEnvironment(environment);
  } catch {
    runtimeEnvironment.firstChild.textContent = "Environment unavailable ";
    document.querySelector("#runtime-capability").textContent = "확인 필요";
  }
}

function applyEnvironment(environment) {
  runtimeEnvironment.firstChild.textContent = `${environment.vendorMode} + ${environment.chainMode} · ${environment.dataSet} 데이터 `;
  document.querySelector("#runtime-capability").textContent = environment.assetManagementEnabled ? "Asset registration" : "Read only";
  document.querySelector("#scope-note").textContent = environment.assetManagementEnabled
    ? "로컬 PC에서 Fireblocks 자산 후보를 비교해 등록할 수 있습니다. 지원 Network 연결은 시작 과정에서 자동 준비됩니다."
    : "조회 전용 콘솔입니다. 상태 변경 기능은 현재 환경에 노출되지 않습니다.";
}

async function loadDashboard() {
  skeleton("대시보드");
  try {
    const [payload, environment] = await Promise.all([
      request("/bff/admin/overview"),
      adminEnvironment ? Promise.resolve(adminEnvironment) : request("/bff/admin/environment"),
    ]);
    adminEnvironment = environment;
    applyEnvironment(environment);
    const data = payload.data;
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">${escapeHtml(environment.vendorMode)} WORKSPACE · ${escapeHtml(environment.chainMode)}</p><h1>무엇부터 시작할까요?</h1><p class="subtitle">Fireblocks에서 읽은 후보와 BCM에 등록한 데이터를 구분하고, 필요한 설정을 순서대로 완료합니다.</p></div><div class="timestamp">데이터 기준 시각<strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}
      ${workspaceSummary(data, environment)}
      ${setupJourney(data, environment)}
      ${preparationChecklist(data.preparationChecks || [])}
      ${webhookRuntimePanel(data.webhook)}
      <section class="panel" aria-labelledby="overview-attention">
        <div class="section-head"><div><h2 id="overview-attention">확인이 필요한 소스</h2><p class="eyebrow">서버가 계산한 조회 상태와 실패 원인만 표시합니다.</p></div><span>조회 상태 ${escapeHtml(data.state)}</span></div>
        ${attentionLedger(data.issues)}
      </section>
      <section class="panel next-step"><div><h2>운영 데이터는 등록 뒤에도 계속 대조합니다</h2><p>거래가 시작되면 통합 검색에서 BCM 상태, Fireblocks 상태와 Webhook 처리 이력을 한 번에 추적할 수 있습니다.</p></div><a class="button primary" href="/admin/search?q=tx" data-link>거래 조사 시작</a></section>`;
    announce("대시보드 조회 완료");
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadDashboard);
  }
}

function workspaceSummary(data, environment) {
  const catalogReady = Number(data.catalogNetworkCount || 0) > 0;
  const source = environment.vendorMode === "FIREBLOCKS" ? "Fireblocks API" : "Fireblocks Stub";
  return `<section class="workspace-summary ${catalogReady ? "ready" : "needs-action"}" aria-labelledby="workspace-summary-title">
    <div class="workspace-copy"><p class="eyebrow">CURRENT DATA SOURCE</p><h2 id="workspace-summary-title">${escapeHtml(source)} 카탈로그</h2><p>${catalogReady ? "BCM이 읽어 저장한 카탈로그입니다. 지원 Network는 시작 과정에서 자동 연결되고 자산은 운영자가 선택합니다." : "아직 네트워크 카탈로그가 없습니다. Fireblocks 인증과 catalog 준비 로그를 확인하세요."}</p><span class="status ${catalogReady ? "success" : "danger"}">${catalogReady ? "CATALOG READY" : "CATALOG REQUIRED"}</span></div>
    <dl class="workspace-metrics">
      <div><dt>Catalog networks</dt><dd>${escapeHtml(data.catalogNetworkCount ?? "—")}</dd></div>
      <div><dt>BCM adopted</dt><dd>${escapeHtml(data.adoptedNetworkCount ?? "—")}</dd></div>
      <div><dt>Available networks</dt><dd>${escapeHtml(data.availableNetworkCount ?? "—")}</dd></div>
      <div><dt>Active assets</dt><dd>${escapeHtml(data.assetMappingCount ?? "—")}</dd></div>
    </dl>
    <div class="workspace-meta"><span>마지막 카탈로그 동기화</span><strong>${coreTime(data.catalogSyncedAt)}</strong></div>
  </section>`;
}

function setupJourney(data, environment) {
  const catalogReady = Number(data.catalogNetworkCount || 0) > 0;
  const networkReady = Number(data.adoptedNetworkCount || 0) > 0;
  const assetReady = Number(data.assetMappingCount || 0) > 0;
  const steps = [
    ["01", "Fireblocks 연결 확인", catalogReady, catalogReady ? `${data.catalogNetworkCount}개 네트워크를 읽었습니다.` : "API 인증과 카탈로그 동기화가 필요합니다.", "/admin/networks", "카탈로그 보기", false],
    ["02", "지원 Network 자동 연결", networkReady, networkReady ? `${data.adoptedNetworkCount}개 지원 Network가 연결되었습니다.` : "로컬 시작 과정의 Network 연결·asset catalog 준비 로그를 확인하세요.", "/admin/networks", "연결 상태 보기", false],
    ["03", "USDC 검색·등록", assetReady, assetReady ? `${data.assetMappingCount}개 자산 매핑이 활성입니다.` : "Network·Contract address·Fireblocks Asset ID를 비교해 선택합니다.", environment.assetManagementEnabled ? "/admin/assets?action=add&q=USDC" : "/admin/assets", environment.assetManagementEnabled ? "USDC 찾기" : "자산 보기", false],
    ["04", "vault·입금 주소 연동", false, "vault와 주소는 Admin이 아니라 DAW-CORE가 사용하는 공개 계정 API로 생성합니다.", environment.developerPortalUrl, "API 문서 열기", true],
  ];
  return `<section class="setup-section" aria-labelledby="setup-title"><div class="section-head"><div><p class="eyebrow">QUICK START</p><h2 id="setup-title">처음 설정하는 순서</h2></div><span>Fireblocks → 자산 → 계정 API</span></div><ol class="setup-grid">${steps.map(([index, title, complete, detail, href, label, external]) => `<li class="setup-card ${complete ? "complete" : "pending"}"><span class="setup-index">${index}</span><div><span class="status ${complete ? "success" : "warning"}">${complete ? "완료" : "확인 필요"}</span><h3>${escapeHtml(title)}</h3><p>${escapeHtml(detail)}</p></div><a class="button${complete ? "" : " primary"}" href="${escapeHtml(href)}" ${external ? 'target="_blank" rel="noopener"' : "data-link"}>${escapeHtml(label)}</a></li>`).join("")}</ol></section>`;
}

function preparationChecklist(checks) {
  const readyCount = checks.filter((check) => check.status === "READY").length;
  return `<section class="panel preparation-panel" aria-labelledby="preparation-heading">
    <div class="section-head"><div><h2 id="preparation-heading">서비스 연결 상태</h2><p class="eyebrow">BCM과 Webhook의 자동 점검 결과입니다.</p></div><span>${escapeHtml(readyCount)} / ${escapeHtml(checks.length)} READY</span></div>
    <ol class="preparation-list">${checks.map((check) => `<li>
      <span class="preparation-index" aria-hidden="true">${escapeHtml(check.key)}</span>
      <div><strong>${escapeHtml(check.label)}</strong><small>${escapeHtml(check.detail)}</small></div>
      <span class="status ${statusTone(check.status)}">${escapeHtml(check.status)}</span>
      <span class="preparation-owner">${check.owner === "AUTO" ? "자동 확인" : "직접 확인"}</span>
      <a class="button" href="${escapeHtml(check.action.href)}" data-link>확인</a>
    </li>`).join("")}</ol>
  </section>`;
}

function webhookRuntimePanel(webhook) {
  if (!webhook) {
    return `<section class="panel webhook-runtime" aria-labelledby="webhook-runtime-heading"><div class="section-head"><div><h2 id="webhook-runtime-heading">Webhook 처리 상태</h2><p class="eyebrow">BCM runtime 원장</p></div><span class="status danger">UNAVAILABLE</span></div><p class="section-empty">Webhook runtime 요약을 읽지 못했습니다.</p></section>`;
  }
  const tone = webhook.state === "HEALTHY" ? "success" : webhook.state === "NEVER_RECEIVED" ? "neutral" : webhook.state === "BACKLOG" ? "warning" : "danger";
  return `<section class="panel webhook-runtime" aria-labelledby="webhook-runtime-heading">
    <div class="section-head"><div><h2 id="webhook-runtime-heading">Webhook 처리 상태</h2><p class="eyebrow">원문 payload와 서명은 표시하지 않습니다.</p></div><span class="status ${tone}">${escapeHtml(webhook.state)}</span></div>
    <div class="runtime-grid">
      <div><span>마지막 수신</span><strong>${dualTime(webhook.lastReceivedAt)}</strong></div>
      <div><span>미처리 Inbox</span><strong class="tabular">${escapeHtml(webhook.pendingInboxCount)}</strong></div>
      <div><span>격리 Inbox</span><strong class="tabular">${escapeHtml(webhook.poisonedInboxCount)}</strong></div>
      <div><span>미발행 Outbox</span><strong class="tabular">${escapeHtml(webhook.pendingOutboxCount)}</strong></div>
      <div><span>격리 Outbox</span><strong class="tabular">${escapeHtml(webhook.poisonedOutboxCount)}</strong></div>
      <a class="button" href="${escapeHtml(webhook.statusPath)}" data-link>비상 운영 확인</a>
    </div>
  </section>`;
}

function attentionLedger(issues) {
  if (!issues.length) {
    return '<ul class="attention-list"><li class="clear"><div><strong>모든 조회 소스가 응답했습니다</strong><small>부분 조회나 오래된 데이터로 표시된 소스가 없습니다.</small></div><span class="attention-meta">FRESH</span></li></ul>';
  }
  return `<ul class="attention-list">${issues.map((issue) => `<li><div><strong>${escapeHtml(issue.source)}</strong><small>${escapeHtml(issue.message)}</small></div><span class="attention-meta">${escapeHtml(issue.code)}</span></li>`).join("")}</ul>`;
}

async function loadNetworks() {
  const url = new URL(window.location.href);
  const filters = filtersFromUrl(url);
  const fullCatalog = filters.view === "all";
  const upstream = new URLSearchParams(url.search);
  upstream.delete("view");
  if (!fullCatalog && !upstream.has("adopted")) upstream.set("adopted", "true");
  skeleton("네트워크");
  try {
    const [payload, environment] = await Promise.all([
      request(`/bff/admin/networks${upstream.size ? `?${upstream}` : ""}`),
      adminEnvironment ? Promise.resolve(adminEnvironment) : request("/bff/admin/environment"),
    ]);
    adminEnvironment = environment;
    applyEnvironment(environment);
    const viewState = resolveViewState({ state: payload.state, data: payload.data });
    const adoptedCount = payload.data.filter((network) => network.code).length;
    const candidateCount = payload.data.filter((network) => !network.code && !network.deprecated).length;
    const filtered = Object.keys(filters).some((key) => key !== "view");
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">BCM NETWORK REGISTRY</p><h1>Networks</h1><p class="subtitle">기본 화면에는 BCM 연결 네트워크만 표시합니다. Fireblocks 원본 전체는 Advanced 진단에서 확인합니다.</p></div><div class="timestamp">${fullCatalog ? `연결 ${adoptedCount} · 미연결 ${candidateCount}` : `연결 ${adoptedCount}`}<strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}${networkFilters(filters, fullCatalog)}
      ${fullCatalog ? '<section class="catalog-note"><div><strong>Fireblocks catalog</strong><span>동기화한 원본 후보</span></div><span aria-hidden="true">→</span><div><strong>BCM 연결</strong><span>업무 요청과 자산 매핑에 사용하는 네트워크</span></div></section>' : ""}
      <section class="panel table-wrap"><table><thead><tr><th>Status / Name</th><th>Chain ID</th><th>Environment</th><th>Synced at (UTC)</th><th>Action</th></tr></thead><tbody>
        ${viewState === "empty" ? `<tr><td colspan="5" class="asset-empty"><strong>${filtered ? "검색 조건에 맞는 네트워크가 없습니다." : "연결된 BCM 네트워크가 없습니다."}</strong><small>${filtered ? "검색어 또는 필터를 바꾸거나 초기화하세요." : "로컬 시작 로그 또는 DAW-ADMIN의 네트워크 설정 요청을 확인하세요."}</small></td></tr>` : payload.data.map((network) => `<tr><td><span class="status ${network.code ? "success" : "neutral"}">${network.code ? "CONNECTED" : "CATALOG ONLY"}</span><strong>${escapeHtml(network.displayName)}</strong><small>${escapeHtml(network.code || "BCM 미연결")}</small></td><td class="mono tabular">${escapeHtml(network.chainId ?? "—")}</td><td><span class="status ${network.testnet ? "warning" : "success"}">${network.testnet ? "Testnet" : "Mainnet"}</span>${network.deprecated ? '<small class="danger-text">deprecated</small>' : ""}</td><td class="mono tabular">${coreTime(network.syncedAt)}</td><td>${network.code ? `<a class="button" href="/admin/assets?network=${encodeURIComponent(network.code)}" data-link>자산 보기</a>` : '<span class="muted">진단 전용</span>'}</td></tr>`).join("")}
      </tbody></table></section>`;
    bindFilter("#network-filter", "/admin/networks");
    announce(`네트워크 ${payload.data.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadNetworks);
  }
}

function networkFilters(filters, fullCatalog) {
  return `<form class="filters network-filters" id="network-filter" aria-label="네트워크 필터">
    <label>Name / BCM code<input name="q" value="${escapeHtml(filters.q || "")}" placeholder="예: Base 또는 BASE" autocomplete="off"></label>
    <label>Chain ID<input name="chainId" inputmode="numeric" value="${escapeHtml(filters.chainId || "")}" placeholder="8453"></label>
    <button class="button primary" type="submit">적용</button><a class="button" href="/admin/networks" data-link>초기화</a>
    <details class="network-advanced-filters" ${fullCatalog || filters.testnet || filters.adopted ? "open" : ""}><summary>Advanced</summary><div>
      ${fullCatalog ? '<input type="hidden" name="view" value="all">' : ""}
      <label>Connection<select name="adopted"><option value="">전체</option>${options(filters.adopted, [["true", "BCM 연결"], ["false", "Catalog only"]])}</select></label>
      <label>Environment<select name="testnet"><option value="">전체</option>${options(filters.testnet, [["false", "Mainnet"], ["true", "Testnet"]])}</select></label>
      <a class="button" href="${fullCatalog ? "/admin/networks" : "/admin/networks?view=all"}" data-link>${fullCatalog ? "BCM 연결 네트워크만 보기" : "Fireblocks 전체 카탈로그 보기"}</a>
    </div></details>
  </form>`;
}

async function loadAssets() {
  const url = new URL(window.location.href);
  const filters = filtersFromUrl(url);
  const discoverySymbol = assetDiscoverySymbol(filters.q);
  skeleton("자산 매핑");
  try {
    const [payload, environment] = await Promise.all([
      request(`/bff/admin/assets${url.search}`),
      adminEnvironment ? Promise.resolve(adminEnvironment) : request("/bff/admin/environment"),
    ]);
    adminEnvironment = environment;
    const viewState = resolveViewState({ state: payload.state, data: payload.data });
    const filtered = Object.keys(filters).length > 0;
    app.innerHTML = `
      <header class="page-head"><div><h1>Assets</h1><p class="subtitle">USDC처럼 알고 있는 자산 이름 하나로 시작하세요. Network는 검색 결과에서 비교해 선택합니다.</p></div><div class="page-actions">${environment.assetManagementEnabled ? '<button class="button primary" id="open-asset-add" type="button">+ 자산 찾아 등록</button>' : ""}<div class="timestamp">${payload.data.length}건<strong>${dualTime(payload.meta.generatedAt)}</strong></div></div></header>
      ${statusBanner(payload)}${assetFilters(filters)}${assetDiscoveryPrompt(filters.q, discoverySymbol, environment.assetManagementEnabled)}
      <section class="panel table-wrap"><table><thead><tr><th>Network</th><th>Symbol</th><th>Fireblocks Asset ID</th><th>Contract address</th><th>Registered at (UTC)</th></tr></thead><tbody>
        ${viewState === "empty" ? `<tr><td colspan="5" class="asset-empty"><strong>${filtered ? "검색 조건에 맞는 asset mapping이 없습니다." : "등록된 asset mapping이 없습니다."}</strong><small>${filtered ? "Network, Symbol 또는 Contract address를 바꾸거나 초기화하세요." : "USDC처럼 알고 있는 이름으로 후보를 검색해 첫 mapping을 등록하세요."}</small></td></tr>` : payload.data.map((asset) => `<tr><td><strong>${escapeHtml(asset.network)}</strong></td><td class="mono">${escapeHtml(asset.symbol)}</td><td>${identifier(asset.fireblocksAssetId, "Fireblocks Asset ID")}</td><td><code title="${escapeHtml(asset.contractAddress || "native")}">${escapeHtml(asset.contractAddress || "Native asset")}</code>${asset.contractAddress ? `<button class="copy" data-copy="${escapeHtml(asset.contractAddress)}" aria-label="Contract address 복사">복사</button>` : ""}</td><td class="mono tabular">${coreTime(asset.registeredAt)}</td></tr>`).join("")}
      </tbody></table></section>
      ${environment.assetManagementEnabled ? assetAddDialog() : '<section class="readonly-callout" role="note"><strong>READ ONLY</strong><span>로컬 자산 매핑 관리가 비활성화되어 있습니다.</span></section>'}`;
    bindFilter("#asset-filter", "/admin/assets");
    bindCopy();
    if (environment.assetManagementEnabled) {
      const openAssetDialog = bindAssetAddDialog(payload.data);
      document.querySelectorAll("[data-discover-symbol]").forEach((button) => button.addEventListener("click", () => {
        openAssetDialog(button.dataset.discoverSymbol, true);
      }));
      if (url.searchParams.get("action") === "add") {
        const initialSymbol = assetDiscoverySymbol(url.searchParams.get("q") || url.searchParams.get("symbol"));
        openAssetDialog(initialSymbol, Boolean(initialSymbol));
      }
    }
    announce(`자산 매핑 ${payload.data.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadAssets);
  }
}

function assetAddDialog() {
  return `<dialog class="asset-dialog" id="asset-add-dialog" aria-labelledby="asset-add-title">
    <div class="asset-dialog-head"><div><p class="eyebrow">LOCAL ASSET MAPPING</p><h2 id="asset-add-title">자산 추가</h2></div><button class="dialog-close" type="button" data-close-asset aria-label="자산 추가 닫기">×</button></div>
    <div class="asset-dialog-body">
      <form class="asset-search" id="asset-candidate-search" role="search">
        <label for="asset-candidate-symbol">어떤 자산을 찾으세요?</label>
        <div><span class="search-icon" aria-hidden="true"></span><input id="asset-candidate-symbol" name="q" minlength="2" maxlength="64" autocomplete="off" placeholder="USDC, USD Coin, contract address" required><button class="button" type="submit">검색</button></div>
      </form>
      <p class="asset-search-help">Symbol, asset name 또는 contract address로 검색합니다.</p>
      <div class="asset-candidate-status" id="asset-candidate-status" role="status">자산을 입력하면 네트워크별 후보를 찾습니다.</div>
      <div class="asset-catalog-sources" id="asset-catalog-sources" aria-label="자산 카탈로그 동기화 상태"></div>
      <div class="asset-candidate-list" id="asset-candidate-list" role="listbox" aria-label="등록 가능한 자산 후보"></div>
      <section class="asset-selection" id="asset-selection" aria-labelledby="asset-selection-title" hidden>
        <div><p class="eyebrow">등록할 자산 확인</p><h3 id="asset-selection-title">선택 0개</h3></div>
        <p class="asset-selection-help">검색을 이어가며 최대 20개를 선택할 수 있습니다. 한 항목이라도 검증에 실패하면 모두 등록되지 않습니다.</p>
        <div id="asset-selection-details" class="asset-selection-items"></div>
      </section>
      <div class="asset-dialog-message" id="asset-dialog-message" role="alert" hidden></div>
      <aside class="asset-info"><span aria-hidden="true">i</span><p><strong>자산 매핑은 덮어쓰지 않습니다.</strong>잘못 등록했다면 주소 발급 여부와 변경 snapshot을 확인해야 합니다.</p></aside>
    </div>
    <div class="asset-dialog-actions"><button class="button" type="button" data-close-asset>취소</button><button class="button primary" id="register-asset" type="button" disabled>선택 자산 등록</button></div>
  </dialog>`;
}

function bindAssetAddDialog(activeMappings) {
  const dialog = document.querySelector("#asset-add-dialog");
  const open = document.querySelector("#open-asset-add");
  const search = dialog.querySelector("#asset-candidate-search");
  const input = dialog.querySelector("#asset-candidate-symbol");
  const list = dialog.querySelector("#asset-candidate-list");
  const status = dialog.querySelector("#asset-candidate-status");
  const sources = dialog.querySelector("#asset-catalog-sources");
  const selection = dialog.querySelector("#asset-selection");
  const register = dialog.querySelector("#register-asset");
  const message = dialog.querySelector("#asset-dialog-message");
  let candidates = [];
  let selected = [];

  const resetSelection = () => {
    selected = [];
    selection.hidden = true;
    register.disabled = true;
    list.querySelectorAll("[role=option]").forEach((option) => option.setAttribute("aria-selected", "false"));
  };
  const renderSelection = () => {
    selection.hidden = selected.length === 0;
    register.disabled = selected.length === 0;
    dialog.querySelector("#asset-selection-title").textContent = `선택 ${selected.length}개`;
    dialog.querySelector("#asset-selection-details").innerHTML = selected.map((item) => `
      <div class="asset-selection-item"><span><strong>${escapeHtml(item.symbol)} · ${escapeHtml(item.network)}</strong><small>${escapeHtml(item.networkDisplayName)} · ${item.testnet ? "Testnet" : "Mainnet"}</small><dl><div><dt>Decimals</dt><dd>${escapeHtml(item.decimals ?? "—")}</dd></div><div><dt>Fireblocks Asset ID</dt><dd>${identifier(item.fireblocksAssetId, "Fireblocks Asset ID")}</dd></div><div><dt>Contract address</dt><dd>${item.contractAddress ? identifier(item.contractAddress, "Contract address") : "Native asset"}</dd></div></dl></span><button type="button" data-remove-selection="${escapeHtml(encodeURIComponent(assetSelectionKey(item)))}" aria-label="${escapeHtml(item.symbol)} ${escapeHtml(item.network)} 선택 해제">×</button></div>`).join("");
    dialog.querySelectorAll("[data-remove-selection]").forEach((button) => button.addEventListener("click", () => {
      const key = decodeURIComponent(button.dataset.removeSelection);
      selected = selected.filter((item) => assetSelectionKey(item) !== key);
      list.querySelectorAll("[data-candidate-index]").forEach((option) => {
        const candidate = candidates[Number(option.dataset.candidateIndex)];
        option.setAttribute("aria-selected", String(selected.some((item) => assetSelectionKey(item) === assetSelectionKey(candidate))));
      });
      renderSelection();
    }));
  };
  const showMessage = (value, tone = "danger") => {
    message.hidden = !value;
    message.className = `asset-dialog-message ${tone}`;
    message.textContent = value || "";
  };
  const close = () => dialog.close();

  const openDialog = (symbol = null, searchNow = false) => {
    if (symbol) input.value = symbol;
    dialog.showModal();
    input.focus();
    if (searchNow) search.requestSubmit();
  };
  open.addEventListener("click", () => openDialog());
  dialog.querySelectorAll("[data-close-asset]").forEach((button) => button.addEventListener("click", close));
  dialog.addEventListener("click", (event) => { if (event.target === dialog) close(); });
  dialog.addEventListener("close", () => {
    search.reset();
    list.innerHTML = "";
    sources.innerHTML = "";
    status.textContent = "자산을 입력하면 네트워크별 후보를 찾습니다.";
    showMessage("");
    resetSelection();
  });

  search.addEventListener("submit", async (event) => {
    event.preventDefault();
    const query = input.value.trim();
    input.value = query;
    if (query.length < 2 || query.length > 64) return;
    showMessage("");
    list.innerHTML = "";
    sources.innerHTML = "";
    status.textContent = `‘${query}’ 후보를 찾는 중입니다…`;
    try {
      const [payload, mappingPayload] = await Promise.all([
        request(`/bff/admin/asset-candidates?q=${encodeURIComponent(query)}`, {
          headers: { "X-BCM-Local-Asset-Management": "execute" },
        }),
        request("/bff/admin/assets"),
      ]);
      candidates = payload.data?.items || [];
      const catalogSources = payload.data?.sources || [];
      sources.innerHTML = assetCatalogSources(catalogSources);
      const currentMappings = mappingPayload.data || activeMappings;
      const mappedCandidates = candidates.map((candidate) => ({ candidate, mapping: registeredAssetMapping(candidate, currentMappings) }));
      const availableCount = mappedCandidates.filter(({ candidate, mapping }) => assetCandidateSelectable(candidate, mapping)).length;
      const registeredCount = mappedCandidates.filter(({ mapping }) => mapping).length;
      const unsupportedCount = mappedCandidates.filter(({ candidate }) => !candidate.registrationAllowed).length;
      const emptyState = candidates.length ? null : assetCandidateEmptyState(query, catalogSources);
      status.textContent = candidates.length
        ? `${candidates.length}개 후보 · 등록 가능 ${availableCount}개${unsupportedCount ? ` · 미지원 ${unsupportedCount}개` : ""}${registeredCount ? ` · 이미 등록 ${registeredCount}개` : ""}`
        : emptyState.message;
      list.innerHTML = candidates.length
        ? mappedCandidates.map(({ candidate, mapping }, index) => assetCandidateRow(candidate, index, mapping, selected.some((item) => assetSelectionKey(item) === assetSelectionKey(candidate)))).join("")
        : assetCandidatePrerequisite(emptyState);
      list.querySelectorAll("[data-candidate-index]").forEach((option) => option.addEventListener("click", () => {
        if (option.disabled) return;
        const candidate = candidates[Number(option.dataset.candidateIndex)];
        const before = selected.length;
        selected = toggleAssetSelection(selected, candidate);
        if (before === 20 && selected.length === 20 && !selected.some((item) => assetSelectionKey(item) === assetSelectionKey(candidate))) {
          showMessage("한 번에 최대 20개까지 등록할 수 있습니다.", "warning");
        }
        option.setAttribute("aria-selected", String(selected.some((item) => assetSelectionKey(item) === assetSelectionKey(candidate))));
        renderSelection();
      }));
    } catch (error) {
      status.textContent = "후보를 불러오지 못했습니다.";
      showMessage(`${error.code}: ${error.message}${error.requestId ? ` · requestId ${error.requestId}` : ""}`);
    }
  });

  register.addEventListener("click", () => runSingleFlight(register, async () => {
    if (!selected.length) return;
    showMessage("");
    const payload = await request("/bff/admin/assets/bulk", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-BCM-Local-Asset-Management": "execute" },
      body: JSON.stringify({ items: selected.map((item) => ({ network: item.network, symbol: item.symbol.toUpperCase(), fireblocksAssetId: item.fireblocksAssetId, contractAddress: item.contractAddress })) }),
    });
    announce(`자산 매핑 ${payload.data.length}건 등록 완료`);
    close();
    navigate("/admin/assets");
  }).catch((error) => {
    const failedItem = error.details
      ? ` · ${Number(error.details.index) + 1}번째 ${error.details.network}/${error.details.symbol} (${error.details.reason})`
      : "";
    showMessage(`${error.code}: ${error.message}${failedItem}${error.requestId ? ` · requestId ${error.requestId}` : ""}`);
  }));
  return openDialog;
}

function assetCandidatePrerequisite(state) {
  const action = state.actionHref
    ? `<a class="button primary" href="${escapeHtml(state.actionHref)}" data-link>${escapeHtml(state.actionLabel)}</a>`
    : "";
  const detail = state.kind === "CATALOG_REQUIRED"
    ? "./scripts/local.sh sync assets를 실행한 뒤 다시 검색하세요."
    : state.detail;
  return `<section class="asset-prerequisite" data-asset-prerequisite="${escapeHtml(state.kind)}" role="note"><div><strong>${escapeHtml(state.message)}</strong><p>${escapeHtml(detail)}</p></div>${action}</section>`;
}

function assetCandidateRow(candidate, index, mapping, selected = false) {
  const address = candidate.contractAddress || "Native asset";
  const selectable = assetCandidateSelectable(candidate, mapping);
  const unavailable = mapping ? `이미 BCM에 등록됨 · ${mapping.symbol}` : candidate.registrationDisabledReason;
  return `<button class="asset-candidate" type="button" role="option" aria-selected="${selected}" data-candidate-index="${index}" ${selectable ? "" : "disabled aria-disabled=\"true\""}>
    <span class="asset-avatar" aria-hidden="true">${escapeHtml(candidate.symbol.slice(0, 2))}</span>
    <span class="asset-candidate-copy"><strong>${escapeHtml(candidate.symbol)} <small>${escapeHtml(candidate.displayName || "")}</small></strong><span>${escapeHtml(candidate.networkDisplayName)} · ${candidate.testnet ? "Testnet" : "Mainnet"}${candidate.chainId == null ? "" : ` · Chain ID ${escapeHtml(candidate.chainId)}`} · ${escapeHtml(candidate.assetClass || "UNKNOWN")} · Decimals ${escapeHtml(candidate.decimals ?? "—")}</span><small>Fireblocks Asset ID</small><code title="${escapeHtml(candidate.fireblocksAssetId)}">${escapeHtml(candidate.fireblocksAssetId)}</code><small>Contract address</small><code title="${escapeHtml(address)}">${escapeHtml(address)}</code><small>Catalog ${coreTime(candidate.catalogSyncedAt)}</small>${unavailable ? `<em>${escapeHtml(unavailable)}</em>` : ""}</span>
    <span class="asset-select-mark" aria-hidden="true">${mapping ? "등록됨" : selected ? "선택됨" : candidate.registrationAllowed ? "선택" : "미지원"}</span>
  </button>`;
}

function assetCatalogSource(source) {
  const tone = { READY: "success", STALE: "warning", NEVER_SYNCED: "danger" }[source.state] || "danger";
  const time = source.catalogSyncedAt ? coreTime(source.catalogSyncedAt) : "동기화 이력 없음";
  const label = source.network || source.networkDisplayName;
  return `<div><strong>${escapeHtml(label)}</strong><span class="status ${tone}">${escapeHtml(source.state)}</span><small>${time}</small></div>`;
}

function assetCatalogSources(sources) {
  if (!sources.length) return "";
  const ready = sources.filter((source) => source.state === "READY").length;
  const stale = sources.filter((source) => source.state === "STALE").length;
  const never = sources.filter((source) => source.state === "NEVER_SYNCED").length;
  const issues = sources.filter((source) => source.state !== "READY").slice(0, 10);
  return `<div><strong>${sources.length} Networks</strong><span class="status ${never ? "danger" : stale ? "warning" : "success"}">READY ${ready}</span><small>STALE ${stale} · NOT SYNCED ${never}</small></div>${issues.map(assetCatalogSource).join("")}`;
}

function assetFilters(filters) {
  const advanced = Boolean(filters.network || filters.symbol);
  return `<form class="filters asset-simple-search" id="asset-filter" aria-label="자산 검색">
    <label>찾을 자산<input name="q" maxlength="128" value="${escapeHtml(filters.q || "")}" placeholder="예: USDC" autocomplete="off"></label>
    <button class="button primary" type="submit">자산 찾기</button><a class="button" href="/admin/assets" data-link>초기화</a>
    <details class="asset-advanced-filters" ${advanced ? "open" : ""}><summary>Advanced</summary><div>
      <label>Network<input name="network" maxlength="20" value="${escapeHtml(filters.network || "")}" placeholder="BASE" autocomplete="off"></label>
      <label>Symbol<input name="symbol" maxlength="16" value="${escapeHtml(filters.symbol || "")}" placeholder="USDC" autocomplete="off"></label>
    </div></details>
  </form>`;
}

function assetDiscoveryPrompt(query, symbol, enabled) {
  if (!enabled || !query) return "";
  if (!symbol) {
    return `<section class="asset-discovery-prompt neutral" role="note"><div><strong>등록 후보는 Symbol로 찾습니다</strong><span>USDC·KRWK처럼 짧은 자산 이름을 입력하면 Network별 후보를 보여 드립니다.</span></div></section>`;
  }
  return `<section class="asset-discovery-prompt" aria-label="Fireblocks 자산 후보 검색"><div><strong>등록된 매핑 밖에서도 ‘${escapeHtml(symbol)}’을 찾을까요?</strong><span>Fireblocks에서 읽은 관련 자산을 네트워크별로 비교한 뒤 선택해 등록할 수 있습니다.</span></div><button class="button primary" type="button" data-discover-symbol="${escapeHtml(symbol)}">Fireblocks 후보에서 찾기</button></section>`;
}

async function loadSearch() {
  const query = new URL(window.location.href).searchParams.get("q") || "";
  if (query.length < 2) return statePanel("empty", () => globalSearch.querySelector("input").focus());
  skeleton("통합 검색");
  try {
    const [payload, environment] = await Promise.all([
      request(`/bff/admin/search?q=${encodeURIComponent(query)}`),
      adminEnvironment ? Promise.resolve(adminEnvironment) : request("/bff/admin/environment"),
    ]);
    adminEnvironment = environment;
    const discoverySymbol = environment.assetManagementEnabled ? assetDiscoverySymbol(query) : null;
    if (!payload.data.length && !discoverySymbol) return statePanel("empty", loadSearch);
    const resultCount = payload.data.length + (discoverySymbol ? 1 : 0);
    app.innerHTML = `
      <header class="page-head"><div><h1>“${escapeHtml(query)}” 검색</h1><p class="subtitle">거래 식별자, 네트워크와 자산 계약을 한 번에 찾습니다.</p></div><div class="timestamp">${resultCount}건<strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}<section class="panel result-list">
        ${discoverySymbol ? `<a href="/admin/assets?action=add&q=${encodeURIComponent(discoverySymbol)}" data-link><span class="result-kind">자산 후보</span><strong>${escapeHtml(discoverySymbol)} 관련 자산 찾기</strong><small>Fireblocks 후보를 네트워크별로 비교하고 선택해 BCM에 등록합니다.</small><span aria-hidden="true">→</span></a>` : ""}
        ${payload.data.map((item) => `<a href="${escapeHtml(item.action.href)}" data-link><span class="result-kind">${escapeHtml(item.kind)}</span><strong>${escapeHtml(item.primary)}</strong><small>${escapeHtml(item.secondary)}</small><span aria-hidden="true">→</span></a>`).join("")}
      </section>`;
    announce(`검색 결과 ${resultCount}건`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadSearch);
  }
}

async function loadTransaction() {
  const identifierValue = transactionIdentifierFromPath(window.location.pathname);
  if (!identifierValue) return statePanel("empty", () => navigate("/admin/search?q=tx"));
  skeleton("거래 조사");
  try {
    const payload = await request(`/bff/admin/transactions/${encodeURIComponent(identifierValue)}`);
    const data = payload.data;
    const summary = data.summary;
    app.innerHTML = `
      <header class="page-head transaction-head"><div><h1>Transaction investigation</h1><p class="subtitle">원거래와 현재 활성 거래를 분리해 제출·Webhook·정합성 상태를 추적합니다.</p></div><div class="timestamp">기준 시각<strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}
      <section class="panel identity-panel" aria-labelledby="transaction-identity">
        <div><p class="eyebrow">원거래</p><h2 id="transaction-identity">${escapeHtml(summary.rootTransactionId)}</h2></div>
        <span class="status ${statusTone(summary.status)}">${escapeHtml(summary.status)}</span>
        <button class="copy" data-copy="${escapeHtml(summary.rootTransactionId)}" aria-label="원거래 ID 복사">전체 ID 복사</button>
      </section>
      <div class="investigation-grid">
        ${summaryPanel(summary)}
        ${diagnosisPanel(summary)}
      </div>
      ${timelinePanel(data.timeline)}
      ${boostPanel(data.boosts)}
      ${sweepPanel(data.sweepExecution)}
      ${allowancePanel(data.allowances)}
      ${feePanel(data.feeQuotes)}
    `;
    bindCopy();
    announce(`거래 ${summary.rootTransactionId} 조사 조회 완료`);
  } catch (error) {
    if (error.status === 404) {
      return transactionEmpty(identifierValue);
    }
    statePanel(error.status === 403 ? "forbidden" : "error", loadTransaction);
  }
}

function transactionEmpty(identifierValue) {
  app.innerHTML = `<section class="state-panel" role="status"><span class="state-mark" aria-hidden="true">!</span><h1>거래를 찾지 못했습니다</h1><p><code>${escapeHtml(identifierValue)}</code>와 연결된 원거래·활성 거래·외부 거래·스윕 실행이 없습니다.</p><a class="button primary" href="/admin/search?q=${encodeURIComponent(identifierValue)}" data-link>통합 검색으로 돌아가기</a></section>`;
}

function statusTone(status) {
  if (["COMPLETED", "CONFIRMED", "SUCCESS", "PASSED", "UP", "ACTIVE", "VALID", "VERIFIED", "APPROVED", "ACTIVATED", "OPEN", "READY"].includes(status)) return "success";
  if (["FAILED", "CANCELLED", "REJECTED", "INVALID", "ERROR", "EXPIRED", "STOPPED"].includes(status)) return "danger";
  return "warning";
}

async function discoverSystemTestRuns() {
  const navigation = document.querySelector("[data-system-test-nav]");
  if (!navigation) return;
  try {
    await request("/bff/admin/test-runs");
    navigation.hidden = false;
  } catch {
    navigation.hidden = true;
  }
}

async function loadTestRuns() {
  skeleton("테스트 실행");
  try {
    let scenarios = [];
    try {
      const catalog = await request("/bff/admin/test-scenarios");
      scenarios = catalog.data.scenarios;
    } catch (error) {
      if (error.status !== 404) throw error;
    }
    const payload = await request("/bff/admin/test-runs");
    const runs = payload.data.runs;
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">LOCAL · FUNCTION TEST</p><h1>Test scenarios &amp; runs</h1><p class="subtitle">Stub 시나리오를 시작하고 실행 단계와 BCM 업무 식별자를 같은 runId로 추적합니다.</p></div><div class="timestamp">최근 ${runs.length}건<strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}
      ${scenarios.length ? scenarioConsole(scenarios) : '<section class="readonly-callout" role="note"><strong>READ ONLY</strong><span>로컬 시나리오는 STUB+LOCAL로 기동했을 때만 표시됩니다.</span></section>'}
      ${runs.length ? `<section class="panel table-wrap"><table><thead><tr><th>Run / Suite</th><th>상태</th><th>진행률</th><th>시작</th><th>실패 단계</th></tr></thead><tbody>
        ${runs.map((run) => `<tr><td><a href="/admin/test-runs/${encodeURIComponent(run.runId)}" data-link><strong>${escapeHtml(run.runId)}</strong></a><small>${escapeHtml(run.suite)}</small></td><td><span class="status ${statusTone(run.state)}">${escapeHtml(run.state)}</span></td><td><div class="run-progress"><progress max="100" value="${escapeHtml(run.progress.percent)}">${escapeHtml(run.progress.percent)}%</progress><span>${escapeHtml(run.progress.percent)}% · ${escapeHtml(run.progress.completedSteps)}/${escapeHtml(run.progress.totalSteps)}</span></div></td><td>${dualTime(run.startedAt)}</td><td><code>${escapeHtml(run.failedStep || "—")}</code></td></tr>`).join("")}
      </tbody></table></section>` : '<section class="state-panel compact-state" role="status"><span class="state-mark" aria-hidden="true">0</span><h2>아직 실행 원장이 없습니다</h2><p>위 시나리오를 선택하거나 <code>./scripts/system-test.sh smoke</code>를 실행하세요.</p></section>'}`;
    bindScenarioForms();
    announce(`테스트 실행 ${runs.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadTestRuns);
  }
}

function scenarioConsole(scenarios) {
  return `<section class="scenario-section"><div class="section-head"><div><h2>실행할 시나리오</h2><p class="eyebrow">STUB + LOCAL ONLY · 동시에 하나씩 실행</p></div></div><div class="scenario-grid">
    ${scenarios.map((scenario) => `<form class="panel scenario-card" data-scenario-id="${escapeHtml(scenario.id)}">
      <div><span class="status warning">${escapeHtml(scenario.estimatedDuration)}</span><h3>${escapeHtml(scenario.title)}</h3><p>${escapeHtml(scenario.description)}</p><small>${escapeHtml(scenario.classification.join(" + "))}</small></div>
      ${scenario.inputs.map((input) => `<label>${escapeHtml(input.label)}<input name="${escapeHtml(input.name)}" value="${escapeHtml(input.defaultValue || "")}" ${input.required ? "required" : ""} maxlength="64" autocomplete="off"></label>`).join("")}
      <button class="button primary" type="submit">실행</button>
    </form>`).join("")}
  </div></section>`;
}

function bindScenarioForms() {
  document.querySelectorAll("[data-scenario-id]").forEach((form) => {
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      const button = form.querySelector("button[type=submit]");
      button.disabled = true;
      button.textContent = "시작 중…";
      const body = Object.fromEntries(new FormData(form).entries());
      try {
        const payload = await request(`/bff/admin/test-scenarios/${encodeURIComponent(form.dataset.scenarioId)}/runs`, {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-BCM-Local-Scenario": "execute" },
          body: JSON.stringify(body),
        });
        navigate(payload.data.href);
      } catch (error) {
        button.disabled = false;
        button.textContent = error.status === 409 ? "다른 실행 진행 중" : "다시 실행";
        announce(error.message);
      }
    });
  });
}

async function loadTestRun() {
  const runId = testRunIdFromPath(window.location.pathname);
  if (!runId) return statePanel("empty", () => navigate("/admin/test-runs"));
  skeleton("테스트 실행 상세");
  try {
    const payload = await request(`/bff/admin/test-runs/${encodeURIComponent(runId)}`);
    const data = payload.data;
    const failure = data.failure;
    const relatedIds = data.relatedIds;
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">${escapeHtml(data.suite)} · ${escapeHtml(data.classification.join(" + "))}</p><h1>${escapeHtml(data.runId)}</h1><p class="subtitle">서버가 검증한 단계 상태와 안전한 진단 정보입니다.</p></div><div class="timestamp">마지막 갱신<strong>${dualTime(data.summary.updatedAt)}</strong></div></header>
      ${statusBanner(payload)}
      <section class="panel identity-panel"><div><p class="eyebrow">실행 상태</p><h2>${escapeHtml(data.state)}</h2></div><span class="status ${statusTone(data.state)}">${escapeHtml(data.progress.percent)}%</span><a class="button" href="/admin/test-runs" data-link>목록</a></section>
      ${failure ? `<section class="banner danger" role="alert"><strong>${escapeHtml(failure.code)}</strong><span>${escapeHtml(failure.message)} · 실패 단계 ${escapeHtml(failure.failedStep)}<br><b>다음 조치:</b> ${escapeHtml(failure.nextAction)} · 재시도 ${failure.retryable ? "가능" : "불가"}</span></section>` : ""}
      <section class="panel"><div class="section-head"><div><h2>단계</h2><p class="eyebrow">${escapeHtml(data.progress.completedSteps)}/${escapeHtml(data.progress.totalSteps)} 완료</p></div></div><ol class="run-steps">${data.steps.map((step) => `<li><span class="status ${statusTone(step.state)}">${escapeHtml(step.state)}</span><div><strong>${escapeHtml(step.name)}</strong><code>${escapeHtml(step.id)}</code><small>${escapeHtml(step.classification.join(" + ") || "이전 artifact · 분류 없음")}</small>${step.observations.length ? `<div class="step-observations">${step.observations.map((observation) => `<div><span>${escapeHtml(observation.type)}</span>${identifier(observation.value, observation.type)}${observation.transactionHref ? `<a href="${escapeHtml(observation.transactionHref)}" data-link>거래 조사</a>` : ""}<small>${dualTime(observation.observedAt)}</small></div>`).join("")}</div>` : ""}</div><span>${step.durationMs === null ? "—" : `${escapeHtml(step.durationMs)}ms`}</span></li>`).join("")}</ol></section>
      <section class="panel"><div class="section-head"><div><h2>Component</h2><p class="eyebrow">원문 로그 없이 마지막 관찰 상태만 표시합니다.</p></div></div><div class="component-grid">${data.components.length ? data.components.map((component) => `<div><strong>${escapeHtml(component.name)}</strong><span class="status ${statusTone(component.state)}">${escapeHtml(component.state)}</span><small>${component.observedAt ? dualTime(component.observedAt) : "관찰 시각 없음"}</small></div>`).join("") : '<p class="muted">기록된 component 상태가 없습니다.</p>'}</div></section>
      <section class="panel"><div class="section-head"><div><h2>연관 업무 식별자</h2><p class="eyebrow">존재하는 식별자만 표시합니다.</p></div>${relatedIds.transactionHref ? `<a class="button primary" href="${escapeHtml(relatedIds.transactionHref)}" data-link>거래 조사 열기</a>` : ""}</div><dl class="related-identifiers">${Object.entries(relatedIds).filter(([key, value]) => key !== "transactionHref" && value).map(([key, value]) => `<div><dt>${escapeHtml(key)}</dt><dd>${identifier(value, key)}</dd></div>`).join("") || '<div><dt>식별자</dt><dd class="muted">아직 기록되지 않았습니다.</dd></div>'}</dl></section>
      <section class="readonly-callout" role="note"><strong>LOCAL ARTIFACT</strong><span>${escapeHtml(data.artifactPath)} · 원문 로그: <code>./scripts/system-test.sh logs ${escapeHtml(data.runId)}</code></span></section>`;
    bindCopy();
    if (payload.state === "FRESH" && shouldRefreshTestRun(data.state)) {
      testRunRefreshTimer = window.setTimeout(() => {
        if (route() === "testRun") loadTestRun();
      }, 2000);
    }
    announce(`테스트 실행 ${data.runId} 상세 조회 완료`);
  } catch (error) {
    if (error.status === 404) return statePanel("empty", () => navigate("/admin/test-runs"));
    statePanel(error.status === 403 ? "forbidden" : "error", loadTestRun);
  }
}

async function loadContracts() {
  skeleton("컨트랙트 레지스트리");
  try {
    const [payload, runtime] = await Promise.all([request("/bff/admin/contracts"), request("/bff/admin/runtime-readiness").catch(() => null)]);
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">SWEEP SAFEGUARD</p><h1>Contract registry</h1><p class="subtitle">BCM이 sweep 실행 시 호출할 안전장치 컨트랙트의 활성 binding과 독립 2-RPC evidence를 대조합니다.</p></div><div class="timestamp">${payload.data.length}개 version<strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}
      ${sweepReadinessPanel(runtime?.data?.sweep)}
      <div class="readonly-callout" role="note"><strong>REGISTRY ONLY</strong><span>이 화면은 컨트랙트를 배포하거나 서명하지 않습니다. 별도 contracts release를 배포한 뒤 Fireblocks Security Admin Vault의 TAP 승인과 온체인 검증 증적을 거쳐 binding을 활성화합니다.</span></div>
      <section class="panel table-wrap"><table><thead><tr><th>Scope / Version</th><th>Address</th><th>Derived state</th><th>Evidence</th><th>Valid until</th><th>Runtime hash</th></tr></thead><tbody>
        ${payload.data.length ? payload.data.map((contract) => `<tr><td><strong>${escapeHtml(contract.scopeId)}</strong><small>${escapeHtml(contract.versionId)}</small></td><td>${identifier(contract.address, "컨트랙트 주소")}</td><td><span class="status ${statusTone(contract.state)}">${escapeHtml(contract.state)}</span></td><td><span class="status ${statusTone(contract.evidenceStatus || "MISSING")}">${escapeHtml(contract.evidenceStatus || "MISSING")}</span></td><td>${dualTime(contract.evidenceValidUntil)}</td><td>${identifier(contract.runtimeCodeHash, "runtime code hash")}</td></tr>`).join("") : '<tr><td colspan="6" class="asset-empty"><strong>등록된 sweep 컨트랙트가 없습니다.</strong><small>현재 sweep은 실행할 수 없습니다. 배포 release와 온체인 검증 절차가 먼저 필요합니다.</small></td></tr>'}
      </tbody></table></section>`;
    bindCopy();
    announce(`컨트랙트 버전 ${payload.data.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadContracts);
  }
}

async function loadVaults() {
  const url = new URL(window.location.href);
  const query = url.searchParams.get("q") || "";
  skeleton("Vaults");
  try {
    const payload = await request(`/bff/admin/vaults${query ? `?q=${encodeURIComponent(query)}` : ""}`);
    const managed = payload.data.filter((vault) => vault.reconciliationStatus === "MANAGED").length;
    const attention = payload.data.length - managed;
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">FIREBLOCKS ↔ BCM RECONCILIATION</p><h1>Vaults</h1><p class="subtitle">Fireblocks workspace의 전체 vault와 BCM 계정 레지스트리를 대조합니다. 생성과 변경은 이 화면에서 하지 않습니다.</p></div><div class="timestamp">관리 ${managed} · 확인 필요 ${attention}<strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}
      <form class="filters asset-simple-search" id="vault-filter" aria-label="Vault 검색"><label>Account / Ref / Vault<input name="q" maxlength="128" value="${escapeHtml(query)}" placeholder="accountId, ref, vault id 또는 name" autocomplete="off"></label><button class="button primary" type="submit">검색</button><a class="button" href="/admin/vaults" data-link>초기화</a></form>
      <section class="catalog-note"><div><strong>MANAGED</strong><span>BCM 계정과 Fireblocks vault가 일치</span></div><span aria-hidden="true">·</span><div><strong>UNMANAGED</strong><span>Fireblocks에만 존재</span></div><span aria-hidden="true">·</span><div><strong>MISSING</strong><span>BCM에는 있으나 Fireblocks에서 찾지 못함</span></div></section>
      <section class="panel table-wrap"><table><thead><tr><th>Status / Vault</th><th>BCM account</th><th>Type / Ref</th><th>Wallets</th><th>Registered at (UTC)</th></tr></thead><tbody>
        ${payload.data.length ? payload.data.map((vault) => `<tr><td><span class="status ${vault.reconciliationStatus === "MANAGED" ? "success" : vault.reconciliationStatus === "UNMANAGED" ? "warning" : "danger"}">${escapeHtml(vault.reconciliationStatus)}</span><strong>${escapeHtml(vault.vendorVaultName || "Fireblocks vault missing")}</strong><small>${identifier(vault.vendorVaultId, "Fireblocks vault ID")}</small></td><td>${vault.accountId ? identifier(vault.accountId, "BCM account ID") : '<span class="muted">—</span>'}</td><td><strong>${escapeHtml(vault.accountType || "—")}</strong><small>${escapeHtml(vault.ref || "BCM 매핑 없음")}</small></td><td class="mono tabular">${escapeHtml(vault.walletCount ?? "—")}</td><td class="mono tabular">${vault.registeredAt ? coreTime(vault.registeredAt) : '<span class="muted">—</span>'}</td></tr>`).join("") : '<tr><td colspan="5" class="asset-empty"><strong>조건에 맞는 vault가 없습니다.</strong><small>검색어를 지우거나 Fireblocks 연결 상태를 확인하세요.</small></td></tr>'}
      </tbody></table></section>`;
    bindFilter("#vault-filter", "/admin/vaults");
    bindCopy();
    announce(`Vault ${payload.data.length}건 대조 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadVaults);
  }
}

async function loadPolicies() {
  skeleton("실행 정책");
  try {
    const [payload, runtime] = await Promise.all([request("/bff/admin/policies"), request("/bff/admin/runtime-readiness").catch(() => null)]);
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">SWEEP EXECUTION RULES</p><h1>Execution policies</h1><p class="subtitle">sweep 대상·최소 금액·allowance·건별/배치 상한의 불변 version과 배포 hard ceiling을 확인합니다.</p></div><div class="timestamp">${payload.data.length}개 version<strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${sweepReadinessPanel(runtime?.data?.sweep)}
      <div class="readonly-callout" role="note"><strong>SERVER DECISION</strong><span>상태와 hard ceiling은 서버가 계산하며 브라우저가 허용 범위를 재구성하지 않습니다.</span></div>
      <section class="panel table-wrap"><table><thead><tr><th>Scope</th><th>Version</th><th>Derived state</th><th>Hard ceiling</th><th>Registered at</th><th>Policy hash</th></tr></thead><tbody>
        ${payload.data.length ? payload.data.map((policy) => `<tr><td><strong>${escapeHtml(policy.scopeId)}</strong><small>${escapeHtml(policy.versionId)}</small></td><td class="mono tabular">v${escapeHtml(policy.versionNumber)} · ${escapeHtml(policy.schemaVersion)}</td><td><span class="status ${statusTone(policy.state)}">${escapeHtml(policy.state)}</span></td><td><span class="status ${policy.ceilingPassed ? "success" : "danger"}">${policy.ceilingPassed ? "PASS" : "BLOCKED"}</span></td><td>${dualTime(policy.registeredAt)}</td><td>${identifier(policy.policyHash, "정책 hash")}</td></tr>`).join("") : '<tr><td colspan="6" class="asset-empty"><strong>등록된 sweep 정책이 없습니다.</strong><small>현재 sweep은 실행할 수 없습니다. DAW-ADMIN의 정책 요청과 승인 절차가 먼저 필요합니다.</small></td></tr>'}
      </tbody></table></section>`;
    bindCopy();
    announce(`정책 버전 ${payload.data.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadPolicies);
  }
}

function sweepReadinessPanel(sweep) {
  if (!sweep) return '<section class="banner danger"><strong>Sweep 상태 확인 실패</strong><span>runtime readiness를 읽지 못했습니다. 실행 가능으로 간주하지 않습니다.</span></section>';
  const ready = sweep.enabled && sweep.state === "READY";
  return `<section class="workspace-summary ${ready ? "ready" : "needs-action"}" aria-label="Sweep 실행 준비 상태"><div class="workspace-copy"><p class="eyebrow">SWEEP RUNTIME</p><h2>${ready ? "Sweep 실행 준비 완료" : "Sweep 운영 비활성"}</h2><p>${ready ? "bcm-bat 실행 heartbeat와 활성 컨트랙트·정책·증적·release·중지 gate가 확인됐습니다." : "설정만 등록돼도 자동 실행되지 않습니다. bcm-bat 관찰 시각과 아래 서버 계산 금지 사유를 먼저 확인하세요."}</p><span class="status ${ready ? "success" : "danger"}">${escapeHtml(sweep.state)}</span></div><dl class="workspace-metrics"><div><dt>Active contracts</dt><dd>${escapeHtml(sweep.activeContractCount)}</dd></div><div><dt>Active policies</dt><dd>${escapeHtml(sweep.activePolicyCount)}</dd></div></dl><div class="workspace-meta"><span>Executor last run</span><strong>${dualTime(sweep.executorLastRunAt)}</strong><span>Executor last success</span><strong>${dualTime(sweep.executorLastSucceededAt)}</strong><span>Disabled reasons</span><strong>${escapeHtml(sweep.disabledReasons.join(", ") || "—")}</strong></div></section>`;
}

async function loadBandS() {
  skeleton("밴드S 운영 원장");
  try {
    const payload = await request("/bff/admin/band-s");
    if (!payload.data.length) {
      app.innerHTML = `<section class="state-panel" role="status"><span class="state-mark" aria-hidden="true">0</span><h1>등록된 밴드S 제안이 없습니다</h1><p>DAW-CORE가 snapshot과 simulation을 등록하면 이 원장에 표시됩니다.</p><a class="button primary" href="/admin/policies" data-link>실행 정책 확인</a></section>`;
      return;
    }
    app.innerHTML = `
      <header class="page-head"><div><h1>Band S ledger</h1><p class="subtitle">입력 snapshot, simulation, 이동안, 승인과 항목별 실행을 고정 hash 문맥으로 대조합니다.</p></div><div class="timestamp">최근 ${payload.data.length}건<strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}
      <div class="readonly-callout" role="note"><strong>LOCAL · READ ONLY</strong><span>상태와 금지 사유는 BCM 서버가 계산합니다. mTLS와 단기 JWT 경계 전에는 승인·예약·제출을 이 화면에서 실행하지 않습니다.</span></div>
      <div class="band-ledger">
        ${payload.data.map(bandSPanel).join("")}
      </div>`;
    bindCopy();
    announce(`밴드S 제안 ${payload.data.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadBandS);
  }
}

function bandSPanel(item) {
  const requestLink = item.requestId
    ? `<a class="text-link mono" href="/admin/change-requests/${encodeURIComponent(item.requestId)}" data-link>${escapeHtml(item.requestId)}</a>`
    : '<span class="muted">요청 전</span>';
  const reasons = item.disabledReasons.length
    ? item.disabledReasons.map((reason) => `<code>${escapeHtml(reason)}</code>`).join("")
    : '<span class="muted">없음</span>';
  return `<article class="panel band-panel">
    <div class="section-head band-title"><div><p class="eyebrow">${escapeHtml(item.direction)} · ${escapeHtml(item.sourceProposalId)}</p><h2>${escapeHtml(item.proposalId)}</h2></div><span class="status ${statusTone(item.state)}">${escapeHtml(item.state)}</span></div>
    <div class="band-metrics" aria-label="밴드 비율과 평가액">
      <div><span>현재 Hot</span><strong>${escapeHtml(item.hotRatio)}%</strong><small>${escapeHtml(item.effectiveHotKrwAmount)} KRW effective</small></div>
      <div><span>정책 밴드</span><strong>${escapeHtml(item.lowerRatio)} · ${escapeHtml(item.targetRatio)} · ${escapeHtml(item.upperRatio)}%</strong><small>하한 · 목표 · 상한</small></div>
      <div><span>이동 후 Hot</span><strong>${escapeHtml(item.afterHotRatio)}%</strong><small>${escapeHtml(item.totalKrwAmount)} KRW 이동</small></div>
      <div><span>승인 / 실행</span><strong>${escapeHtml(item.approvalCount)} / ${escapeHtml(item.requiredApprovals)}</strong><small>${escapeHtml(item.requestState || "REQUEST_NOT_CREATED")} · ${escapeHtml(item.executionStatus || "NOT_RESERVED")}</small></div>
    </div>
    <div class="band-context-grid">
      <section><h3>입력 snapshot</h3><dl class="detail-list compact-details"><div><dt>관측 / 만료</dt><dd>${dualTime(item.observedAt)}${dualTime(item.expiresAt)}</dd></div><div><dt>총 자산</dt><dd class="mono tabular">${escapeHtml(item.totalAssetKrwAmount)} KRW</dd></div><div><dt>관측 Hot / Cold</dt><dd class="mono tabular">${escapeHtml(item.observedHotKrwAmount)} / ${escapeHtml(item.observedColdKrwAmount)}</dd></div><div><dt>완전성</dt><dd><span class="status ${item.inputComplete ? "success" : "danger"}">${item.inputComplete ? "COMPLETE" : "INCOMPLETE"}</span><small>${escapeHtml(item.issueCodes.join(" · ") || "issue 없음")}</small></dd></div></dl></section>
      <section><h3>고정 문맥</h3><dl class="detail-list compact-details"><div><dt>Policy version</dt><dd>${identifier(item.policyVersionId, "정책 버전 ID")}</dd></div><div><dt>Snapshot hash</dt><dd>${identifier(item.snapshotHash, "snapshot hash")}</dd></div><div><dt>Input hash</dt><dd>${identifier(item.inputHash, "input hash")}</dd></div><div><dt>Proposal hash</dt><dd>${identifier(item.proposalHash, "proposal hash")}</dd></div></dl></section>
      <section><h3>승인 · 예약 경계</h3><dl class="detail-list compact-details"><div><dt>변경 요청</dt><dd>${requestLink}</dd></div><div><dt>실행 ID</dt><dd>${identifier(item.executionId, "밴드S 실행 ID")}</dd></div><div><dt>예약 시각</dt><dd>${dualTime(item.reservedAt)}</dd></div><div><dt>도메인 예약 조건</dt><dd><span class="status ${item.executionReady ? "success" : "warning"}">${item.executionReady ? "READY" : "NOT READY"}</span></dd></div></dl><div class="reason-list" aria-label="실행 금지 사유">${reasons}</div></section>
    </div>
    <section class="band-items"><div class="section-head"><div><p class="eyebrow">불변 이동 계획</p><h3>이동 항목</h3></div><span>${item.items.length}건</span></div><div class="table-wrap"><table><thead><tr><th>Sequence / Dependency</th><th>Leg</th><th>Network / Asset</th><th>Source</th><th>Destination</th><th>Amount / KRW</th><th>Latest state</th></tr></thead><tbody>${item.items.map((entry) => `<tr><td class="mono">#${escapeHtml(entry.sequence)}<small>${entry.dependsOnSequence ? `after #${escapeHtml(entry.dependsOnSequence)}` : "independent"}</small></td><td>${escapeHtml(entry.legType)}</td><td>${escapeHtml(entry.network)} / ${escapeHtml(entry.tokenSymbol)}</td><td>${identifier(entry.sourceVaultId, "source vault ID")}</td><td>${identifier(entry.destinationVaultId || entry.destinationAddress, "destination identifier")}</td><td class="mono tabular">${escapeHtml(entry.amount)}<small>${escapeHtml(entry.krwAmount)} KRW · fee ${escapeHtml(entry.expectedFeeAmount)}</small></td><td><span class="status ${statusTone(entry.executionStatus || (entry.executable ? "READY" : "BLOCKED"))}">${escapeHtml(entry.executionStatus || (entry.executable ? "READY" : "BLOCKED"))}</span>${entry.blockReason ? `<small class="danger-text">${escapeHtml(entry.blockReason)}</small>` : ""}</td></tr>`).join("")}</tbody></table></div></section>
  </article>`;
}

async function loadEmergency() {
  skeleton("비상 운영");
  try {
    const payload = await request("/bff/admin/emergency");
    const data = payload.data;
    if (!data.gates.length) {
      app.innerHTML = `<section class="state-panel" role="status"><span class="state-mark" aria-hidden="true">0</span><h1>표시할 실행 게이트가 없습니다</h1><p>채택된 네트워크가 있는지 네트워크 카탈로그에서 먼저 확인하세요.</p><a class="button primary" href="/admin/networks" data-link>네트워크 확인</a></section>`;
      return;
    }
    const stoppedCount = data.gates.filter((gate) => gate.state === "STOPPED").length;
    const controls = data.externalControls || [];
    const confirmedControls = controls.filter((evidence) => evidence.completionReady).length;
    const revocations = data.allowanceRevocations || [];
    const completedRevocations = revocations.filter((revocation) => revocation.status === "COMPLETED").length;
    const recoveries = data.webhookRecoveries || [];
    const completedRecoveries = recoveries.filter((recovery) => recovery.state === "COMPLETED").length;
    const resumes = data.resumes || [];
    const completedResumes = resumes.filter((resume) => resume.state === "RESUMED").length;
    const byNetwork = data.gates.reduce((result, gate) => {
      if (!result.has(gate.network)) result.set(gate.network, []);
      result.get(gate.network).push(gate);
      return result;
    }, new Map());
    app.innerHTML = `
      <header class="page-head"><div><h1>Emergency operations</h1><p class="subtitle">실행 차단, allowance 회수와 Webhook 수신 복구를 서버 계산 결과로 확인합니다.</p></div><div class="timestamp">관측 시각<strong>${dualTime(data.observedAt)}</strong></div></header>
      ${statusBanner(payload)}
      <div class="readonly-callout" role="note"><strong>LOCAL · READ ONLY</strong><span>이 화면은 중지·재개를 실행하지 않습니다. 기존 제출 복구와 비상 approve(0) 허용 여부도 BCM 서버 응답을 그대로 표시합니다.</span></div>
      <section class="gate-summary" aria-label="실행 게이트 요약">
        <div><span>채택 네트워크</span><strong>${escapeHtml(byNetwork.size)}</strong></div>
        <div><span>전체 게이트</span><strong>${escapeHtml(data.gates.length)}</strong></div>
        <div class="${stoppedCount ? "has-stop" : ""}"><span>중지됨</span><strong>${escapeHtml(stoppedCount)}</strong></div>
        <div class="${controls.length !== confirmedControls ? "has-stop" : ""}"><span>외부 통제 확인</span><strong>${escapeHtml(confirmedControls)} / ${escapeHtml(controls.length)}</strong></div>
        <div class="${revocations.length !== completedRevocations ? "has-stop" : ""}"><span>Allowance 회수</span><strong>${escapeHtml(completedRevocations)} / ${escapeHtml(revocations.length)}</strong></div>
        <div class="${recoveries.length !== completedRecoveries ? "has-stop" : ""}"><span>웹훅 복구</span><strong>${escapeHtml(completedRecoveries)} / ${escapeHtml(recoveries.length)}</strong></div>
        <div class="${resumes.length !== completedResumes ? "has-stop" : ""}"><span>강화 재개</span><strong>${escapeHtml(completedResumes)} / ${escapeHtml(resumes.length)}</strong></div>
      </section>
      <div class="gate-ledger">${[...byNetwork.entries()].map(([network, gates]) => executionGateNetwork(network, gates)).join("")}</div>
      ${externalControlLedger(controls)}
      ${allowanceRevocationLedger(revocations)}
      ${executionGateResumeLedger(resumes)}
      ${webhookRecoveryLedger(recoveries)}`;
    announce(`실행 게이트 ${data.gates.length}건, 중지 ${stoppedCount}건, 외부 통제 증적 ${controls.length}건, allowance 회수 ${revocations.length}건, 강화 재개 ${resumes.length}건, 웹훅 복구 ${recoveries.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadEmergency);
  }
}

function executionGateResumeLedger(resumes) {
  if (!resumes.length) {
    return '<section class="panel"><div class="section-head"><div><p class="eyebrow">강화 재개 원장</p><h2>강화 재개</h2></div><span>요청 없음</span></div><p class="section-empty">접수된 재개 요청이 없습니다. 중지 상태는 자동으로 해제되지 않습니다.</p></section>';
  }
  return `<section class="panel" aria-labelledby="resume-ledger-heading">
    <div class="section-head"><div><p class="eyebrow">최신 증적 · 2인 승인</p><h2 id="resume-ledger-heading">강화 재개</h2></div><span>${resumes.length}건</span></div>
    <div class="revocation-ledger">${resumes.map(executionGateResumeCard).join("")}</div>
  </section>`;
}

function executionGateResumeCard(resume) {
  const reasons = resume.disabledReasons.length
    ? resume.disabledReasons.map((reason) => `<code>${escapeHtml(reason)}</code>`).join("")
    : '<span class="muted">서버 계산 금지 사유 없음</span>';
  const issues = resume.issues.length
    ? resume.issues.map((issue) => `<code>${escapeHtml(issue)}</code>`).join("")
    : '<span class="muted">외부 drift issue 없음</span>';
  return `<article class="band-card">
    <div class="section-head"><div><p class="eyebrow">${escapeHtml(resume.network)} · ${escapeHtml(resume.type)}</p><h3>${identifier(resume.requestId, "재개 변경 요청 ID")}</h3></div><span class="status ${statusTone(resume.state)}">${escapeHtml(resume.state)}</span></div>
    <div class="gate-summary" aria-label="재개 준비 상태">
      <div><span>승인</span><strong>${escapeHtml(resume.approvalCount)} / ${escapeHtml(resume.requiredApprovals)}</strong></div>
      <div><span>보안 승인</span><strong>${escapeHtml(resume.securityApprovalCount)}</strong></div>
      <div><span>직전 재검사</span><strong>${escapeHtml(resume.latestCheckStatus || "NOT RUN")}</strong></div>
      <div class="${resume.resumeReady ? "" : "has-stop"}"><span>서버 실행 판정</span><strong>${resume.resumeReady ? "READY" : "BLOCKED"}</strong></div>
      <div class="${resume.retryable ? "" : "has-stop"}"><span>재시도</span><strong>${resume.retryable ? "가능" : "불가"}</strong><small>${escapeHtml(resume.retryCondition)} · ${escapeHtml(resume.statusPath)}</small></div>
    </div>
    <dl class="detail-list compact-details"><div><dt>중지 event</dt><dd>${identifier(resume.stoppedEventId, "중지 event ID")}</dd></div><div><dt>Contract / evidence</dt><dd>${identifier(resume.contractVersionId, "컨트랙트 버전 ID")}<small>${identifier(resume.contractEvidenceId, "컨트랙트 evidence ID")}</small></dd></div><div><dt>Allowance 회수</dt><dd>${identifier(resume.revocationExecutionId, "allowance 회수 실행 ID")}</dd></div><div><dt>원인 해소 증적</dt><dd><span class="mono">${escapeHtml(resume.causeEvidenceUri)}</span><small>${identifier(resume.causeEvidenceHash, "원인 증적 hash")}</small></dd></div><div><dt>요청 / 만료</dt><dd>${dualTime(resume.requestedAt)}<small>만료 ${dualTime(resume.expiresAt)}</small></dd></div><div><dt>재검사 관측 / 만료</dt><dd>${dualTime(resume.latestCheckObservedAt)}<small>${dualTime(resume.latestCheckValidUntil)}</small></dd></div></dl>
    <div class="reason-list" aria-label="재개 금지 사유">${reasons}</div>
    <div class="reason-list" aria-label="외부 재검사 issue">${issues}</div>
  </article>`;
}

function webhookRecoveryLedger(recoveries) {
  if (!recoveries.length) {
    return '<section class="panel"><div class="section-head"><div><p class="eyebrow">웹훅 복구 원장</p><h2>웹훅 수신 복구</h2></div><span>요청 없음</span></div><p class="section-empty">접수된 최근 24시간 실패 알림 복구 요청이 없습니다.</p></section>';
  }
  return `<section class="panel" aria-labelledby="webhook-recovery-heading">
    <div class="section-head"><div><p class="eyebrow">벤더 호출 전 의도 기록</p><h2 id="webhook-recovery-heading">웹훅 수신 복구</h2></div><span>${recoveries.length}건</span></div>
    <div class="revocation-ledger">${recoveries.map(webhookRecoveryCard).join("")}</div>
  </section>`;
}

function webhookRecoveryCard(recovery) {
  const missing = recovery.missingRequiredEvents.length
    ? recovery.missingRequiredEvents.map((event) => `<code>${escapeHtml(event)}</code>`).join("")
    : '<span class="muted">필수 이벤트 누락 없음</span>';
  const window = recovery.scopeFrom && recovery.scopeTo
    ? `${dualTime(recovery.scopeFrom)}<small>~ ${dualTime(recovery.scopeTo)}</small>`
    : '<span class="muted">재전송 호출 전</span>';
  return `<article class="band-card">
    <div class="section-head"><div><p class="eyebrow">${escapeHtml(recovery.scope)} · ${escapeHtml(recovery.workTicket)}</p><h3>${identifier(recovery.requestId, "웹훅 복구 요청 ID")}</h3></div><span class="status ${statusTone(recovery.state)}">${escapeHtml(recovery.state)}</span></div>
    <div class="gate-summary" aria-label="웹훅 복구 상태">
      <div><span>최신 단계</span><strong>${escapeHtml(recovery.latestEvent || "ACCEPTED")}</strong></div>
      <div><span>구독 전 → 현재</span><strong>${escapeHtml(recovery.previousStatus || "—")} → ${escapeHtml(recovery.currentStatus || "—")}</strong></div>
      <div><span>재전송 예약</span><strong>${escapeHtml(recovery.scheduledNotificationCount ?? "—")}</strong></div>
      <div class="${recovery.errorCode ? "has-stop" : ""}"><span>오류</span><strong>${escapeHtml(recovery.errorCode || "NONE")}</strong></div>
      <div class="${recovery.retryable ? "" : "has-stop"}"><span>재시도</span><strong>${recovery.retryable ? "가능" : "불가"}</strong><small>${escapeHtml(recovery.retryCondition)} · ${escapeHtml(recovery.statusPath)}</small></div>
    </div>
    <dl class="detail-list compact-details"><div><dt>Webhook</dt><dd>${identifier(recovery.webhookId, "webhook ID")}</dd></div><div><dt>요청자 / 사유</dt><dd class="mono">${escapeHtml(recovery.requestedByEmployeeNo)}<small>${escapeHtml(recovery.reason)}</small></dd></div><div><dt>독립 승인자</dt><dd class="mono">${escapeHtml(recovery.approvedByEmployeeNo)}<small>${dualTime(recovery.approvedAt)}</small></dd></div><div><dt>요청 시각</dt><dd>${dualTime(recovery.requestedAt)}</dd></div><div><dt>호출 / 결과</dt><dd>${dualTime(recovery.calledAt)}${recovery.resultAt ? `<small>결과 ${dualTime(recovery.resultAt)}</small>` : ""}</dd></div><div><dt>최근 24시간 범위</dt><dd>${window}</dd></div></dl>
    <div class="reason-list" aria-label="누락 필수 이벤트">${missing}</div>
    ${recovery.state === "COMPLETED" ? '<p class="gate-open-note">RESEND_ACCEPTED는 벤더 배차 접수이며 실제 재수신 완료를 뜻하지 않습니다.</p>' : ""}
  </article>`;
}

function allowanceRevocationLedger(revocations) {
  if (!revocations.length) {
    return '<section class="panel"><div class="section-head"><div><p class="eyebrow">승인된 approve(0) 실행</p><h2>Allowance 전량 회수</h2></div><span>실행 없음</span></div><p class="section-empty">승인 대상으로 고정된 allowance 회수 snapshot이 아직 없습니다.</p></section>';
  }
  return `<section class="panel" aria-labelledby="allowance-revocation-heading">
    <div class="section-head"><div><p class="eyebrow">승인된 approve(0) 실행</p><h2 id="allowance-revocation-heading">Allowance 전량 회수</h2></div><span>${revocations.length}건</span></div>
    <div class="revocation-ledger">${revocations.map(allowanceRevocationCard).join("")}</div>
  </section>`;
}

function allowanceRevocationCard(revocation) {
  const requestLink = `<a class="inline-link" href="/admin/change-requests/${encodeURIComponent(revocation.requestId)}" data-link>${identifier(revocation.requestId, "변경 요청 ID")}</a>`;
  return `<article class="band-card">
    <div class="section-head"><div><p class="eyebrow">${escapeHtml(revocation.network)} · binding r${escapeHtml(revocation.contractBindingRevision)}</p><h3>${identifier(revocation.executionId, "allowance 회수 실행 ID")}</h3></div><span class="status ${statusTone(revocation.status)}">${escapeHtml(revocation.status)}</span></div>
    <div class="gate-summary" aria-label="allowance 회수 진행률">
      <div><span>전체</span><strong>${escapeHtml(revocation.totalCount)}</strong></div>
      <div><span>0 확인</span><strong>${escapeHtml(revocation.zeroConfirmedCount)}</strong></div>
      <div><span>제출 중</span><strong>${escapeHtml(revocation.submittingCount)}</strong></div>
      <div class="${revocation.failedCount ? "has-stop" : ""}"><span>실패</span><strong>${escapeHtml(revocation.failedCount)}</strong></div>
      <div class="${revocation.retryable ? "" : "has-stop"}"><span>재시도</span><strong>${revocation.retryable ? "가능" : "불가"}</strong><small>${escapeHtml(revocation.retryCondition)} · ${escapeHtml(revocation.statusPath)}</small></div>
    </div>
    <dl class="detail-list compact-details"><div><dt>변경 요청</dt><dd>${requestLink}</dd></div><div><dt>컨트랙트</dt><dd>${identifier(revocation.contractVersionId, "컨트랙트 버전 ID")}<small>${identifier(revocation.sweepContractAddress, "sweep 컨트랙트 주소")}</small></dd></div><div><dt>Snapshot hash</dt><dd>${identifier(revocation.targetSnapshotHash, "회수 snapshot hash")}</dd></div><div><dt>등록 시각</dt><dd>${dualTime(revocation.registeredAt)}</dd></div></dl>
    <div class="table-wrap"><table><thead><tr><th>항목 / Vault</th><th>자산 / Owner</th><th>승인 전 / 최근 관찰</th><th>최신 상태</th><th>제출 / 오류</th></tr></thead><tbody>
      ${revocation.items.map((item) => `<tr><td class="mono">#${escapeHtml(item.sequence)}<small>${identifier(item.sourceVaultId, "source vault ID")}</small></td><td>${escapeHtml(item.network)} / ${escapeHtml(item.symbol)}<small>${identifier(item.ownerAddress, "owner 주소")}</small><small>${identifier(item.tokenContractAddress, "token 컨트랙트 주소")}</small></td><td class="mono tabular">${escapeHtml(item.beforeObservedAllowance)}<small>${escapeHtml(item.observedAllowance)} · ${dualTime(item.observedAt)}</small></td><td><span class="status ${statusTone(item.latestStatus || "READY")}">${escapeHtml(item.latestStatus || "READY")}</span><small>${dualTime(item.occurredAt)}</small></td><td>${identifier(item.externalTransactionId, "external transaction ID")}<small>${identifier(item.vendorTransactionId, "vendor transaction ID")}</small>${item.errorCode ? `<code>${escapeHtml(item.errorCode)}</code>` : ""}</td></tr>`).join("")}
    </tbody></table></div>
  </article>`;
}

function externalControlLedger(controls) {
  if (!controls.length) {
    return '<section class="panel"><div class="section-head"><div><p class="eyebrow">외부 통제 증적</p><h2>외부 통제 관찰</h2></div><span>증적 없음</span></div><p class="section-empty">TAP 차단·컨트랙트 pause·운영자 제거를 재조회한 증적이 아직 없습니다.</p></section>';
  }
  return `<section class="panel" aria-labelledby="external-control-heading">
    <div class="section-head"><div><p class="eyebrow">외부 통제 증적</p><h2 id="external-control-heading">외부 통제 관찰</h2></div><span>${controls.length}개 네트워크</span></div>
    <div class="table-wrap"><table><thead><tr><th>Network / Status</th><th>TAP batch</th><th>Independent RPC pause</th><th>Operator set hash</th><th>Observed / Valid until</th><th>Audit / Issue</th></tr></thead><tbody>
      ${controls.map(externalControlRow).join("")}
    </tbody></table></div>
  </section>`;
}

function externalControlRow(evidence) {
  const issues = evidence.issues.length
    ? evidence.issues.map((issue) => `<code>${escapeHtml(issue)}</code>`).join("")
    : '<span class="muted">issue 없음</span>';
  const tap = evidence.tapBlocked === null ? "UNCONFIRMED" : evidence.tapBlocked ? "BLOCKED" : "OPEN";
  const firstPause = evidence.firstPaused === null ? "UNCONFIRMED" : evidence.firstPaused ? "PAUSED" : "OPEN";
  const secondPause = evidence.secondPaused === null ? "UNCONFIRMED" : evidence.secondPaused ? "PAUSED" : "OPEN";
  return `<tr>
    <td><strong>${escapeHtml(evidence.network)}</strong><small>${identifier(evidence.evidenceId, "외부 증적 ID")}</small><span class="status ${statusTone(evidence.status)}">${escapeHtml(evidence.status)}</span></td>
    <td><span class="status ${statusTone(tap)}">${escapeHtml(tap)}</span><small>${escapeHtml(evidence.tapSourceId)}</small></td>
    <td><strong>${escapeHtml(evidence.firstEndpointId)} · ${escapeHtml(firstPause)}</strong><small>${escapeHtml(evidence.secondEndpointId)} · ${escapeHtml(secondPause)}</small><code>block ${escapeHtml(evidence.pinnedBlockNumber)}</code></td>
    <td>${identifier(evidence.expectedOperatorSetHash, "기대 운영자 집합 hash")}<small>RPC1 ${escapeHtml(evidence.firstOperatorSetHash || "미확인")}</small><small>RPC2 ${escapeHtml(evidence.secondOperatorSetHash || "미확인")}</small></td>
    <td>${dualTime(evidence.observedAt)}<small>만료 ${dualTime(evidence.validUntil)}</small></td>
    <td><strong>${escapeHtml(evidence.workTicket)}</strong><small>${escapeHtml(evidence.reason)}</small><div class="reason-list">${issues}</div></td>
  </tr>`;
}

function executionGateNetwork(network, gates) {
  const blocked = gates.filter((gate) => !gate.newExecutionAllowed).length;
  return `<section class="panel gate-network" aria-labelledby="gate-${escapeHtml(network)}">
    <div class="section-head"><div><p class="eyebrow">네트워크 실행 경계</p><h2 id="gate-${escapeHtml(network)}">${escapeHtml(network)}</h2></div><span>${blocked ? `${blocked} BLOCKED` : "ALL READY"}</span></div>
    <div class="gate-grid">${gates.map(executionGateCard).join("")}</div>
  </section>`;
}

function executionGateCard(gate) {
  const stopped = gate.state === "STOPPED";
  const audit = stopped
    ? `<dl class="gate-audit"><div><dt>중지 시각</dt><dd>${dualTime(gate.stoppedAt)}</dd></div><div><dt>사유</dt><dd>${escapeHtml(gate.reason || "—")}</dd></div><div><dt>작업 티켓</dt><dd class="mono">${escapeHtml(gate.workTicket || "—")}</dd></div><div><dt>작업자 / 순번</dt><dd class="mono tabular">${escapeHtml(gate.actorEmployeeNo || "—")} · #${escapeHtml(gate.sequence ?? "—")}</dd></div></dl>`
    : '<p class="gate-open-note">기록된 중지 이벤트가 없습니다.</p>';
  const reasons = gate.disabledReasons.length
    ? gate.disabledReasons.map((reason) => `<code>${escapeHtml(reason)}</code>`).join("")
    : '<span class="muted">금지 사유 없음</span>';
  return `<article class="gate-card ${stopped ? "stopped" : ""}">
    <header><div><span class="gate-type">${escapeHtml(gate.type)}</span><span class="status ${statusTone(gate.state)}">${escapeHtml(gate.state)}</span></div><strong>${gate.newExecutionAllowed ? "신규 실행 가능" : "신규 실행 차단"}</strong></header>
    ${audit}
    <dl class="gate-decisions" aria-label="서버 계산 실행 가능 여부">
      ${gateDecision("신규 실행", gate.newExecutionAllowed)}
      ${gateDecision("기존 실행 복구", gate.existingExecutionRecoveryAllowed)}
      ${gateDecision("비상 approve(0)", gate.emergencyRevocationAllowed)}
    </dl>
    <div class="reason-list" aria-label="실행 금지 사유">${reasons}</div>
  </article>`;
}

function gateDecision(label, allowed) {
  return `<div><dt>${escapeHtml(label)}</dt><dd class="decision ${allowed ? "allowed" : "blocked"}">${allowed ? "ALLOWED" : "BLOCKED"}</dd></div>`;
}

async function loadChangeRequest() {
  const requestId = changeRequestIdFromPath(window.location.pathname);
  if (!requestId) return statePanel("empty", () => navigate("/admin/policies"));
  skeleton("변경 요청");
  try {
    const payload = await request(`/bff/admin/change-requests/${encodeURIComponent(requestId)}`);
    const item = payload.data;
    app.innerHTML = `
      <header class="page-head"><div><h1>Change request</h1><p class="subtitle">요청 snapshot과 승인 판단, 활성화 금지 사유를 같은 원장에서 확인합니다.</p></div><div class="timestamp">만료 시각<strong>${dualTime(item.expiresAt)}</strong></div></header>
      <section class="panel identity-panel"><div><p class="eyebrow">${escapeHtml(item.targetType)} · ${escapeHtml(item.risk)}</p><h2>${escapeHtml(item.requestId)}</h2></div><span class="status ${statusTone(item.state)}">${escapeHtml(item.state)}</span>${identifier(item.snapshotHash, "요청 snapshot hash")}</section>
      <div class="readonly-callout" role="note"><strong>LOCAL · READ ONLY</strong><span>${item.disabledReasons.length ? escapeHtml(item.disabledReasons.join(" · ")) : "인증 경계 미구현으로 이 화면에서는 승인·활성화를 실행하지 않습니다."}</span></div>
      <div class="governance-grid">
        <section class="panel detail-panel"><div class="section-head"><div><p class="eyebrow">요청 문맥</p><h2>요청 정보</h2></div></div><dl class="detail-list"><div><dt>Scope</dt><dd>${escapeHtml(item.scopeId)}</dd></div><div><dt>대상 버전</dt><dd>${identifier(item.targetVersionId, "대상 버전 ID")}</dd></div><div><dt>요청자</dt><dd class="mono">${escapeHtml(item.requesterEmployeeNo)}</dd></div><div><dt>작업 티켓</dt><dd>${escapeHtml(item.workTicket)}</dd></div><div><dt>요청 시각</dt><dd>${dualTime(item.requestedAt)}</dd></div><div><dt>사유</dt><dd>${escapeHtml(item.reason)}</dd></div></dl></section>
        <section class="panel quorum-panel"><div class="section-head"><div><p class="eyebrow">승인 정족수</p><h2>승인 진행</h2></div><span class="mono tabular">${escapeHtml(item.approvalCount)} / ${escapeHtml(item.requiredApprovals)}</span></div><div class="quorum-count"><strong>${escapeHtml(item.approvalCount)}</strong><span>독립 승인 완료</span></div><p>${item.securityApprovalRequired ? `보안 승인 ${escapeHtml(item.securityApprovalCount)}건 포함 필요` : "일반 독립 승인 1건 필요"}</p></section>
      </div>
      <section class="panel diff-panel"><div class="section-head"><div><p class="eyebrow">서버 snapshot</p><h2>Diff · 영향</h2></div></div><div class="snapshot-grid"><div><h3>변경 diff</h3><pre>${escapeHtml(prettyJson(item.diff))}</pre></div><div><h3>영향 snapshot</h3><pre>${escapeHtml(prettyJson(item.impact))}</pre></div></div></section>
      <section class="panel related-panel"><div class="section-head"><div><p class="eyebrow">독립 판단</p><h2>판단 원장</h2></div><span>${item.decisions.length}건</span></div>${item.decisions.length ? `<div class="table-wrap"><table><thead><tr><th>판단자</th><th>역할</th><th>판단</th><th>의견</th><th>시각</th></tr></thead><tbody>${item.decisions.map((decision) => `<tr><td class="mono">${escapeHtml(decision.employeeNo)}</td><td>${escapeHtml(decision.role)}</td><td><span class="status ${statusTone(decision.decision)}">${escapeHtml(decision.decision)}</span></td><td>${escapeHtml(decision.opinion || "—")}</td><td>${dualTime(decision.decidedAt)}</td></tr>`).join("")}</tbody></table></div>` : '<p class="section-empty">아직 기록된 판단이 없습니다.</p>'}</section>`;
    bindCopy();
    announce(`변경 요청 ${item.requestId} 조회 완료`);
  } catch (error) {
    if (error.status === 404) return statePanel("empty", () => navigate("/admin/policies"));
    statePanel(error.status === 403 ? "forbidden" : "error", loadChangeRequest);
  }
}

function prettyJson(value) {
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
}

function summaryPanel(summary) {
  const fields = [
    ["원거래 ID", identifier(summary.rootTransactionId, "원거래 ID")],
    ["활성 거래 ID", identifier(summary.activeTransactionId, "활성 거래 ID")],
    ["외부 거래 ID", identifier(summary.externalTransactionId, "외부 거래 ID")],
    ["트랜잭션 해시", identifier(summary.transactionHash, "트랜잭션 해시")],
    ["계정", escapeHtml(summary.accountId)],
    ["Network / Symbol", `${escapeHtml(summary.network)} / ${escapeHtml(summary.symbol)}`],
    ["유형", escapeHtml(summary.transactionType || "—")],
    ["금액", `<span class="mono tabular">${escapeHtml(summary.amount || "—")}</span>`],
    ["송신 계정", escapeHtml(summary.senderAccountId || "—")],
    ["수신 대상", `${escapeHtml(summary.receiverType || "—")} · ${escapeHtml(summary.receiverValue || "—")}`],
  ];
  return `<section class="panel detail-panel" aria-labelledby="transaction-summary"><div class="section-head"><div><p class="eyebrow">연결된 식별자</p><h2 id="transaction-summary">거래 요약</h2></div></div><dl class="detail-list">${fields.map(([label, value]) => `<div><dt>${label}</dt><dd>${value}</dd></div>`).join("")}</dl></section>`;
}

function diagnosisPanel(summary) {
  const fields = [
    ["BCM 상태", `<span class="status ${statusTone(summary.status)}">${escapeHtml(summary.status)}</span>`],
    ["제출 상태", escapeHtml(summary.submissionStatus || "—")],
    ["벤더 하위 상태", escapeHtml(summary.vendorSubStatus || "—")],
    ["벤더 네트워크 상태", escapeHtml(summary.vendorNetworkStatus || "—")],
    ["Confirmations", `<span class="mono tabular">${escapeHtml(summary.confirmationCount)}</span>`],
    ["정합성 검사", `${escapeHtml(summary.reconciliationCheckCount)}회`],
    ["최종 변경", dualTime(summary.lastChangedAt)],
    ["정합성 확인", dualTime(summary.reconciliationCheckedAt)],
    ["정합성 중단", dualTime(summary.reconciliationStoppedAt)],
  ];
  return `<section class="panel detail-panel" aria-labelledby="current-diagnosis"><div class="section-head"><div><p class="eyebrow">현재 진단</p><h2 id="current-diagnosis">현재 진단</h2></div></div><dl class="detail-list diagnostic">${fields.map(([label, value]) => `<div><dt>${label}</dt><dd>${value}</dd></div>`).join("")}</dl></section>`;
}

function timelinePanel(entries) {
  return `<section class="panel timeline-panel" aria-labelledby="transaction-timeline"><div class="section-head"><div><p class="eyebrow">시간순 증적</p><h2 id="transaction-timeline">거래 타임라인</h2></div><span>${entries.length}건</span></div>${entries.length ? `<ol class="timeline">${entries.map((entry) => `<li><div class="timeline-rail" aria-hidden="true"></div><div class="timeline-source">${escapeHtml(entry.source)}</div><div class="timeline-body"><strong>${escapeHtml(entry.code)}</strong>${entry.status ? `<span class="status ${statusTone(entry.status)}">${escapeHtml(entry.status)}</span>` : ""}${entry.identifier ? identifier(entry.identifier, "타임라인 식별자") : ""}</div><time datetime="${escapeHtml(entry.observedAt || "")}">${dualTime(entry.observedAt)}</time></li>`).join("")}</ol>` : '<p class="section-empty">기록된 타임라인이 없습니다.</p>'}</section>`;
}

function boostPanel(boosts) {
  if (!boosts.length) return "";
  return `<section class="panel related-panel" aria-labelledby="boost-attempts"><div class="section-head"><div><p class="eyebrow">교체 체인</p><h2 id="boost-attempts">부스트 시도</h2></div><span>${boosts.length}건</span></div><div class="table-wrap"><table><thead><tr><th>순번 / 상태</th><th>교체 전</th><th>교체 후</th><th>수수료</th><th>요청 시각</th></tr></thead><tbody>${boosts.map((boost) => `<tr><td><strong>#${escapeHtml(boost.attemptSequence)}</strong><small>${escapeHtml(boost.status)}</small></td><td>${identifier(boost.replacedTransactionId, "교체 전 거래 ID")}</td><td>${identifier(boost.newTransactionId, "교체 후 거래 ID")}</td><td>${escapeHtml(boost.feeLevel)}<small>gasless ${boost.gasless ? "yes" : "no"}</small></td><td>${dualTime(boost.requestedAt)}</td></tr>`).join("")}</tbody></table></div></section>`;
}

function sweepPanel(sweep) {
  if (!sweep) return "";
  return `<section class="panel related-panel" aria-labelledby="sweep-execution"><div class="section-head"><div><p class="eyebrow">1:N 실행</p><h2 id="sweep-execution">스윕 실행</h2></div><span class="status ${statusTone(sweep.status)}">${escapeHtml(sweep.status)}</span></div><dl class="detail-list compact-details"><div><dt>실행 ID</dt><dd>${identifier(sweep.executionId, "스윕 실행 ID")}</dd></div><div><dt>외부 거래 ID</dt><dd>${identifier(sweep.externalTransactionId, "스윕 외부 거래 ID")}</dd></div><div><dt>요청 / 실제 합계</dt><dd class="mono tabular">${escapeHtml(sweep.requestedTotalAmount)} / ${escapeHtml(sweep.actualTotalAmount || "—")}</dd></div><div><dt>요청 시각</dt><dd>${dualTime(sweep.requestedAt)}</dd></div></dl><div class="table-wrap"><table><thead><tr><th>순번</th><th>계정 / 주소</th><th>요청 / 실제</th><th>상태</th></tr></thead><tbody>${sweep.items.map((item) => `<tr><td class="mono">${escapeHtml(item.sequence)}</td><td>${escapeHtml(item.accountId)}<small><code title="${escapeHtml(item.sourceAddress)}">${escapeHtml(item.sourceAddress)}</code></small></td><td class="mono tabular">${escapeHtml(item.requestedAmount)}<small>${escapeHtml(item.actualAmount || "—")}</small></td><td><span class="status ${statusTone(item.status)}">${escapeHtml(item.status)}</span>${item.failureCode ? `<small class="danger-text">${escapeHtml(item.failureCode)}</small>` : ""}</td></tr>`).join("")}</tbody></table></div></section>`;
}

function allowancePanel(allowances) {
  if (!allowances.length) return "";
  return `<section class="panel related-panel" aria-labelledby="allowances"><div class="section-head"><div><p class="eyebrow">승인 증적</p><h2 id="allowances">Allowance</h2></div><span>${allowances.length}건</span></div><div class="table-wrap"><table><thead><tr><th>Account</th><th>Network / Symbol</th><th>Cap / Observed</th><th>Status</th><th>Checked at</th></tr></thead><tbody>${allowances.map((item) => `<tr><td>${escapeHtml(item.accountId)}</td><td>${escapeHtml(item.network)} / ${escapeHtml(item.symbol)}</td><td class="mono tabular">${escapeHtml(item.cap)}<small>${escapeHtml(item.observedAllowance)}</small></td><td><span class="status ${statusTone(item.status)}">${escapeHtml(item.status)}</span></td><td>${dualTime(item.checkedAt)}</td></tr>`).join("")}</tbody></table></div></section>`;
}

function feePanel(quotes) {
  if (!quotes.length) return "";
  return `<section class="panel related-panel" aria-labelledby="fee-quotes"><div class="section-head"><div><p class="eyebrow">관측 수수료</p><h2 id="fee-quotes">수수료 견적</h2></div><span>${quotes.length}건</span></div><div class="table-wrap"><table><thead><tr><th>맥락 / 레벨</th><th>Network fee</th><th>Gas price</th><th>Base / Priority</th><th>관측 시각</th></tr></thead><tbody>${quotes.map((quote) => `<tr><td>${escapeHtml(quote.context)}<small>${escapeHtml(quote.level)}</small></td><td class="mono tabular">${escapeHtml(quote.networkFee || quote.feePerByte || "—")}</td><td class="mono tabular">${escapeHtml(quote.gasPrice || "—")}</td><td class="mono tabular">${escapeHtml(quote.baseFee || "—")}<small>${escapeHtml(quote.priorityFee || "—")}</small></td><td>${dualTime(quote.observedAt)}</td></tr>`).join("")}</tbody></table></div></section>`;
}

function options(selected, values) {
  return values.map(([value, label]) => `<option value="${value}" ${selected === value ? "selected" : ""}>${label}</option>`).join("");
}

function bindFilter(selector, path) {
  document.querySelector(selector).addEventListener("submit", (event) => {
    event.preventDefault();
    navigate(filtersToUrl(path, Object.fromEntries(new FormData(event.currentTarget))));
  });
}

function bindCopy() {
  document.querySelectorAll("[data-copy]").forEach((button) => button.addEventListener("click", () =>
    runSingleFlight(button, async () => {
      await navigator.clipboard.writeText(button.dataset.copy);
      announce("전체 식별자를 복사했습니다");
      showCopied(button);
    })));
}

function showCopied(button) {
  if (!button.dataset.label) button.dataset.label = button.textContent;
  button.classList.add("copied");
  button.textContent = "복사됨 ✓";
  clearTimeout(button.copiedTimer);
  button.copiedTimer = setTimeout(() => {
    button.classList.remove("copied");
    button.textContent = button.dataset.label;
  }, 1500);
}

function announce(message) { live.textContent = message; }

function navigate(href) {
  history.pushState({}, "", href);
  render();
  document.querySelector("#app-content").focus();
}

function render() {
  if (testRunRefreshTimer !== null) {
    window.clearTimeout(testRunRefreshTimer);
    testRunRefreshTimer = null;
  }
  const current = route();
  if (current !== "networks") document.querySelector("#network-adopt-dialog")?.remove();
  setActiveNav(current);
  ({ dashboard: loadDashboard, transaction: loadTransaction, networks: loadNetworks, assets: loadAssets, vaults: loadVaults, contracts: loadContracts, policies: loadPolicies, bandS: loadBandS, emergency: loadEmergency, changeRequest: loadChangeRequest, search: loadSearch, testRuns: loadTestRuns, testRun: loadTestRun })[current]();
}

document.addEventListener("click", (event) => {
  const link = event.target.closest("a[data-link]");
  if (!link || link.origin !== window.location.origin) return;
  event.preventDefault();
  navigate(link.pathname + link.search);
});

globalSearch.addEventListener("submit", (event) => {
  event.preventDefault();
  const query = new FormData(globalSearch).get("q")?.toString().trim();
  if (query && query.length >= 2) navigate(`/admin/search?q=${encodeURIComponent(query)}`);
});

document.addEventListener("keydown", (event) => {
  if (!isGlobalSearchShortcut(event)) return;
  event.preventDefault();
  const query = globalSearch.querySelector("input");
  query.focus();
  query.select();
});

window.addEventListener("popstate", () => {
  render();
  document.querySelector("#app-content").focus();
});
render();
loadEnvironment();
discoverSystemTestRuns();
