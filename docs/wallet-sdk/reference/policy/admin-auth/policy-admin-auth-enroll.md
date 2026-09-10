---
search:
  tags:
    - Admin Auth
    - POST
seo:
  description: >-
    등록 1단계. 1회용 등록 토큰으로 비밀번호를 세우고 2FA 시드를… Reference for the POST
    /auth/enrollments endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: 2FA 자가 등록
  badge: POST
title: 2FA 자가 등록
type: openapi-operation
---
등록 1단계. 1회용 등록 토큰으로 비밀번호를 세우고 2FA 시드를 **한 번** 돌려준다.
이 호출은 토큰을 소진하지 않고, 2FA는 아직 활성이 아니다 — 활성은 `confirm`이 한다.

다시 부를 수 있다. QR을 못 찍은 사람이 갇히지 않게 하는 재시도 경로이고, 그 대가로
이전 시드와 이전 비밀번호가 폐기된다 — 마지막 1단계의 것만 산다.

실패 응답은 로그인과 같은 봉투다 — 토큰의 부재·만료·소진을 구분하지 않는다.

Errors:
- 400 INVALID_REQUEST: 바디가 JSON 형태가 아니다
- 401 AUTHENTICATION_FAILED: 그 밖의 모든 실패. 바디 부재도 여기로 접힌다

<Operation source="reference-policy" id="policy-admin-auth-enroll" />
