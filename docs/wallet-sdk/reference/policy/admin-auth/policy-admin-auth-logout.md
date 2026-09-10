---
search:
  tags:
    - Admin Auth
    - POST
seo:
  description: >-
    로그아웃. 이 브라우저의 세션 쿠키를 지운다. Reference for the POST /auth/logout endpoint in
    the Wallet Policy Engine Internal API API.
sidebar:
  label: 어드민 로그아웃
  badge: POST
title: 어드민 로그아웃
type: openapi-operation
---
로그아웃. 이 브라우저의 세션 쿠키를 지운다.

**자격을 요구하지 않는다** — 쿠키를 지우는 일은 무해하고 멱등이라 만료된 세션을 든 화면도
부를 수 있어야 한다. 요구하면 그 화면이 쿠키를 지우지 못한 채 남는다.

**서버측 폐기가 아니다** — 서버는 세션을 저장하지 않으므로(무상태 HMAC) 본문으로 옮겨 적은
크리덴셜은 만료 시각까지 산다.

<Operation source="reference-policy" id="policy-admin-auth-logout" />
