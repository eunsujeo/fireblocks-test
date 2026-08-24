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
} from "./app-state.js";

const app = document.querySelector("#app-content .content");
const live = document.querySelector("#live-region");
const globalSearch = document.querySelector("#global-search");

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
    const active = link.dataset.route === current;
    link.classList.toggle("active", active);
    if (active) link.setAttribute("aria-current", "page"); else link.removeAttribute("aria-current");
  });
}

function skeleton(title) {
  app.innerHTML = `
    <header class="page-head"><div><p class="eyebrow">Read-only ledger</p><h1>${escapeHtml(title)}</h1></div></header>
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

async function request(url) {
  const response = await fetch(url, { headers: { Accept: "application/json" } });
  let body = null;
  try { body = await response.json(); } catch { body = null; }
  if (!response.ok) {
    const error = new Error(body?.error?.message || "request failed");
    error.status = response.status;
    throw error;
  }
  return body;
}

async function loadDashboard() {
  skeleton("대시보드");
  try {
    const payload = await request("/bff/admin/overview");
    const data = payload.data;
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">Functional test console</p><h1>대시보드</h1><p class="subtitle">BCM 네트워크와 자산 매핑의 읽기 상태를 먼저 확인합니다.</p></div><div class="timestamp">기준 시각<br><strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}
      <section class="metric-grid" aria-label="조회 요약">
        ${metric("채택 네트워크", data.networkCount, "BCM catalog")}
        ${metric("Testnet", data.testnetCount, "환경 구분")}
        ${metric("자산 매핑", data.assetMappingCount, "활성 mapping")}
        ${metric("조회 상태", data.state, data.issues.length ? `${data.issues.length}개 소스 확인 필요` : "모든 소스 응답")}
      </section>
      <section class="panel next-step"><div><p class="eyebrow">Next check</p><h2>네트워크 → 자산 순서로 계약을 확인하세요</h2><p>채택 체인의 chainId와 동기화 시각을 확인한 뒤 자산 매핑의 컨트랙트 주소를 대조합니다.</p></div><a class="button primary" href="/admin/networks" data-link>네트워크 열기</a></section>`;
    announce("대시보드 조회 완료");
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadDashboard);
  }
}

function metric(label, value, note) {
  return `<article class="metric"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value ?? "—")}</strong><small>${escapeHtml(note)}</small></article>`;
}

async function loadNetworks() {
  const url = new URL(window.location.href);
  const filters = filtersFromUrl(url);
  skeleton("네트워크");
  try {
    const payload = await request(`/bff/admin/networks${url.search}`);
    const viewState = resolveViewState({ state: payload.state, data: payload.data });
    if (viewState === "empty") return statePanel("empty", () => navigate("/admin/networks"));
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">Network catalog</p><h1>네트워크</h1><p class="subtitle">채택 여부·chainId·testnet·동기화 시각을 한 계약으로 확인합니다.</p></div><div class="timestamp">${payload.data.length}건<br><strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}${networkFilters(filters)}
      <section class="panel table-wrap"><table><thead><tr><th>코드 / 이름</th><th>Chain ID</th><th>환경</th><th>동기화 UTC</th><th>전체 ID</th></tr></thead><tbody>
        ${payload.data.map((network) => `<tr><td><strong>${escapeHtml(network.code || "미채택")}</strong><small>${escapeHtml(network.displayName)}</small></td><td class="mono tabular">${escapeHtml(network.chainId ?? "—")}</td><td><span class="status ${network.testnet ? "warning" : "success"}">${network.testnet ? "TESTNET" : "MAINNET"}</span>${network.deprecated ? '<small class="danger-text">deprecated</small>' : ""}</td><td class="mono tabular">${coreTime(network.syncedAt)}</td><td><code title="${escapeHtml(network.candidateId)}">${escapeHtml(network.candidateId)}</code><button class="copy" data-copy="${escapeHtml(network.candidateId)}" aria-label="candidate ID 복사">복사</button></td></tr>`).join("")}
      </tbody></table></section>`;
    bindFilter("#network-filter", "/admin/networks");
    bindCopy();
    announce(`네트워크 ${payload.data.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadNetworks);
  }
}

