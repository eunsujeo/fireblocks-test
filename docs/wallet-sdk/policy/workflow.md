---
title: 룰 저작과 발행
description: draft 하나가 편집·검증·승인을 거쳐 활성 번들이 되기까지
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

_읽는 사람: 구현팀. draft 하나가 활성 번들이 될 때까지 사람이 거치는 길을 다룹니다._

저작과 발행은 draft를 열고, 검증을 받고, 승인을 모아 발행과 활성화까지 가는 과정입니다. 이
페이지는 그 과정에서 사람이 거치는 길을 씁니다.

룰은 저작 세트에서 시작해 번들이 되어 서빙됩니다. 저작 세트는 룰 모듈과 카탈로그·레지스트리
파일이 놓이는 묶음입니다.

컴파일이 그것을 평가기가 읽는 선언 데이터로 바꾸고, 채점이 컴파일된 룰을 테스트 벡터로 돌려
기대 결과와 맞는지 확인합니다.

둘을 통과한 내용이 정책 로직 번들로 묶여 발행되고, 활성 포인터가 그중 한 revision을 지목하면
그것이 서빙됩니다. 번들 바이트와 발행 이력은 관계형 저장소에 남습니다.

<Diagram name="rule-draft-lifecycle" title="룰 draft의 일생 — 저작에서 활성까지" />

## 저작은 기준선에서 시작합니다

콘솔의 편집기는 빈 화면이 아니라 **활성 번들에서 유도한 기준선**으로 시작합니다. 지금 서빙되는
내용을 받아 고치는 구조라, 저작이 현재 상태와 동떨어지지 않습니다.

활성 번들이 없을 때 무엇을 받는지는 두 경우로 갈립니다. 워크스페이스 저작은 앱에 미리 넣어 둔
시드 저작 세트를 받습니다. 그 시드도 없으면 기준선이 비고, 플랫폼 저작은 활성 번들이 없으면 그대로
빕니다.

빈 기준선은 오류가 아니라 첫 저작의 정상 출발점입니다.

## 편집은 지우지 않고 누적합니다

draft 하나는 편집의 머리(head)이고, **편집은 언제나 새 revision을 추가하는 일입니다.** 지난
revision은 고쳐지지 않고 그대로 남아, 언제든 주소로 되찾을 수 있습니다.

폐기도 삭제가 아니라 상태 전이입니다. 폐기된 draft의 revision 체인은 기록으로 남습니다.

조회 범위는 요청이 아니라 인증에서 옵니다. [인증과 스코프](/start/authentication)의 규율
그대로입니다.

## 검증은 revision 하나에 묶입니다

검증 요청은 draft가 아니라 **특정 revision을 지목합니다.** 컴파일 검사 전부와 케이스 채점이 한
요청에서 함께 실행됩니다.

결과는 그 revision에 1:1로 남습니다. 통과 여부, 위반 목록, 실행된 케이스 수, 그리고 통과 시의
내용 해시입니다.

그래서 "검증 통과"라는 말에는 항상 대상이 있습니다. revision을 더 쌓으면 새 revision은 다시
검증받아야 합니다.

콘솔 검증과 발행 빌드는 같은 컴파일러를 거칩니다. 그래서 검증 화면에서 통과한 내용이 발행
시점에 다른 판정을 받지 않습니다.

### 경고는 판정을 바꾸지 않습니다

결과에는 위반 말고 경고가 함께 들어갑니다. **경고는 통과 여부를 뒤집지 않습니다.** 발행 검사에서
막힐 것을 검증 화면이 미리 보여 주기 때문입니다. 그래서 오타 하나가 정족수 승인 한 벌을 헛되게
하지 않습니다. 정족수는 승인이 성립하는 데 필요한 최소 승인자 수입니다.

경고 종류는 셋입니다.

- **슬롯 카탈로그 드리프트**: 세트가 담은 카탈로그 사본이 기준 카탈로그와 갈렸습니다
- **기준 카탈로그 미확정**: 대조할 기준을 찾지 못해 세트가 담은 사본으로 검증했습니다. 발행은
  기준을 확정하지 못하면 거부합니다
- **테넌트 부재**: 슬롯 값 구획이 적은 테넌트를 그 워크스페이스에서 찾지 못했습니다

## 승인은 통과한 revision만 받습니다

승인을 여는 것(maker)과 표를 던지는 것(checker)이 갈립니다. maker-checker는 제안자와 승인자를
갈라 두는 정책 변경 거버넌스입니다([자세히](/policy)).

