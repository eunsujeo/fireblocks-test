---
search:
  tags:
    - Bootstrap
    - POST
seo:
  description: >-
    어드민 계정과 역할을 만들고 1회용 등록 토큰을 낸다. Reference for the POST
    /platform/bootstrap/admins endpoint in the Wallet Policy Engine Internal API
    API.
sidebar:
  label: 초기 어드민 계정 생성
  badge: POST
title: 초기 어드민 계정 생성
type: openapi-operation
---
어드민 계정과 역할을 만들고 1회용 등록 토큰을 낸다.

아직 등록을 마치지 않은 계정에 다시 호출하면 새 토큰이 나고 이전 토큰은 통하지
않는다 — 만료된 토큰 하나가 환경 전체를 굳히지 않게 하는 자리다.

**이미 2팩터를 등록한 계정이면 거부한다.** 허용하면 root 가 활동 중인 어드민의
자격을 초기화해 그 사람 행세를 할 수 있다.

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 loginId·displayName 이 없거나 형태를 벗어났다
- 401 AUTHENTICATION_FAILED: root 자격이 확정되지 않았다
- 403 BOOTSTRAP_CLOSED: 부트스트랩이 이미 끝났다
- 409 ADMIN_LOGIN_ID_TAKEN: 등록을 마친 계정이 그 loginId 를 쓴다

<Operation source="reference-policy" id="policy-bootstrap-create-admin" />
