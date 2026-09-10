---
title: 금지선
description: deny는 어떤 허가로도 뒤집히지 않습니다
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

_읽는 사람: 구현팀. 금지 룰이 허가를 뒤집는 결합을 다룹니다._

이 페이지는 허가 룰과 금지 룰이 함께 참일 때 결과가 어떻게 정해지는지를 따라갑니다. 룰은
`scope`와 조건을 갖고 `allow`·`deny`를 내는 단위입니다([자세히](/policy/rule/structure)).

**허가 룰이 참이어도 금지 룰이 참이면 결과는 deny입니다.** 예약 어드민 계정에 권한을 부여하려는
요청이 이 결합을 정확히 보여줍니다.

이 페이지는 그 설계를 담습니다. 어드민 액션을 판단 요청으로 접수하는 표면은 아직
없습니다([승인 게이트](/policy/sequences/approval-gate)).

<Diagram name="usecase-deny-override" title="금지 룰 — deny 는 허가를 뒤집는다" />

## 금지 룰은 대상만 봅니다

예약 어드민 계정을 권한 부여 대상으로 삼는 것을 금지하는 룰의 형태 예시입니다.

```json
{
  "id": "escalation/deny-reserved-admin-target",
  "effect": "deny",
  "scope": {
    "all": [
      { "field": "operation", "op": "eq", "value": "admin_action" },
      { "field": "payload.action", "op": "eq", "value": "role_grant" }
    ]
  },
  "when": {
    "field": "payload.target_admin_id",
    "op": "in",
    "value": ["<예약 계정 식별자 목록>"]
  }
}
```

**deny 룰에는 obligation이 없습니다.** obligation은 통과로 확정되기 전에 이행해야 하는 조건이라,
거부하면서 이행을 요구하는 것은 뜻이 성립하지 않습니다. 그 계약은
[룰 구조](/policy/rule/structure)에 있습니다.

## deny 면제 메커니즘은 없습니다

**어느 모듈에서든 금지 룰이 참이면 결과는 deny입니다.** 허가 룰이 몇 개 발동했는지는 결과를
바꾸지 못합니다. 결합 규칙은 [결정과 결합](/policy/rule/decision)에 있습니다.

deny 판정은 수집 요구보다 앞입니다. 소스 없이도 이미 참인 금지 룰이 있으면 수집 없이 즉시
종결하고, 거부가 확정된 요청에 수집 비용을 쓰지 않습니다.

## 금지 값이 리터럴인 것이 통제입니다

슬롯은 카탈로그에 선언되고 워크스페이스가 조정할 수 있는
값입니다([자세히](/policy/rule/structure)).

**금지 대상 목록을 슬롯으로 두면 피통제 조직이 목록을 비워 무력화할 수 있습니다.** 금지 규칙을
금지 대상이 직접 정하게 되는 셈입니다.

리터럴로 두는 대가는 목록 변경이 룰 변경 거버넌스를 거치는 것입니다. 그 비용이 이 형태의
요점이고, 금지 대상 목록을 바꾸는 일은 조용히 일어나면 안 됩니다.

## 금지 룰은 채워짐이 보장된 필드만 참조합니다

**값이 없는 필드의 비교는 거짓이 됩니다.** 허가 룰에서는 통과가 막히는 안전한 방향이지만,
금지 룰에서는 금지가 적용되지 않는 방향입니다.

그래서 이 룰은 액션 계약이 필수로 요구하는 필드만 봅니다. 필드 계약은
[룰 명세](/policy/rule/reference)에 있습니다.

## 다음으로

- [결정과 결합](/policy/rule/decision) — deny 우선 결합과 결정값 네 가지
- [권한 상승 게이트](/policy/use-cases/escalation-pending) — 같은 표면의 하한 obligation