**승인은 언제나 검증 통과 revision의 내용 해시에 붙습니다.** 통과하지 않은 revision은 승인의
대상이 될 수 없고, 승인이 붙은 뒤 내용이 바뀌면 그 해시가 달라집니다.

표 접수와 자격 재검증, 재평가로 이어지는 maker-checker 흐름은
[승인 게이트](/policy/sequences/approval-gate)에 있습니다. 이 페이지가 더하는 것은 승인이 내용
해시에 묶인다는 사실 하나입니다.

## 발행은 다시 컴파일해 대조합니다

발행은 승인된 revision의 콘텐츠를 다시 컴파일해 내용 해시를 새로 계산하고, 승인이 묶인 해시와
대조합니다. 같아야 번들이 만들어집니다. 승인과 발행 사이에 내용이 바뀌는 길이 이 대조에서
막힙니다.

발행 시점에 채점을 되풀이하지는 않습니다. 채점은 검증 시점에 끝났고, 해시가 같다는 것은 채점받은
바이트와 발행되는 바이트가 같다는 뜻이기 때문입니다.

**발행에는 컴파일 밖의 검사가 하나 더 있습니다.** 슬롯 값 구획이 테넌트를 지목하면, 그 테넌트가
그 워크스페이스에 실재하는지 지갑 쪽에 물어봅니다. 판정에 외부 조회가 필요해 순수 컴파일 검사에
둘 수 없고, 그래서 발행 서비스 안에 있습니다.

거절 사유는 셋으로 갈립니다. 없다는 사실 확인, 물어보지 못한 상태, 그리고 배선 결함입니다. 물을
테넌트 키가 없으면 조회 자체를 하지 않습니다.

## 활성화는 별도 액션입니다

발행은 번들 revision을 만드는 일입니다. 서빙되는 것은 지금 무엇이 서빙되는지를 정하는 단일
기록인 활성 포인터가 가리키는 revision입니다. 발행이 곧 활성화가 되지 않게 두 단계를 갈라
두었습니다.

포인터 전환은 경합에 안전한 조건부 갱신이고, 전환 이력은 추가 전용으로 남습니다. **되돌림도
재발행이 아니라 포인터 재전환입니다.** 이전 revision은 저장소에 그대로 있습니다.

## 콘솔은 두 배포로 갈립니다

이 과정을 사람이 거치는 화면이 정책 콘솔입니다. 파트너사 워크스페이스가 쓰는 콘솔과, 플랫폼
통제 구역이 쓰는 콘솔로 갈립니다.

두 배포가 부를 수 있는 표면이 다르므로 화면 구성도 다릅니다. 표면은 계약 소유자와 인증 방식이
하나로 정해지는 API 경로 묶음입니다([자세히](/start/authentication)).

양쪽에 함께 있는 화면입니다.

- **로그인**(`/login`)과 **자격 등록**(`/enroll`): 세션을 세우는 화면입니다. 자격 등록은 아직
  세션이 없는 사람이 1회용 등록 토큰으로 부릅니다
- **draft 목록**(`/drafts`)과 **draft 상세**(`/drafts/:draftId`): revision 체인과 편집기가 여기
  있습니다
- **승인 목록**(`/approvals`)과 **승인 상세**(`/approvals/:approvalId`): 표를 던지는 화면이고,
  상세가 활성 번들을 기준선 삼아 내용 차이를 보여 줍니다
- **어드민 목록**(`/admins`): 지명과 회수 화면입니다

파트너사 콘솔에만 판단 요청 생성(`/requests/new`)과 판단 요청 상세(`/requests/:policyRequestId`)가
있습니다. 접수 자격이 어드민 세션과 다른 기준이라 통제 구역 콘솔에는 이 화면을 두지 않습니다.

통제 구역 콘솔에만 셋이 있습니다. 부트스트랩(`/bootstrap`)은 최초 어드민을 만들고, 활성
번들(`/bundles`)은 지금 서빙되는 번들을 보여 주며, 워크스페이스 등록(`/workspaces/new`)은 새
워크스페이스와 그 매니저를 만듭니다.

## 다음으로

- [승인 게이트](/policy/sequences/approval-gate) — 표 접수와 자격 재검증이 거치는 흐름
- [룰 구조](/policy/rule/structure) — 여기서 저작하는 룰이 무엇으로 이루어지나
- [구조](/policy/architecture) — 발행된 번들이 어디에 배포되고 어떻게 서빙되나