function networkFilters(filters) {
  return `<form class="filters" id="network-filter" aria-label="네트워크 필터">
    <label>이름<input name="q" value="${escapeHtml(filters.q || "")}" placeholder="예: Base"></label>
    <label>Chain ID<input name="chainId" inputmode="numeric" value="${escapeHtml(filters.chainId || "")}" placeholder="8453"></label>
    <label>채택<select name="adopted"><option value="">전체</option>${options(filters.adopted, [["true", "채택"], ["false", "미채택"]])}</select></label>
    <label>환경<select name="testnet"><option value="">전체</option>${options(filters.testnet, [["false", "Mainnet"], ["true", "Testnet"]])}</select></label>
    <button class="button primary" type="submit">적용</button><a class="button" href="/admin/networks" data-link>초기화</a>
  </form>`;
}

async function loadAssets() {
  const url = new URL(window.location.href);
  const filters = filtersFromUrl(url);
  skeleton("자산 매핑");
  try {
    const payload = await request(`/bff/admin/assets${url.search}`);
    const viewState = resolveViewState({ state: payload.state, data: payload.data });
    if (viewState === "empty") return statePanel("empty", () => navigate("/admin/assets"));
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">Asset bindings</p><h1>자산 매핑</h1><p class="subtitle">벤더 식별자를 노출하지 않고 네트워크·심볼·컨트랙트 주소를 대조합니다.</p></div><div class="timestamp">${payload.data.length}건<br><strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}${assetFilters(filters)}
      <section class="panel table-wrap"><table><thead><tr><th>네트워크</th><th>심볼</th><th>컨트랙트 주소</th><th>등록 UTC</th></tr></thead><tbody>
        ${payload.data.map((asset) => `<tr><td><strong>${escapeHtml(asset.network)}</strong></td><td class="mono">${escapeHtml(asset.symbol)}</td><td><code title="${escapeHtml(asset.contractAddress || "native")}">${escapeHtml(asset.contractAddress || "native asset")}</code>${asset.contractAddress ? `<button class="copy" data-copy="${escapeHtml(asset.contractAddress)}" aria-label="컨트랙트 주소 복사">복사</button>` : ""}</td><td class="mono tabular">${coreTime(asset.registeredAt)}</td></tr>`).join("")}
      </tbody></table></section>`;
    bindFilter("#asset-filter", "/admin/assets");
    bindCopy();
    announce(`자산 매핑 ${payload.data.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadAssets);
  }
}

function assetFilters(filters) {
  return `<form class="filters compact" id="asset-filter" aria-label="자산 필터">
    <label>네트워크<input name="network" value="${escapeHtml(filters.network || "")}" placeholder="BASE"></label>
    <label>심볼<input name="symbol" value="${escapeHtml(filters.symbol || "")}" placeholder="USDC"></label>
    <button class="button primary" type="submit">적용</button><a class="button" href="/admin/assets" data-link>초기화</a>
  </form>`;
}

async function loadSearch() {
  const query = new URL(window.location.href).searchParams.get("q") || "";
  if (query.length < 2) return statePanel("empty", () => globalSearch.querySelector("input").focus());
  skeleton("통합 검색");
  try {
    const payload = await request(`/bff/admin/search?q=${encodeURIComponent(query)}`);
    if (!payload.data.length) return statePanel("empty", loadSearch);
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">Unified search</p><h1>“${escapeHtml(query)}” 검색</h1><p class="subtitle">거래 식별자, 네트워크와 자산 계약을 한 번에 찾습니다.</p></div><div class="timestamp">${payload.data.length}건<br><strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}<section class="panel result-list">${payload.data.map((item) => `<a href="${escapeHtml(item.action.href)}" data-link><span class="result-kind">${escapeHtml(item.kind)}</span><strong>${escapeHtml(item.primary)}</strong><small>${escapeHtml(item.secondary)}</small><span aria-hidden="true">→</span></a>`).join("")}</section>`;
    announce(`검색 결과 ${payload.data.length}건`);
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
      <header class="page-head transaction-head"><div><p class="eyebrow">Transaction investigation</p><h1>거래 조사</h1><p class="subtitle">원거래와 현재 활성 거래를 분리해 제출·웹훅·정합성 상태를 추적합니다.</p></div><div class="timestamp">기준 시각<br><strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}
      <section class="panel identity-panel" aria-labelledby="transaction-identity">
        <div><p class="eyebrow">Root transaction</p><h2 id="transaction-identity">${escapeHtml(summary.rootTransactionId)}</h2></div>
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
  if (["COMPLETED", "CONFIRMED", "SUCCESS", "ACTIVE", "VALID", "VERIFIED", "APPROVED", "ACTIVATED", "OPEN"].includes(status)) return "success";
  if (["FAILED", "CANCELLED", "REJECTED", "INVALID", "ERROR", "EXPIRED", "STOPPED"].includes(status)) return "danger";
  return "warning";
}

