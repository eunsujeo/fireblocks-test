---
search:
  tags:
    - Policy Draft
    - GET
seo:
  description: >-
    head와 체인. 각 칸의 검증 결과가 함께 온다. Reference for the GET /policy-drafts/{draftId}
    endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: draft 조회
  badge: GET
title: draft 조회
type: openapi-operation
---
head와 체인. 각 칸의 검증 결과가 함께 온다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 DRAFT_FORBIDDEN: 발행 권한 role의 부여가 없다
- 404 DRAFT_UNKNOWN: 그런 draft가 없다 (자격 밖 draft 포함)

<Operation source="reference-policy" id="policy-draft-view" />
