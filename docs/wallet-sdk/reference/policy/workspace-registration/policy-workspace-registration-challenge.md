---
search:
  tags:
    - Workspace Registration
    - POST
seo:
  description: >-
    스텝업 챌린지 발급. 아무것도 저장하지 않는다 —… Reference for the POST
    /platform/workspaces/challenges endpoint in the Wallet Policy Engine
    Internal API API.
sidebar:
  label: 스텝업 챌린지 발급
  badge: POST
title: 스텝업 챌린지 발급
type: openapi-operation
---
스텝업 챌린지 발급. **아무것도 저장하지 않는다** — 본문을 정규화해 챌린지만 답한다.

Errors:
- 400 INVALID_REQUEST: action 이 등재된 값이 아니거나 그 행위의 필드가 없다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 WORKSPACE_ADMIN_FORBIDDEN: 초대 권한의 platform 부여가 없다

<Operation source="reference-policy" id="policy-workspace-registration-challenge" />
