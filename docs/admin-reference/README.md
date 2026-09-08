# Admin 승인 기준 화면

Admin 화면을 구현하는 개발자를 위한 **2026-08-17 사용자 승인 기준안**이다.
화면·토큰을 참고할 때 사용하며, 기능·권한·승인 계약의 정본은 [Admin 설계](../design/08-bcm-admin.md)다.

| 자료 | 용도 |
|---|---|
| [기준 화면](index.html) | Dashboard·Transaction Detail·Policy Approval·Band S Simulation, 비상 흐름·권한·stale 상태 |
| [디자인 토큰](tokens.css) | 승인 기준 화면의 색·간격·상태 표현 — 적용 계약은 [설계의 디자인 토큰](../design/08-bcm-admin.md#디자인-토큰) |

구현할 때는 [화면별 기능](../design/08-bcm-admin.md#화면별-기능)과 [공통 UI·UX 규칙](../design/08-bcm-admin.md#공통-uiux-규칙)을 함께 확인한다.
승인 기준 화면 이후의 확정 사항은 설계가 우선한다.

정책을 찾는 경우: [신뢰 경계](../design/08-bcm-admin.md#호출-구조와-신뢰-경계),
[역할·정족수](../design/08-bcm-admin.md#위험-등급과-정족수),
[컨트랙트·증적](../design/08-bcm-admin.md#sweep-컨트랙트),
[밴드S](../design/06-sweep.md#②-밴드s--어떻게-처리하나).
