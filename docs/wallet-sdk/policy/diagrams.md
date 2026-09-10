---
title: 다이어그램
description: 정책 엔진의 배포 토폴로지·서명 게이트·상태 머신·발행 워크플로우와, rego·JSON 소스 구성, 코드 레벨 호출 경로를 상호작용 뷰어로 봅니다
---

export const base = import.meta.env.BASE_URL.replace(/\/$/, "");

{/* 뷰어는 1440×900 데스크톱 기준으로 만든 자체 완결형 HTML 이라 본문 칸(약 720px)에 그대로 넣으면 잘린다.
    그래서 iframe 을 1440×900 으로 고정하고 칸 폭에 맞춰 축소해 전체를 보이게 한다. 상호작용은
    전체 화면(Fullscreen API — 화면에 맞춰 다시 키운다, Esc 로 복귀)이나 새 탭에서 한다. */}
<script is:inline data-astro-rerun>{`
(function () {
  var W = 1440, H = 900;
  function fit(w) {
    var f = w.querySelector("iframe");
    var fs = document.fullscreenElement === w;
    var s = fs ? Math.min(innerWidth / W, innerHeight / H) : w.clientWidth / W;
    f.style.transform = "scale(" + s + ")";
    w.style.height = fs ? "100%" : Math.round(H * s) + "px";
    f.style.left = fs ? Math.round((innerWidth - W * s) / 2) + "px" : "0";
    f.style.top = fs ? Math.round((innerHeight - H * s) / 2) + "px" : "0";
  }
  function fitAll() { document.querySelectorAll(".dg-wrap").forEach(fit); }
  window.__dgFit = fit;
  window.__dgFull = function (btn) {
    var w = btn.closest("figure").querySelector(".dg-wrap");
    (w.requestFullscreen || w.webkitRequestFullscreen).call(w);
  };
  addEventListener("resize", fitAll);
  addEventListener("fullscreenchange", fitAll);
  if (document.readyState !== "loading") fitAll(); else addEventListener("DOMContentLoaded", fitAll);
})();
`}</script>

export function Diagram({ name, title }) {
  const src = `${base}/diagrams/${name}.html`;
  return (
    <figure class="dg" style="display:grid;gap:0;margin:.75rem 0 2.5rem 0">
      <div class="dg-wrap" style="margin:0;position:relative;overflow:hidden;width:100%;height:450px;border:1px solid var(--color-border,#e5e7eb);border-radius:8px 8px 0 0;background:#fff">
        <iframe
          src={src}
          title={title}
          loading="lazy"
          onload="window.__dgFit && window.__dgFit(this.parentElement)"
          style="position:absolute;left:0;top:0;width:1440px;height:900px;border:0;transform-origin:0 0"
        ></iframe>
      </div>
      <figcaption style="margin:0;display:flex;gap:.6rem;justify-content:flex-end;align-items:center;padding:.55rem .75rem;border:1px solid var(--color-border,#e5e7eb);border-top:0;border-radius:0 0 8px 8px;font-size:.95rem">
        <span style="margin-right:auto;opacity:.6;font-size:.85rem">축소 미리보기 — 상호작용은 전체 화면에서</span>
        <button type="button" onclick="window.__dgFull(this)" style="display:inline-flex;align-items:center;gap:.4rem;cursor:pointer;padding:.45rem .9rem;border:1px solid var(--color-border,#e5e7eb);border-radius:8px;background:transparent;font:inherit;font-size:.95rem;font-weight:600;color:inherit;line-height:1">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/></svg>
          전체 화면
        </button>
        <a href={src} target="_blank" rel="noopener" style="display:inline-flex;align-items:center;gap:.4rem;padding:.45rem .9rem;border:1px solid var(--color-border,#e5e7eb);border-radius:8px;text-decoration:none;font-size:.95rem;font-weight:600;color:inherit;line-height:1">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 3h6v6"/><path d="M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/></svg>
          새 탭에서 열기
        </a>
      </figcaption>
    </figure>
  );
}

_읽는 사람: 구현팀. 정책 엔진의 다이어그램을 모아 둔 곳이고, 각 장이 무엇을 담는지만 씁니다._

이 페이지는 정책 엔진의 다이어그램 열 장을 모아 둡니다. 설계 다섯 장, 소스 구성 두 장, 코드 주파
세 장입니다. 열 장 전부 자체 완결형 뷰어라 안에서 확대·검색·관계 추적·안내 보기(`Play story`)가
됩니다.

명세와 재생성 절차는 저장소의 `docs/site/diagrams/README.md`에 있습니다.

