---
title: 룰 변경 게이트
description: 정책을 바꾸는 요청도 정책 평가를 거칩니다
---

export const base = import.meta.env.BASE_URL.replace(/\/$/, "");

{/* 뷰어는 1440×900 데스크톱 기준의 자체 완결형 HTML이라 본문 칸(약 720px)에 그대로 넣으면 잘린다.
    iframe을 1440×900으로 고정하고 칸 폭에 맞춰 축소한다 — 상호작용은 전체 화면·새 탭에서 한다. */}
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

_읽는 사람: 구현팀. 정책 변경 자체가 받는 게이트를 다룹니다._

이 페이지는 워크스페이스가 자기 룰을 바꾸는 요청이 어떤 게이트를 거치는지를 따라갑니다.
maker-checker는 제안자와 승인자를 갈라 두는 정책 변경 거버넌스입니다([자세히](/policy)).

**정책 변경은 엔진 밖의 관리 작업이 아니라 엔진이 평가하는 연산입니다.** 워크스페이스가 자기
룰을 바꾸려면 그 요청부터 게이트를 거칩니다.

이 페이지는 그 설계를 담습니다. 지금 룰 변경이 실제로 거치는 것은 번들 발행의 maker-checker이고
그 대비는 [승인 게이트](/policy/sequences/approval-gate)에 있습니다.

<Diagram name="usecase-rule-change" title="룰 변경 게이트 — 저작에서 활성까지" />

## 게이트를 만드는 것은 allow 룰 하나입니다

룰 변경 액션에 maker-checker를 적용하는 룰입니다. 저작 세트의 escalation 모듈에 이 형태로 있습니다.

```json
{
  "id": "escalation/workspace-rule-change-gate",
  "effect": "allow",
  "scope": {
    "all": [
      { "field": "operation", "op": "eq", "value": "admin_action" },
      { "field": "payload.action", "op": "eq", "value": "workspace_rule_change" }
    ]
  },
  "obligations": [
    { "type": "require_quorum", "params": { "n": { "slot": "escalation_quorum" } } }
  ]
}
```

**등재된 액션에는 그것을 명시로 제약하는 allow 룰이 최소 하나 있어야 합니다.** 없으면 컴파일이
실패합니다. 게이트 없는 액션이 조용히 허용되는 상태를 컴파일 단계가 막습니다.

## 모듈 조건을 달지 않는 것이 의도입니다

**어느 모듈을 바꾸든 강도는 같습니다.** 모듈마다 강도를 가르면 목록에 없는 모듈이 하한 없는
경우가 되고, 넓은 허가 룰 하나가 그 경우까지 허용해 버릴 수 있습니다.

강도는 기준 설계가 정하고 저작이 더하지 않습니다. 이 경로의 강제는 maker-checker 하나입니다.
2단계 인증은 [권한 상승 게이트](/policy/use-cases/escalation-pending)의 것입니다.

## allow 뒤에도 발행을 거쳐야 바뀝니다

**승인이 끝나도 서빙 중인 정책은 아직 그대로입니다.** 검증·컴파일·발행을 거쳐 활성 revision이
전환된 뒤에야 다음 평가가 새 룰을 봅니다.

저작에서 발행까지의 파이프라인은 [저작과 발행](/policy/workflow)에 있습니다. 평가가
읽는 활성 revision은 [저장과 이동](/policy/runtime/storage)에 있습니다.

## 다음으로

- [저작과 발행](/policy/workflow) — 초안·검증·발행 파이프라인
- [승인 게이트](/policy/sequences/approval-gate) — 정족수 집계와 재평가
- [권한 상승 게이트](/policy/use-cases/escalation-pending) — 더 강한 하한이 적용되는 표면
