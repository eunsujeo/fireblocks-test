---
search:
  tags:
    - Policy Draft
    - GET
seo:
  description: >-
    콘솔 목록. 폐기된 것도 준다 — 목록이 활성/폐기를 구분해 보여야… Reference for the GET /policy-drafts
    endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: draft 목록
  badge: GET
title: draft 목록
type: openapi-operation
---
콘솔 목록. **폐기된 것도 준다** — 목록이 활성/폐기를 구분해 보여야 한다.
목록의 술어는 세션이 확정한 스코프다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 DRAFT_FORBIDDEN: 발행 권한 role의 부여가 없다

<Operation source="reference-policy" id="policy-draft-list" />
