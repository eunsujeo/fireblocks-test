---
search:
  tags:
    - Callback
    - POST
seo:
  description: >-
    PEP가 종단한다 — JWT 양방향 검증·서명 대상… Reference for the POST
    /callback/fireblocks-cosigner endpoint in the Wallet Policy Engine Internal
    API API.
sidebar:
  label: Co-Signer pre-signing 콜백
  badge: POST
title: Co-Signer pre-signing 콜백
type: openapi-operation
---
PEP가 종단한다 — JWT 양방향 검증·서명 대상 추출·**산출물에서 signingAuthorizationId
추출**(owning service가 제출 시 externalTxId로 지정 — D-66. 조회 힌트이고 방어는 전체
canonical 대조 + CAS)·응답 서명. 승인 저장소 대조와 원자적 단회성 소비는
Orchestrator가 소유한다.

콜백이 거부하면 Co-Signer는 서명하지 않는다 — 이것이 최종 방어선이다.

미디어 타입은 요청·응답 모두 `application/jose`(JWT compact 직렬화, RFC 7515 §9.2.1)다
— provider 실물 Content-Type과의 대조는 실물 전환 게이트 항목이다(A8 확정 ⑥).

<Operation source="reference-policy" id="policy-callback-handle" />
