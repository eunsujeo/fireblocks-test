---
search:
  tags:
    - Policy Draft
    - POST
seo:
  description: >-
    새 칸을 붙이고 head를 그리로 옮긴다. 새… Reference for the POST
    /policy-drafts/{draftId}/revisions endpoint in the Wallet Policy Engine
    Internal API API.
sidebar:
  label: revision 추가
  badge: POST
title: revision 추가
type: openapi-operation
---
새 칸을 붙이고 head를 그리로 옮긴다. **새 칸에는 검증 기록이 없다** — 편집이 검증을
무효화하는 것이 아니라, 결과가 애초에 칸마다 붙기 때문이다.

head가 그 사이 옮겨졌거나 draft가 폐기됐으면 409다.

Errors:
- 400 INVALID_REQUEST: 바디가 JSON 형태가 아니거나 content가 없다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 DRAFT_FORBIDDEN: 발행 권한 role의 부여가 없다
- 404 DRAFT_UNKNOWN: 그런 draft가 없다 (자격 밖 draft 포함)
- 409 DRAFT_DISCARDED: 폐기된 draft다
- 409 DRAFT_HEAD_CONFLICT: head가 그 사이 옮겨졌다
- 413 DRAFT_CONTENT_TOO_LARGE: 본문이 256 KiB를 넘는다

<Operation source="reference-policy" id="policy-draft-revision-append" />
