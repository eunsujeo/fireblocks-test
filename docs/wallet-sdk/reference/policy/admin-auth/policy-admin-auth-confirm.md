---
search:
  tags:
    - Admin Auth
    - POST
seo:
  description: >-
    등록 2단계. 1단계가 내준 시드로 만든 코드를 대조하고,… Reference for the POST
    /auth/enrollments/confirm endpoint in the Wallet Policy Engine Internal API
    API.
sidebar:
  label: 2FA 등록 확인
  badge: POST
title: 2FA 등록 확인
type: openapi-operation
---
등록 2단계. 1단계가 내준 시드로 만든 코드를 대조하고, 맞으면 2FA를 활성으로 넘기며
그때 토큰을 소진한다. **세션을 요구하지 않는다** — 아직 자격이 없는 사람이 부른다.

틀리면 아무것도 바뀌지 않는다 — 자격증명이 살아 있고 토큰도 남아 같은 시드로 다시
시도할 수 있다. 1단계를 지난 토큰은 남은 수명이 분 단위로 당겨지므로 그 재시도는
사람이 화면 앞에 있는 구간 안이다.

실패 응답은 로그인과 같은 봉투다 — **코드 불일치와 토큰의 부재·만료·소진을 구분하지
않는다.** 가르면 그 차이가 곧 "이 토큰은 살아 있다"를 알려 준다.

Errors:
- 400 INVALID_REQUEST: 바디가 JSON 형태가 아니다
- 401 AUTHENTICATION_FAILED: 그 밖의 모든 실패. 바디 부재도 여기로 접힌다

<Operation source="reference-policy" id="policy-admin-auth-confirm" />
