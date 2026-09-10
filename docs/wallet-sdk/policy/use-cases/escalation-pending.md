---
title: 권한 상승 게이트
description: 하한 룰은 조건 없이 적용됩니다
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

_읽는 사람: 구현팀. 권한 상승 요청이 받는 하한 게이트를 다룹니다._

이 페이지는 권한을 올리는 요청이 pending으로 멈추는 지점을 따라갑니다. obligation은 통과로
확정되기 전에 이행해야 하는 조건이고([자세히](/policy/rule/structure)), 이 요청에서는 2단계
인증과, 승인에 필요한 최소 인원인 정족수의 승인이 그것입니다.

**권한을 올리는 요청은 어떤 설정에서도 가장 강한 게이트를 받습니다.** 그 하한을 만드는 것이
조건 없는 룰 하나입니다.

이 페이지는 그 설계를 담습니다. 어드민 액션을 판단 요청으로 접수하는 표면은 아직
없습니다([승인 게이트](/policy/sequences/approval-gate)).

<Diagram name="usecase-escalation-pending" title="권한 상승 게이트 — obligation 이 만드는 pending" />

## 하한 룰에는 조건이 없습니다

권한 부여에 하한을 거는 룰의 형태 예시입니다.

```json
{
  "id": "escalation/role-grant-gate",
  "effect": "allow",
  "scope": {
    "all": [
      { "field": "operation", "op": "eq", "value": "admin_action" },
      { "field": "payload.action", "op": "eq", "value": "role_grant" }
    ]
  },
  "obligations": [
    { "type": "require_2fa" },
    { "type": "require_quorum", "params": { "n": { "slot": "escalation_quorum" } } }
  ]
}
```

**`when`이 없는 것이 이 룰의 요점입니다.** 조건을 달면 조건이 거짓인 경우에 하한이 사라지고,
그 경우는 다른 룰이 허가하는 순간 하한 없이 통과합니다.

액션 종류만으로 대상이 식별되므로 필드 값 조건이 필요하지도 않습니다. 적용 범위 두 개가
발동을 전부 정합니다.

## 하한과 허가는 독립입니다

**무엇을 줄 수 있는지는 허가 룰이 답하고, 얼마나 강한 게이트를 거치는지는 이 룰이 답합니다.** 부여
가능 목록이 어떻게 바뀌어도 이 obligation은 그대로 남습니다.

본인 승인 불허는 생략으로 표현합니다. 허용 여부의 병합은 하나라도 불허면 불허라서, 생략이 곧
가장 안전한 값입니다. 병합 규칙은 [결정과 결합](/policy/rule/decision)에 있습니다.

## 정족수는 리터럴이 아니라 슬롯입니다

슬롯은 카탈로그에 선언되고 워크스페이스가 조정할 수 있는 값입니다.

**값을 고르는 것은 워크스페이스이지만 하한 아래로는 내려가지 못합니다.** 조건 트리를
평가하고 결정을 결합하는 정책 무관 고정 부분인 커널([자세히](/policy/rule/decision))이 평가 시점에
하한을 다시 확인합니다. `escalation_quorum`의 기본값은 3이고 하한은 2입니다. 슬롯 계약은
[룰 구조](/policy/rule/structure)에 있습니다.

정족수를 리터럴로 고정하면 워크스페이스가 자기 규모에 맞게 올릴 수도 없습니다. 조정 가능성과
하한 보증을 슬롯 하나가 함께 만듭니다.

## pending 뒤의 흐름은 승인 게이트가 맡습니다

**이 룰이 만드는 것은 pending까지입니다.** 표 수집·정족수 집계·재평가·집행은
[승인 게이트](/policy/sequences/approval-gate)에 있고, 2단계 인증 채널은
[obligation 수집](/policy/sequences/obligations)에 있습니다.

## 다음으로

- [승인 게이트](/policy/sequences/approval-gate) — pending 이후의 표 수집과 집행
- [금지선](/policy/use-cases/deny-override) — 같은 표면에서 deny 룰이 허가를 뒤집는 흐름
- [룰 변경 게이트](/policy/use-cases/rule-change) — 정책 변경 자체가 받는 게이트
