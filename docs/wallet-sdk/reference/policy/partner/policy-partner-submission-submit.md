---
search:
  tags:
    - Partner
    - POST
seo:
  description: >-
    PIP 결과와 obligation 이행 증거를 같은… Reference for the POST
    /pip/{policyDataRequestId}/result endpoint in the Wallet Policy Engine
    Internal API API.
sidebar:
  label: PIP 결과·이행 증거 제출
  badge: POST
title: PIP 결과·이행 증거 제출
type: openapi-operation
---
PIP 결과와 obligation 이행 증거를 같은 표면·인증·도착 규칙으로 제출한다.

인증은 전송 계층이 아니라 본문이다 — JWS 서명키 → 등록 partnerId(파트너 PIP 계약 §4).
그래서 이 표면은 NoAuth이고, bearer류 크리덴셜을 요구하지 않는다.

policyDataRequestId는 single-use 상관 키다 — Orchestrator가 미결 수집을 조회해
canonical intent 바인딩을 검증하고 소비한 뒤 신선도를 재검사하고 EVALUATING에 재진입한다.

attach_travel_rule_payload는 여기 제출 없이 travel_rule 소스 재수집으로 이행을 확인한다.

## 요청 형태
바디는 JWS compact 직렬화 하나이고 미디어 타입은 `application/jose`로 고정한다(콜백
선례). 서명 페이로드는 파트너 PIP 계약 §5의 인바운드 스키마
`{policyDataRequestId, source, snapshot{...}, evaluatedAt}`이고, **payload의
policyDataRequestId는 경로 변수와 같아야 한다** — 서명이 상관 키를 덮지 않으면 한
수집에 대한 유효 제출을 다른 수집의 경로로 재생할 수 있다.

## 실패 응답은 균일하다
서명 검증 이전·이후를 가리지 않고 인증 실패는 전부 **401 + 같은 바디**다
(`UNAUTHENTICATED`). 사유(미등록 kid·서명 불일치·alg 변조)를 갈라 답하면 등록 키의
형태를 탐색할 수 있다. 상관 키의 **존재 여부도 응답을 가르지 않는다** — 존재 선조회를
서명 검증 앞에 두지 않으므로 미지 id와 유효 id가 같은 경로·같은 비용을 지난다.
인증을 지난 뒤의 도착 판정(§4.3의 여덟 갈래)은 전부 200 `REJECTED` 한 값으로 접힌다.

Errors:
- 401 UNAUTHENTICATED: JWS 인증 실패. 미등록 kid·서명 불일치·alg 변조를 가르지 않는다
- 415 UNSUPPORTED_MEDIA_TYPE: 바디가 `application/jose`가 아니다 (프레임워크 폴백)

<Operation source="reference-policy" id="policy-partner-submission-submit" />