async function loadContracts() {
  skeleton("컨트랙트 레지스트리");
  try {
    const payload = await request("/bff/admin/contracts");
    if (!payload.data.length) return statePanel("empty", loadContracts);
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">Immutable contract registry</p><h1>컨트랙트 레지스트리</h1><p class="subtitle">활성 binding과 최신 독립 2-RPC evidence를 함께 대조합니다.</p></div><div class="timestamp">${payload.data.length}개 버전<br><strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      ${statusBanner(payload)}
      <div class="readonly-callout" role="note"><strong>READ ONLY</strong><span>활성화는 mTLS와 단기 JWT 경계가 준비된 공유 환경에서만 허용됩니다.</span></div>
      <section class="panel table-wrap"><table><thead><tr><th>Scope / 버전</th><th>주소</th><th>파생 상태</th><th>Evidence</th><th>유효 시각</th><th>Runtime hash</th></tr></thead><tbody>
        ${payload.data.map((contract) => `<tr><td><strong>${escapeHtml(contract.scopeId)}</strong><small>${escapeHtml(contract.versionId)}</small></td><td>${identifier(contract.address, "컨트랙트 주소")}</td><td><span class="status ${statusTone(contract.state)}">${escapeHtml(contract.state)}</span></td><td><span class="status ${statusTone(contract.evidenceStatus || "MISSING")}">${escapeHtml(contract.evidenceStatus || "MISSING")}</span></td><td>${dualTime(contract.evidenceValidUntil)}</td><td>${identifier(contract.runtimeCodeHash, "runtime code hash")}</td></tr>`).join("")}
      </tbody></table></section>`;
    bindCopy();
    announce(`컨트랙트 버전 ${payload.data.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadContracts);
  }
}

