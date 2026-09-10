---
search:
  tags:
    - Workspace Registration
    - POST
seo:
  description: >-
    매니저를 하나 더 지명하고 그 사람의… Reference for the POST
    /platform/workspaces/{workspaceId}/managers endpoint in the Wallet Policy
    Engine Internal API API.
sidebar:
  label: 워크스페이스 매니저 지명
  badge: POST
title: 워크스페이스 매니저 지명
type: openapi-operation
---
매니저를 하나 더 지명하고 그 사람의 1회용 등록 토큰을 낸다.

등록 행 조회가 이 오퍼레이션의 유일한 관문이다 — 회수·재발급은 부여 조회에서 막히지만
지명에는 대조할 부여가 아직 없다. 없으면 platform 매니저가 임의의 좌표에 소속과 부여를
세우고, 그 계정은 사람을 더 들일 수 있으면서 그 좌표에는 시드가 없어 평가가 전부 거부다.

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 필수 필드가 비었거나 상한을 넘었다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 401 WORKSPACE_CHALLENGE_INVALID: 건별 2FA 스텝업에 실패했다
- 403 WORKSPACE_ADMIN_FORBIDDEN: 초대 권한의 platform 부여가 없다 (대상 계정의 소속 불변식 위반 포함)
- 404 WORKSPACE_TARGET_UNKNOWN: 등록되지 않은 좌표다
- 409 ADMIN_LOGIN_ID_TAKEN: 등록을 마친 계정이 그 loginId 를 쓴다

<Operation source="reference-policy" id="policy-workspace-managers-assign" />
