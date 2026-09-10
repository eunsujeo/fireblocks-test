---
search:
  tags:
    - Policy Draft
    - GET
seo:
  description: >-
    칸 하나. 여기서만 content가 실린다. Reference for the GET
    /policy-drafts/{draftId}/revisions/{revisionId} endpoint in the Wallet
    Policy Engine Internal API API.
sidebar:
  label: revision 조회
  badge: GET
title: revision 조회
type: openapi-operation
---
칸 하나. 여기서만 content가 실린다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 DRAFT_FORBIDDEN: 발행 권한 role의 부여가 없다
- 404 DRAFT_UNKNOWN: 그런 draft가 없다 (자격 밖 draft 포함)
- 404 DRAFT_REVISION_UNKNOWN: 그런 revision이 없거나 이 draft의 칸이 아니다

<Operation source="reference-policy" id="policy-draft-revision-view" />
