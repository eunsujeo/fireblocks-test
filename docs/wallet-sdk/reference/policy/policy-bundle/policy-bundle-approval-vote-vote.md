---
search:
  tags:
    - Policy Bundle
    - POST
seo:
  description: >-
    발행 승인 표 제출(checker… Reference for the POST
    /policy-bundles/approvals/{publicationApprovalId}/votes endpoint in the
    Wallet Policy Engine Internal API API.
sidebar:
  label: 발행 승인 표 제출
  badge: POST
title: 발행 승인 표 제출
type: openapi-operation
---
발행 승인 표 제출(checker). 건별 2FA 스텝업 필수이고 maker 본인은 던질 수 없다.

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 verdict·challengeId·challengeResponse가 형태를 벗어났다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 401 PUBLICATION_CHALLENGE_INVALID: 건별 2FA 스텝업에 실패했다
- 403 SELF_APPROVAL_FORBIDDEN: maker 본인이다
- 404 PUBLICATION_APPROVAL_UNKNOWN: 표를 받을 수 있는 승인 레코드가 아니다 (자격 밖 참조 포함)
- 409 PUBLICATION_VOTE_ALREADY_CAST: 이미 표를 던졌다

<Operation source="reference-policy" id="policy-bundle-approval-vote-vote" />
