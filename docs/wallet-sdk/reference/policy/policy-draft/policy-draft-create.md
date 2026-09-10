---
search:
  tags:
    - Policy Draft
    - POST
seo:
  description: >-
    draft와 첫 칸을 함께 만든다. Reference for the POST /policy-drafts endpoint in the
    Wallet Policy Engine Internal API API.
sidebar:
  label: draft 생성
  badge: POST
title: draft 생성
type: openapi-operation
---
draft와 첫 칸을 함께 만든다.

Errors:
- 400 INVALID_REQUEST: 바디가 JSON 형태가 아니거나 name·content가 없다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 DRAFT_FORBIDDEN: 발행 권한 role의 부여가 없거나 여럿이다
- 409 DRAFT_NAME_DUPLICATE: 같은 이름의 활성 draft가 있다
- 413 DRAFT_CONTENT_TOO_LARGE: 본문이 256 KiB를 넘는다

<Operation source="reference-policy" id="policy-draft-create" />
