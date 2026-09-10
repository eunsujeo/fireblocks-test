---
search:
  tags:
    - Workspace Registration
    - POST
seo:
  description: >-
    매니… Reference for the POST
    /platform/workspaces/{workspaceId}/managers/{adminUserId}/enrollment-tokens
    endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: 워크스페이스 매니저 등록 토큰 재발급
  badge: POST
title: 워크스페이스 매니저 등록 토큰 재발급
type: openapi-operation
---
매니저의 등록 토큰을 다시 낸다. 이전 토큰은 통하지 않는다 — 만료된 토큰 하나가 그 계정을
굳히지 않게 하는 자리다.

**이미 2팩터를 등록한 계정이면 거부한다**(409). 허용하면 활동 중인 매니저의 자격을
초기화해 그 사람 행세를 할 수 있다.

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 challengeId·challengeResponse 가 비었다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 401 WORKSPACE_CHALLENGE_INVALID: 건별 2FA 스텝업에 실패했다
- 403 WORKSPACE_ADMIN_FORBIDDEN: 초대 권한의 platform 부여가 없다 (대상 계정의 소속 불변식 위반 포함)
- 404 WORKSPACE_TARGET_UNKNOWN: 그 워크스페이스의 매니저가 아니다
- 409 ADMIN_LOGIN_ID_TAKEN: 그 계정이 이미 2팩터 등록까지 마쳤다

<Operation source="reference-policy" id="policy-workspace-managers-reissue-enrollment-token" />
