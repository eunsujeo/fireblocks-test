---
search:
  tags:
    - Workspace Registration
    - POST
seo:
  description: >-
    워크스페이스를 세우고 첫 매니저를 지명한다. Reference for the POST /platform/workspaces
    endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: 워크스페이스 등록
  badge: POST
title: 워크스페이스 등록
type: openapi-operation
---
워크스페이스를 세우고 첫 매니저를 지명한다.

인증: 어드민 세션 + 건별 2FA 스텝업. 인가: 초대 권한 role 의 platform 부여.

관문 순서는 좌표 형태 → 스텝업 → 등록 조회 → 시드 주입 → 등록·지명이다. **시드가 등록보다
앞인 것이 요건이다** — 실패하면 등록부에 아무것도 남지 않아 재시도가 안전하고, 시드 없는
워크스페이스가 default deny 로 죽는 상태가 생기지 않는다.

새로 세우면 201, 이미 등록돼 있었으면 200 이고 본문의 `alreadyRegistered` 가 그것을 말한다.

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 필수 필드가 비었거나 상한을 넘었다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 401 WORKSPACE_CHALLENGE_INVALID: 건별 2FA 스텝업에 실패했다
- 403 WORKSPACE_ADMIN_FORBIDDEN: 초대 권한의 platform 부여가 없다 (매니저 계정의 소속 불변식 위반 포함)
- 409 ADMIN_LOGIN_ID_TAKEN: 등록을 마친 계정이 그 loginId 를 쓴다
- 500 WORKSPACE_SEED_FAILED: 시드 정책을 주입하지 못해 등록을 중단했다

<Operation source="reference-policy" id="policy-workspace-registration-register" />
