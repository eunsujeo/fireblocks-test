---
search:
  tags:
    - Admin Auth
    - POST
seo:
  description: >-
    성공하면 세션 크리덴셜을 본문과 Set-Cookie로 함께… Reference for the POST /auth/2fa endpoint
    in the Wallet Policy Engine Internal API API.
sidebar:
  label: 2FA 검증
  badge: POST
title: 2FA 검증
type: openapi-operation
---
성공하면 세션 크리덴셜을 본문과 `Set-Cookie`로 함께 낸다(`PolicyAdminSessionResponse`).
실패 응답은 로그인과 같은 봉투다(§8 균일 오류).

Errors:
- 400 INVALID_REQUEST: 바디가 JSON 형태가 아니다
- 401 AUTHENTICATION_FAILED: 그 밖의 모든 실패. 바디 부재도 여기로 접힌다

<Operation source="reference-policy" id="policy-admin-auth-verify2-fa" />
