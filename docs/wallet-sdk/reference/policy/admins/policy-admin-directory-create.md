---
search:
  tags:
    - Admins
    - POST
seo:
  description: >-
    계정과 역할 부여를 만들고 1회용 등록 토큰을 낸다. Reference for the POST /admins endpoint in the
    Wallet Policy Engine Internal API API.
sidebar:
  label: 어드민 초대
  badge: POST
title: 어드민 초대
type: openapi-operation
---
계정과 역할 부여를 만들고 1회용 등록 토큰을 낸다.

인가는 `policy_admin_manager` 부여이고, 새 부여의 스코프는 **그 부여의 스코프**다.
요청자가 그 역할을 여러 스코프에서 가지면 거부한다 — 스코프를 요청이 고르게 하면
격리 경계가 흐려진다.

부여할 역할은 요청이 고르되 요청자가 같은 스코프에서 이미 가진 역할이어야 한다.
닫힌 집합이라 오타는 거부로 떨어지고 권한 상향이 성립하지 않는다.

아직 등록을 마치지 않은 계정에 다시 호출하면 새 토큰이 나고 이전 토큰은 통하지
않는다. **이미 2팩터를 등록한 계정이면 거부한다** — 허용하면 활동 중인 어드민의
자격을 초기화해 그 사람 행세를 할 수 있다.

Errors:
- 400 INVALID_REQUEST: 바디가 JSON 형태가 아니거나 필수 필드가 없다
- 401 AUTHENTICATION_FAILED: 세션이 확정되지 않았다
- 403 ADMIN_DIRECTORY_FORBIDDEN: 초대 권한이 없다
- 409 ADMIN_LOGIN_ID_TAKEN: 등록을 마친 계정이 그 loginId 를 쓴다

<Operation source="reference-policy" id="policy-admin-directory-create" />
