---
search:
  tags:
    - Policy Bundle
    - GET
seo:
  description: >-
    승인 레코드 상세. 집계와… Reference for the GET
    /policy-bundles/approvals/{publicationApprovalId} endpoint in the Wallet
    Policy Engine Internal API API.
sidebar:
  label: 발행 승인 레코드 상세 조회
  badge: GET
title: 발행 승인 레코드 상세 조회
type: openapi-operation
---
승인 레코드 상세. **집계와 챌린지가 여기서만 나온다** — 목록은 레코드마다 왕복이 생기지
않게 그 둘을 싣지 않으므로, checker가 표를 던지기 전에 보는 값이 이 표면이다.

자격 밖 레코드는 부재와 같은 답을 받는다 — 갈라 답하면 그 id의 실재가 드러난다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 PUBLICATION_FORBIDDEN: 발행 권한 role의 부여가 없다
- 404 PUBLICATION_APPROVAL_UNKNOWN: 그런 승인 레코드가 없다 (자격 밖 참조 포함)

<Operation source="reference-policy" id="policy-bundle-approval-view-view" />
