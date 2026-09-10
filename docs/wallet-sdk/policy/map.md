---
title: 전체 지도
description: 저작 산출물이 가는 세 경로와 평가 입력의 조립, 두 장으로 요약한 정책 엔진
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

_읽는 사람: 파트너사 개발자와 구현팀. 조각들이 어디로 가는지만 담고, 각 조각의 정의와 규칙은 링크된 페이지에 있습니다._

정책 엔진은 자금을 움직이는 요청이 서명 인프라로 가기 전에 그 요청을 심사하는 별도 시스템입니다.
이 페이지는 그 엔진의 조각들을 한눈에 놓는 지도입니다. 저작 산출물이 어디로 가는지와 평가 입력이
어떻게 조립되는지, 두 장으로 요약합니다.

## 저작 산출물이 가는 곳은 셋으로 갈립니다

저작과 검증에 등장하는 파일이 많아 보여도, 각 산출물이 가는 곳은 셋 중 하나입니다.

- **번들에 들어갑니다**: 룰·슬롯·카탈로그·레지스트리·퇴역 대장. 컴파일을 거쳐 스냅샷 구획이
  되고, 발행·활성화로 평가기의 data 에 적재됩니다.
- **빌드에서만 쓰입니다**: 테스트 벡터·결합 스위트·골든 스위트. 발행될 번들 바이트를 재고,
  번들에는 들어가지 않습니다.
- **런타임에 조립됩니다**: 워크스페이스 번들·수집 스냅샷·테넌트 식별 값. 저작물이 아니라
  요청마다 입력 문서로 조립됩니다.

<Diagram name="source-fates" title="저작 산출물이 가는 세 경로 — 번들로 실리는 것과 빌드에서 끝나는 것" />

## 조각마다 소유 페이지가 있습니다

역할 한 줄과 기준 페이지 위치입니다. 정의·규칙·실제 모습은 링크된 페이지에 있습니다.

| 산출물 | 가는 곳 | 역할 한 줄 | 기준 페이지 |
|---|---|---|---|
| 룰 모듈 | 번들 | 관심사 하나의 룰 목록 | 설계 기록(내부) · [룰 구조](/policy/rule/structure) |
| 슬롯 카탈로그 | 번들 | 워크스페이스가 조정할 값의 선언과 하한 | 설계 기록(내부) |
| 액션 카탈로그 | 번들 | 액션별 payload 경로와 타입 | 설계 기록(내부) |
| 레지스트리 둘 | 번들 | PIP 소스·서명 어댑터 — 바깥 표면의 선언 | 설계 기록(내부) |
| 퇴역 대장 | 번들 | 지운 룰 ID — 재사용 금지 | 설계 기록(내부) |
| 테스트 벡터 | 빌드 전용 | 룰 하나의 기대 판정 | 설계 기록(내부) |
| 결합 스위트 | 빌드 전용 | 모듈 결합과 obligation 병합 | 설계 기록(내부) |
| 골든 스위트 | 빌드 전용 | 커널 자체의 변경 검사 | 설계 기록(내부) |
| 규모별 프리셋 | 조건부 | 온보딩 시드 — 채택되면 그 기관의 저작 세트가 됩니다 | 이 페이지 (아래) |
| 워크스페이스 번들 · 수집 스냅샷 | 런타임 조립 | 요청마다 입력 문서로 | [평가의 순간](/policy/runtime/evaluation) |

**규모별 프리셋은 예시 문서가 아닙니다.** 관리 화면의 가이드 빌더가 생기기 전까지 플랫폼이 기관
대신 저작하는 시드 세트입니다. 표의 저작 세트는 룰 모듈과 카탈로그·레지스트리 파일이 놓이는
묶음입니다.

프리셋은 플랫폼 시드 위에 겹쳐 같은 컴파일 검사와 채점을 거칩니다. 채점은 컴파일된 룰을
케이스로 돌려 기대 결과와 맞는지 확인하는 검사입니다.

겹침의 해석은 파일 단위이고, 같은 파일이 양쪽에 있으면 프리셋을 우선합니다.

## 평가 입력은 조립 결과입니다

입력 문서는 Orchestrator가 요청·환경·PIP 결과를 모아 평가기에 넘기는 완성된 평가 입력입니다.
세 요소가 요청마다 하나로 조립됩니다 — 요청의 사실, 수집한 세상의 사실, 엔진이 확정한 값입니다.

조립 규칙과 슬롯 해석은 [평가의 순간](/policy/runtime/evaluation)에 있습니다.

<Diagram name="input-document" title="평가 입력 문서 — 세 재료와 엔진 구획" />

## 다음으로

각 역할이 어디에 배포되는지는 [구조](/policy/architecture)에 있습니다. 판단과 집행이 나뉘는 두
단계는 [서명 게이트](/policy/signing-gate)에, 상호작용 뷰어 모음은
[다이어그램](/policy/diagrams)에 있습니다.

파일 위치를 하나씩 열거한 목록은 정책 엔진 내부 문서에 있습니다. 여기서 알 것은 그 파일이
각각 위의 세 경로 중 하나로 간다는 점입니다.
