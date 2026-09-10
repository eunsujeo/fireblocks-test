---
search:
  tags:
    - Admin Auth
    - GET
seo:
  description: >-
    세션이 증명하는 신원 조회. 자격은 Bearer 헤더 또는 세션… Reference for the GET /auth/session
    endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: 세션 신원 조회
  badge: GET
title: 세션 신원 조회
type: openapi-operation
---
세션이 증명하는 신원 조회. 자격은 Bearer 헤더 또는 세션 쿠키다(`PolicyAdminSessionAuth`).
만료·변조 토큰은 401이고 봉투는 위 둘과 같다. 콘솔은 기동 시 이것으로 쿠키 세션을 되살린다.

인가 판정의 입력을 콘솔이 직접 볼 수 있게 하는 자리다 — 어드민 액션의 인가는
여기 실린 grants로 판정된다.

Errors:
- 401 AUTHENTICATION_FAILED: 세션이 확정되지 않았다. 계정의 소속이 이 문과 어긋난 경우도 여기다

<Operation source="reference-policy" id="policy-admin-auth-session" />