이 페이지 밖에도 열세 장이 있습니다. 저작 세트와 컴파일을 그린 두 장은 정책 엔진 내부 문서에
있습니다. 저작·평가 서사 네 장(draft 일생·번들 해부·평가 입력·산출물이 가는 곳)은
[룰 저작과 발행](/policy/workflow)·[평가의 순간](/policy/runtime/evaluation)·[저장과
이동](/policy/runtime/storage)과 내부 문서에 나뉘어 있습니다. 전체 지도 두 장(저작 산출물이 가는
경로·평가 입력 문서)은 [전체 지도](/policy/map)에 있습니다. 유즈케이스 다섯 장은
[사용 사례](/policy/use-cases/whitelist-loop) 다섯 편에 각각 있습니다.

설계 다섯 장은 설계 문서(`design-draft` §2·§5.2, `sequences-draft` §1)와 `PolicyRequestStatus.kt`에서,
소스 구성 두 장은 `backend/policy/policy-source/`·`rule-compiler`·`kernel-runner`의 실제 파일(2026-08-31)에서,
코드 주파 세 장은 2026-08-30 codegraph로 hop마다 확정한 호출 체인에서 그렸습니다. 코드 주파 장의
파일:라인은 그 시점 트리 기준이라 코드가 바뀌면 어긋납니다.

## 설계 — 무엇이 어디에 있고 어떤 순서로 처리되는가

### 배포 토폴로지

policy zone 안의 `policy-server`·OPA sidecar·논리 DB 3분할·이벤트 스트림과, 인바운드 신뢰 근거 넷입니다.

<Diagram name="policy-topology" title="Policy Server 배포 토폴로지" />

### 서명 게이트 Phase 1 — 접수·평가·인가 발급

<Diagram name="signing-gate-phase1" title="서명 게이트 Phase 1" />

### 서명 게이트 Phase 2 — Co-Signer 콜백

<Diagram name="signing-gate-phase2" title="서명 게이트 Phase 2" />

### 판단 요청 상태 머신

`PolicyRequestStatus`의 `ALLOWED_TRANSITIONS`와 대조한 전이입니다. `APPROVED → DENIED`(콜백 종결 전용)와
`RECEIVED`·`NORMALIZING → FAILED`는 선 대신 카드에 적었습니다.

<Diagram name="request-status" title="판단 요청 상태 머신" />

### 번들 저작·발행 워크플로우

<Diagram name="bundle-publication" title="번들 저작·발행 워크플로우" />

## 소스 구성 — rego와 JSON이 번들이 되기까지

두 장이 한 쌍입니다. 첫 장은 rego 네 부류와 JSON 저작 데이터가 컴파일·채점을 거쳐 서빙·채점
OPA에 닿는 전체 계층을 잡습니다. 둘째 장은 그 왼쪽 구획(저작 세트·채점 입력·골든 스위트)을
파일 수준으로 폅니다.

### rego와 JSON의 계층

룰은 코드가 아니라 데이터입니다. 평가 로직은 커널 `decision.rego` 하나이고, authz rego 두 벌이
서빙·채점 OPA의 관리 표면을 막습니다. 커널은 조건 트리를 평가하고 결정을 결합하는 정책 무관 고정
부분입니다([결정과 결합](/policy/rule/decision)). 저작·빌드 구획의 파일 구성은 바로 아래 상세
장이 폅니다.

<Diagram name="rego-json-layers" title="정책 엔진 — rego와 JSON의 계층" />

### policy-source 상세 — 저작·채점·골든

저작 세트는 파일 위치 열거가 곧 계약입니다. 채점 입력은 저작한 룰의 기대 판정을 재고, 골든 스위트는
커널 자체를 잽니다. 컴파일 산출물이 그다음 어디로 가는지는 위의 계층 장이 잡습니다.

<Diagram name="policy-source-detail" title="policy-source 상세 — 저작·채점·골든" />

## 코드 주파 — 한 요청이 어느 클래스를 어떤 순서로 거치는가

### 판단 요청

접수는 동기 202이고 구동은 `@Scheduled` 1초 폴링이며 OPA 질의는 트랜잭션 밖입니다. 종결은
`DecisionRecordingService`가 전이·결정로그·outbox를 한 트랜잭션으로 묶습니다.

<Diagram name="code-flow-request" title="판단 요청 코드 주파 경로" />

### Co-Signer 콜백

JWT 검증 → 대조 세 단계 → 조건부 단일행 CAS(`consumeSigningAuthorization` / `terminateSigningAuthorization`)까지
한 트랜잭션이고, HTTP는 언제나 200입니다.

<Diagram name="code-flow-callback" title="콜백 코드 주파 경로" />

### 번들·PIP 클래스 배선도

번들 서빙과 PIP 수집이 만나는 세 지점 — `BundleBackedPipSourceRegistry`의 `activationSeq` 캐시, `WalletSourceClient`
공유, 파트너 결과 도착이 요청 스레드 안에서 커널을 다시 부르는 경로입니다.

<Diagram name="code-flow-bundle-pip" title="번들·PIP 클래스 배선도" />
