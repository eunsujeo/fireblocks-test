---
search:
  tags:
    - Approvals
    - POST
seo:
  description: >-
    표 제출. 건별 2FA 스텝업 필수. Reference for the POST
    /request/{policyRequestId}/approvals endpoint in the Wallet Policy Engine
    Internal API API.
sidebar:
  label: 승인 표 제출
  badge: POST
title: 승인 표 제출
type: openapi-operation
---
표 제출. 건별 2FA 스텝업 필수.

인증: 어드민 세션(§8) 위에 건별 2FA 스텝업이다 — BearerAuth 표기는 세션 크리덴셜의
자리표시자이고, 토큰 포맷은 어드민 인증 API와 함께 후속 확정이다.
정족수 충족은 이행 증거의 완성일 뿐이고, 전체 표 자격 재검증 + stale 재조회 +
전체 재평가(design-draft §9.3)를 통과해야 APPROVED다.

**접두 사본이 없다** — 이 경로는 워크스페이스 문에만 있고 `/platform` 아래에 같은 표면이
없다(policy-api.tsp 문 표).

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 verdict·challengeId·challengeResponse가 형태를 벗어났다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 401 APPROVAL_CHALLENGE_INVALID: 건별 2FA 스텝업에 실패했다
- 403 APPROVAL_NOT_ELIGIBLE: 이 요청의 승인 자격이 없다
- 403 SELF_APPROVAL_FORBIDDEN: 요청자 본인이다
- 404 APPROVAL_TARGET_UNKNOWN: 표를 받을 수 있는 요청이 아니다
- 409 APPROVAL_ALREADY_CAST: 이미 표를 던졌다

<Operation source="reference-policy" id="policy-approval-vote-vote" />