async function loadPolicies() {
  skeleton("실행 정책");
  try {
    const payload = await request("/bff/admin/policies");
    if (!payload.data.length) return statePanel("empty", loadPolicies);
    app.innerHTML = `
      <header class="page-head"><div><p class="eyebrow">Versioned execution policy</p><h1>실행 정책</h1><p class="subtitle">불변 버전과 배포 hard ceiling 통과 여부를 분리해 표시합니다.</p></div><div class="timestamp">${payload.data.length}개 버전<br><strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
      <div class="readonly-callout" role="note"><strong>SERVER DECISION</strong><span>상태와 hard ceiling은 서버가 계산하며 브라우저가 허용 범위를 재구성하지 않습니다.</span></div>
      <section class="panel table-wrap"><table><thead><tr><th>Scope</th><th>버전</th><th>파생 상태</th><th>Hard ceiling</th><th>등록 시각</th><th>Policy hash</th></tr></thead><tbody>
        ${payload.data.map((policy) => `<tr><td><strong>${escapeHtml(policy.scopeId)}</strong><small>${escapeHtml(policy.versionId)}</small></td><td class="mono tabular">v${escapeHtml(policy.versionNumber)} · ${escapeHtml(policy.schemaVersion)}</td><td><span class="status ${statusTone(policy.state)}">${escapeHtml(policy.state)}</span></td><td><span class="status ${policy.ceilingPassed ? "success" : "danger"}">${policy.ceilingPassed ? "PASS" : "BLOCKED"}</span></td><td>${dualTime(policy.registeredAt)}</td><td>${identifier(policy.policyHash, "정책 hash")}</td></tr>`).join("")}
      </tbody></table></section>`;
    bindCopy();
    announce(`정책 버전 ${payload.data.length}건 조회 완료`);
  } catch (error) {
    statePanel(error.status === 403 ? "forbidden" : "error", loadPolicies);
  }
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
      <header class="page-head"><div><p class="eyebrow">Band S control ledger</p><h1>밴드S 운영 원장</h1><p class="subtitle">입력 snapshot, simulation, 이동안, 승인과 항목별 실행을 고정 hash 문맥으로 대조합니다.</p></div><div class="timestamp">최근 ${payload.data.length}건<br><strong>${dualTime(payload.meta.generatedAt)}</strong></div></header>
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
    <section class="band-items"><div class="section-head"><div><p class="eyebrow">Immutable movement plan</p><h3>이동 항목</h3></div><span>${item.items.length}건</span></div><div class="table-wrap"><table><thead><tr><th>순번 / 의존</th><th>Leg</th><th>네트워크 / 자산</th><th>출발</th><th>목적지</th><th>수량 / 원화</th><th>최신 실행 상태</th></tr></thead><tbody>${item.items.map((entry) => `<tr><td class="mono">#${escapeHtml(entry.sequence)}<small>${entry.dependsOnSequence ? `after #${escapeHtml(entry.dependsOnSequence)}` : "independent"}</small></td><td>${escapeHtml(entry.legType)}</td><td>${escapeHtml(entry.network)} / ${escapeHtml(entry.tokenSymbol)}</td><td>${identifier(entry.sourceVaultId, "출발 vault ID")}</td><td>${identifier(entry.destinationVaultId || entry.destinationAddress, "목적지 식별자")}</td><td class="mono tabular">${escapeHtml(entry.amount)}<small>${escapeHtml(entry.krwAmount)} KRW · fee ${escapeHtml(entry.expectedFeeAmount)}</small></td><td><span class="status ${statusTone(entry.executionStatus || (entry.executable ? "READY" : "BLOCKED"))}">${escapeHtml(entry.executionStatus || (entry.executable ? "READY" : "BLOCKED"))}</span>${entry.blockReason ? `<small class="danger-text">${escapeHtml(entry.blockReason)}</small>` : ""}</td></tr>`).join("")}</tbody></table></div></section>
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
      <header class="page-head"><div><p class="eyebrow">Emergency execution ledger</p><h1>비상 운영</h1><p class="subtitle">실행 차단, allowance 회수와 웹훅 수신 복구를 서버 계산 결과로 확인합니다.</p></div><div class="timestamp">관측 시각<br><strong>${dualTime(data.observedAt)}</strong></div></header>
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
    return '<section class="panel"><div class="section-head"><div><p class="eyebrow">Reinforced resume ledger</p><h2>강화 재개</h2></div><span>NO REQUEST</span></div><p class="section-empty">접수된 재개 요청이 없습니다. 중지 상태는 자동으로 해제되지 않습니다.</p></section>';
  }
  return `<section class="panel" aria-labelledby="resume-ledger-heading">
    <div class="section-head"><div><p class="eyebrow">Fresh evidence · two-person quorum</p><h2 id="resume-ledger-heading">강화 재개</h2></div><span>${resumes.length}건</span></div>
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
    return '<section class="panel"><div class="section-head"><div><p class="eyebrow">Webhook recovery ledger</p><h2>웹훅 수신 복구</h2></div><span>NO REQUEST</span></div><p class="section-empty">접수된 최근 24시간 실패 알림 복구 요청이 없습니다.</p></section>';
  }
  return `<section class="panel" aria-labelledby="webhook-recovery-heading">
    <div class="section-head"><div><p class="eyebrow">Intent before vendor call</p><h2 id="webhook-recovery-heading">웹훅 수신 복구</h2></div><span>${recoveries.length}건</span></div>
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
    return '<section class="panel"><div class="section-head"><div><p class="eyebrow">Approved approve(0) execution</p><h2>Allowance 전량 회수</h2></div><span>NO EXECUTION</span></div><p class="section-empty">승인 대상으로 고정된 allowance 회수 snapshot이 아직 없습니다.</p></section>';
  }
  return `<section class="panel" aria-labelledby="allowance-revocation-heading">
    <div class="section-head"><div><p class="eyebrow">Approved approve(0) execution</p><h2 id="allowance-revocation-heading">Allowance 전량 회수</h2></div><span>${revocations.length}건</span></div>
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
    return '<section class="panel"><div class="section-head"><div><p class="eyebrow">External control evidence</p><h2>외부 통제 관찰</h2></div><span>NO EVIDENCE</span></div><p class="section-empty">TAP 차단·컨트랙트 pause·운영자 제거를 재조회한 증적이 아직 없습니다.</p></section>';
  }
  return `<section class="panel" aria-labelledby="external-control-heading">
    <div class="section-head"><div><p class="eyebrow">External control evidence</p><h2 id="external-control-heading">외부 통제 관찰</h2></div><span>${controls.length}개 네트워크</span></div>
    <div class="table-wrap"><table><thead><tr><th>네트워크 / 상태</th><th>TAP batch</th><th>독립 RPC pause</th><th>운영자 집합 hash</th><th>관찰 / 만료</th><th>감사·issue</th></tr></thead><tbody>
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
    <div class="section-head"><div><p class="eyebrow">Network execution boundary</p><h2 id="gate-${escapeHtml(network)}">${escapeHtml(network)}</h2></div><span>${blocked ? `${blocked} BLOCKED` : "ALL READY"}</span></div>
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
      <header class="page-head"><div><p class="eyebrow">Policy approval ledger</p><h1>변경 요청</h1><p class="subtitle">요청 snapshot과 승인 판단, 활성화 금지 사유를 같은 원장에서 확인합니다.</p></div><div class="timestamp">만료 시각<br><strong>${dualTime(item.expiresAt)}</strong></div></header>
      <section class="panel identity-panel"><div><p class="eyebrow">${escapeHtml(item.targetType)} · ${escapeHtml(item.risk)}</p><h2>${escapeHtml(item.requestId)}</h2></div><span class="status ${statusTone(item.state)}">${escapeHtml(item.state)}</span>${identifier(item.snapshotHash, "요청 snapshot hash")}</section>
      <div class="readonly-callout" role="note"><strong>LOCAL · READ ONLY</strong><span>${item.disabledReasons.length ? escapeHtml(item.disabledReasons.join(" · ")) : "인증 경계 미구현으로 이 화면에서는 승인·활성화를 실행하지 않습니다."}</span></div>
      <div class="governance-grid">
        <section class="panel detail-panel"><div class="section-head"><div><p class="eyebrow">Request context</p><h2>요청 정보</h2></div></div><dl class="detail-list"><div><dt>Scope</dt><dd>${escapeHtml(item.scopeId)}</dd></div><div><dt>대상 버전</dt><dd>${identifier(item.targetVersionId, "대상 버전 ID")}</dd></div><div><dt>요청자</dt><dd class="mono">${escapeHtml(item.requesterEmployeeNo)}</dd></div><div><dt>작업 티켓</dt><dd>${escapeHtml(item.workTicket)}</dd></div><div><dt>요청 시각</dt><dd>${dualTime(item.requestedAt)}</dd></div><div><dt>사유</dt><dd>${escapeHtml(item.reason)}</dd></div></dl></section>
        <section class="panel quorum-panel"><div class="section-head"><div><p class="eyebrow">Quorum</p><h2>승인 진행</h2></div><span class="mono tabular">${escapeHtml(item.approvalCount)} / ${escapeHtml(item.requiredApprovals)}</span></div><div class="quorum-count"><strong>${escapeHtml(item.approvalCount)}</strong><span>독립 승인 완료</span></div><p>${item.securityApprovalRequired ? `보안 승인 ${escapeHtml(item.securityApprovalCount)}건 포함 필요` : "일반 독립 승인 1건 필요"}</p></section>
      </div>
      <section class="panel diff-panel"><div class="section-head"><div><p class="eyebrow">Server snapshot</p><h2>Diff · 영향</h2></div></div><div class="snapshot-grid"><div><h3>변경 diff</h3><pre>${escapeHtml(prettyJson(item.diff))}</pre></div><div><h3>영향 snapshot</h3><pre>${escapeHtml(prettyJson(item.impact))}</pre></div></div></section>
      <section class="panel related-panel"><div class="section-head"><div><p class="eyebrow">Independent decisions</p><h2>판단 원장</h2></div><span>${item.decisions.length}건</span></div>${item.decisions.length ? `<div class="table-wrap"><table><thead><tr><th>판단자</th><th>역할</th><th>판단</th><th>의견</th><th>시각</th></tr></thead><tbody>${item.decisions.map((decision) => `<tr><td class="mono">${escapeHtml(decision.employeeNo)}</td><td>${escapeHtml(decision.role)}</td><td><span class="status ${statusTone(decision.decision)}">${escapeHtml(decision.decision)}</span></td><td>${escapeHtml(decision.opinion || "—")}</td><td>${dualTime(decision.decidedAt)}</td></tr>`).join("")}</tbody></table></div>` : '<p class="section-empty">아직 기록된 판단이 없습니다.</p>'}</section>`;
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
    ["네트워크 / 심볼", `${escapeHtml(summary.network)} / ${escapeHtml(summary.symbol)}`],
    ["유형", escapeHtml(summary.transactionType || "—")],
    ["금액", `<span class="mono tabular">${escapeHtml(summary.amount || "—")}</span>`],
    ["송신 계정", escapeHtml(summary.senderAccountId || "—")],
    ["수신 대상", `${escapeHtml(summary.receiverType || "—")} · ${escapeHtml(summary.receiverValue || "—")}`],
  ];
  return `<section class="panel detail-panel" aria-labelledby="transaction-summary"><div class="section-head"><div><p class="eyebrow">Linked identity</p><h2 id="transaction-summary">거래 요약</h2></div></div><dl class="detail-list">${fields.map(([label, value]) => `<div><dt>${label}</dt><dd>${value}</dd></div>`).join("")}</dl></section>`;
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
  return `<section class="panel detail-panel" aria-labelledby="current-diagnosis"><div class="section-head"><div><p class="eyebrow">Current diagnosis</p><h2 id="current-diagnosis">현재 진단</h2></div></div><dl class="detail-list diagnostic">${fields.map(([label, value]) => `<div><dt>${label}</dt><dd>${value}</dd></div>`).join("")}</dl></section>`;
}

function timelinePanel(entries) {
  return `<section class="panel timeline-panel" aria-labelledby="transaction-timeline"><div class="section-head"><div><p class="eyebrow">Ordered evidence</p><h2 id="transaction-timeline">거래 타임라인</h2></div><span>${entries.length}건</span></div>${entries.length ? `<ol class="timeline">${entries.map((entry) => `<li><div class="timeline-rail" aria-hidden="true"></div><div class="timeline-source">${escapeHtml(entry.source)}</div><div class="timeline-body"><strong>${escapeHtml(entry.code)}</strong>${entry.status ? `<span class="status ${statusTone(entry.status)}">${escapeHtml(entry.status)}</span>` : ""}${entry.identifier ? identifier(entry.identifier, "타임라인 식별자") : ""}</div><time datetime="${escapeHtml(entry.observedAt || "")}">${dualTime(entry.observedAt)}</time></li>`).join("")}</ol>` : '<p class="section-empty">기록된 타임라인이 없습니다.</p>'}</section>`;
}

function boostPanel(boosts) {
  if (!boosts.length) return "";
  return `<section class="panel related-panel" aria-labelledby="boost-attempts"><div class="section-head"><div><p class="eyebrow">Replacement chain</p><h2 id="boost-attempts">부스트 시도</h2></div><span>${boosts.length}건</span></div><div class="table-wrap"><table><thead><tr><th>순번 / 상태</th><th>교체 전</th><th>교체 후</th><th>수수료</th><th>요청 시각</th></tr></thead><tbody>${boosts.map((boost) => `<tr><td><strong>#${escapeHtml(boost.attemptSequence)}</strong><small>${escapeHtml(boost.status)}</small></td><td>${identifier(boost.replacedTransactionId, "교체 전 거래 ID")}</td><td>${identifier(boost.newTransactionId, "교체 후 거래 ID")}</td><td>${escapeHtml(boost.feeLevel)}<small>gasless ${boost.gasless ? "yes" : "no"}</small></td><td>${dualTime(boost.requestedAt)}</td></tr>`).join("")}</tbody></table></div></section>`;
}

function sweepPanel(sweep) {
  if (!sweep) return "";
  return `<section class="panel related-panel" aria-labelledby="sweep-execution"><div class="section-head"><div><p class="eyebrow">1:N execution</p><h2 id="sweep-execution">스윕 실행</h2></div><span class="status ${statusTone(sweep.status)}">${escapeHtml(sweep.status)}</span></div><dl class="detail-list compact-details"><div><dt>실행 ID</dt><dd>${identifier(sweep.executionId, "스윕 실행 ID")}</dd></div><div><dt>외부 거래 ID</dt><dd>${identifier(sweep.externalTransactionId, "스윕 외부 거래 ID")}</dd></div><div><dt>요청 / 실제 합계</dt><dd class="mono tabular">${escapeHtml(sweep.requestedTotalAmount)} / ${escapeHtml(sweep.actualTotalAmount || "—")}</dd></div><div><dt>요청 시각</dt><dd>${dualTime(sweep.requestedAt)}</dd></div></dl><div class="table-wrap"><table><thead><tr><th>순번</th><th>계정 / 주소</th><th>요청 / 실제</th><th>상태</th></tr></thead><tbody>${sweep.items.map((item) => `<tr><td class="mono">${escapeHtml(item.sequence)}</td><td>${escapeHtml(item.accountId)}<small><code title="${escapeHtml(item.sourceAddress)}">${escapeHtml(item.sourceAddress)}</code></small></td><td class="mono tabular">${escapeHtml(item.requestedAmount)}<small>${escapeHtml(item.actualAmount || "—")}</small></td><td><span class="status ${statusTone(item.status)}">${escapeHtml(item.status)}</span>${item.failureCode ? `<small class="danger-text">${escapeHtml(item.failureCode)}</small>` : ""}</td></tr>`).join("")}</tbody></table></div></section>`;
}

function allowancePanel(allowances) {
  if (!allowances.length) return "";
  return `<section class="panel related-panel" aria-labelledby="allowances"><div class="section-head"><div><p class="eyebrow">Approval evidence</p><h2 id="allowances">Allowance 확인</h2></div><span>${allowances.length}건</span></div><div class="table-wrap"><table><thead><tr><th>계정</th><th>네트워크 / 심볼</th><th>Cap / 관측값</th><th>상태</th><th>확인 시각</th></tr></thead><tbody>${allowances.map((item) => `<tr><td>${escapeHtml(item.accountId)}</td><td>${escapeHtml(item.network)} / ${escapeHtml(item.symbol)}</td><td class="mono tabular">${escapeHtml(item.cap)}<small>${escapeHtml(item.observedAllowance)}</small></td><td><span class="status ${statusTone(item.status)}">${escapeHtml(item.status)}</span></td><td>${dualTime(item.checkedAt)}</td></tr>`).join("")}</tbody></table></div></section>`;
}

function feePanel(quotes) {
  if (!quotes.length) return "";
  return `<section class="panel related-panel" aria-labelledby="fee-quotes"><div class="section-head"><div><p class="eyebrow">Observed pricing</p><h2 id="fee-quotes">수수료 견적</h2></div><span>${quotes.length}건</span></div><div class="table-wrap"><table><thead><tr><th>맥락 / 레벨</th><th>Network fee</th><th>Gas price</th><th>Base / Priority</th><th>관측 시각</th></tr></thead><tbody>${quotes.map((quote) => `<tr><td>${escapeHtml(quote.context)}<small>${escapeHtml(quote.level)}</small></td><td class="mono tabular">${escapeHtml(quote.networkFee || quote.feePerByte || "—")}</td><td class="mono tabular">${escapeHtml(quote.gasPrice || "—")}</td><td class="mono tabular">${escapeHtml(quote.baseFee || "—")}<small>${escapeHtml(quote.priorityFee || "—")}</small></td><td>${dualTime(quote.observedAt)}</td></tr>`).join("")}</tbody></table></div></section>`;
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
    })));
}

function announce(message) { live.textContent = message; }

function navigate(href) {
  history.pushState({}, "", href);
  render();
}

function render() {
  const current = route();
  setActiveNav(current);
  ({ dashboard: loadDashboard, transaction: loadTransaction, networks: loadNetworks, assets: loadAssets, contracts: loadContracts, policies: loadPolicies, bandS: loadBandS, emergency: loadEmergency, changeRequest: loadChangeRequest, search: loadSearch })[current]();
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

window.addEventListener("popstate", render);
render();
