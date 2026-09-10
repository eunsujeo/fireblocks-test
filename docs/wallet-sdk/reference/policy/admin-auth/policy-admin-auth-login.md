---
search:
  tags:
    - Admin Auth
    - POST
seo:
  description: >-
    로그인. 성공하면 2FA 챌린지를 반환한다. Reference for the POST /auth/login endpoint in the
    Wallet Policy Engine Internal API API.
sidebar:
  label: 어드민 로그인
  badge: POST
title: 어드민 로그인
type: openapi-operation
---
로그인. 성공하면 2FA 챌린지를 반환한다.

실패 응답은 균일하다 — 자격 불일치·계정 부재·잠금을 구분하지 않아 계정 존재를 노출하지 않는다.

Errors:
- 400 INVALID_REQUEST: 바디가 JSON 형태가 아니다
- 401 AUTHENTICATION_FAILED: 그 밖의 모든 실패. 바디 부재도 여기로 접힌다

<Operation source="reference-policy" id="policy-admin-auth-login" />
