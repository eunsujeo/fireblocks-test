---
search:
  tags:
    - Policy Bundle
    - POST
seo:
  description: >-
    발행 승인 레코드 개설(maker). Reference for the POST /policy-bundles/approvals
    endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: 발행 승인 레코드 개설
  badge: POST
title: 발행 승인 레코드 개설
type: openapi-operation
---
발행 승인 레코드 개설(maker).

인증: 어드민 세션. 인가: 발행 권한 role의 platform 부여.

검증을 통과하지 않은 revision은 422 DRAFT_REVISION_NOT_VALIDATED다.

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 bundleName·draftRevisionId가 비었다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 PUBLICATION_FORBIDDEN: 발행 권한 role의 platform 부여가 없다
- 404 DRAFT_REVISION_UNKNOWN: 그런 revision이 없다
- 409 DRAFT_DISCARDED: 폐기된 draft의 칸이다
- 422 DRAFT_REVISION_NOT_VALIDATED: 검증을 통과한 revision이 아니다

<Operation source="reference-policy" id="policy-bundle-approval-open" />
