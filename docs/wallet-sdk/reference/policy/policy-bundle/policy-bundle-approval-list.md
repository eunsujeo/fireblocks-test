---
search:
  tags:
    - Policy Bundle
    - GET
seo:
  description: >-
    checker가 대상을 찾는 자리. 집계·챌린지는 상세가 준다. Reference for the GET
    /policy-bundles/approvals endpoint in the Wallet Policy Engine Internal API
    API.
sidebar:
  label: 발행 승인 목록
  badge: GET
title: 발행 승인 목록
type: openapi-operation
---
checker가 대상을 찾는 자리. 집계·챌린지는 상세가 준다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 PUBLICATION_FORBIDDEN: 발행 권한 role의 부여가 없다

<Operation source="reference-policy" id="policy-bundle-approval-list" />
