---
search:
  tags:
    - Policy Draft
    - POST
seo:
  description: >-
    head를 폐기로 전이한다. revision 기록은… Reference for the POST
    /policy-drafts/{draftId}/discard endpoint in the Wallet Policy Engine
    Internal API API.
sidebar:
  label: draft 폐기
  badge: POST
title: draft 폐기
type: openapi-operation
---
head를 폐기로 전이한다. revision 기록은 불변으로 남는다.

**폐기는 그 draft의 열린 발행 승인을 무효화한다.** 승인 행을 고쳐서가 아니라(승인
기록은 불변이다) 발행 이전 관문이 revision의 draft 상태를 다시 읽기 때문이다 —
기승인이어도 발행은 409 DRAFT_DISCARDED로 거부된다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 DRAFT_FORBIDDEN: 발행 권한 role의 부여가 없다
- 404 DRAFT_UNKNOWN: 그런 draft가 없다 (자격 밖 draft 포함)
- 409 DRAFT_DISCARDED: 이미 폐기된 draft다

<Operation source="reference-policy" id="policy-draft-discard-discard" />
