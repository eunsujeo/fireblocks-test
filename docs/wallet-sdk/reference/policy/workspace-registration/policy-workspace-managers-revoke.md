---
search:
  tags:
    - Workspace Registration
    - POST
seo:
  description: >-
    매니저 부여를… Reference for the POST
    /platform/workspaces/{workspaceId}/managers/{adminUserId}/revoke endpoint in
    the Wallet Policy Engine Internal API API.
sidebar:
  label: 워크스페이스 매니저 회수
  badge: POST
title: 워크스페이스 매니저 회수
type: openapi-operation
---
매니저 부여를 회수한다. 경로가 POST 인 것은 어드민 콘솔 CORS 가 GET·POST 만 허용하기
때문이고, 본문이 필요한 이유는 스텝업이다.

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 challengeId·challengeResponse 가 비었다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 401 WORKSPACE_CHALLENGE_INVALID: 건별 2FA 스텝업에 실패했다
- 403 WORKSPACE_ADMIN_FORBIDDEN: 초대 권한의 platform 부여가 없다
- 404 WORKSPACE_TARGET_UNKNOWN: 그 워크스페이스의 매니저가 아니다

<Operation source="reference-policy" id="policy-workspace-managers-revoke" />
