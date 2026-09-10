---
title: 서명 상한 대조
description: 승인 스탬프는 서명 직전에 일합니다
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

_읽는 사람: 구현팀. 값 이동 승인이 서명 직전에 마지막으로 대조되는 지점을 다룹니다._

이 페이지는 승인 스탬프가 서명 직전 콜백에서 쓰이는 과정을 따라갑니다. 승인 스탬프는 승인 시점에
찍히는 소비 시점 유효값이고, 인가 TTL과 건당 자원 상한이 거기
담깁니다([자세히](/policy/runtime/normalization)).

**allow와 함께 발급된 승인 스탬프는 기록이 아니라 피연산자입니다.** 서명 직전 콜백이 그 값으로
마지막 대조를 합니다. 두 단계 구조 전체는 [서명 게이트](/policy/signing-gate)에 있습니다.

<Diagram name="usecase-callback-limit" title="서명 직전 상한 대조 — 승인 스탬프의 마지막 일" />

## 스탬프는 승인 시점에 좁혀집니다

**값 이동 승인의 자원 상한은 그 요청 체인의 항목 하나로 좁혀 새겨집니다.** 승인 시점에 좁히는
것이 상한 우회를 막는 지점입니다. 다른 체인을 보고한 콜백은 대조할 항목을 찾지 못합니다.

체인 식별자가 없는 요청은 좁힐 기준이 없어 선언 전체가 새겨집니다. 어느 쪽이든 콜백은 보고된 체인
키로 항목을 찾습니다.

## 판정 값은 서명될 트랜잭션 전체에서 나옵니다

**수수료 상한의 피연산자는 서명될 트랜잭션 전체에서 유도합니다.** 그 옆에 따로 담겨 온 신고 총액을
쓰면 상한을 우회할 수 있습니다. 정상적인 트랜잭션을 서명해 놓고 신고 값만 낮추면 됩니다.

대조는 트랜잭션 재구성이 일치한 뒤에만 이뤄집니다. 재구성 일치가 곧 보고된 환경값이 실제로 서명에
들어간다는 증명입니다. 재구성 검증 자체는 [정규화](/policy/runtime/normalization)에 있습니다.

## 상한이 새겨지지 않은 것은 상한 없음이 아닙니다

**보고된 체인의 상한 항목을 찾지 못하면 결과는 거절입니다.** 상한 부재를 "상한 없음"으로
읽는 경로를 두지 않습니다. 부재는 통과 사유가 될 수 없습니다.

## 거절도 200으로 나갑니다

**판정은 HTTP 상태가 아니라 서명된 응답이 전달합니다.** 서명 인프라에게 4xx와 5xx는 "응답을 못
받았다"라서 재시도를 부르고, Wallet SDK가 의도한 거절이라는 사실이 사라집니다.

통과하면 인가를 소비합니다. 인가는 승인된 판단 요청이 발급하는 한 번만 쓸 수 있는 서명 근거라,
같은 승인으로 두 번 서명할 수 없습니다. 인가 유효 시간과 재확인 구조는
[서명 게이트](/policy/signing-gate)에 있습니다.

## 다음으로

- [서명 게이트](/policy/signing-gate) — 두 단계 구조와 인가 유효 시간
- [출금 화이트리스트](/policy/use-cases/whitelist-loop) — 이 스탬프를 발급한 흐름
