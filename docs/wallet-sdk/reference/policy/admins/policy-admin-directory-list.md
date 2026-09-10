---
search:
  tags:
    - Admins
    - GET
seo:
  description: >-
    요청자의 초대 권한 부여 스코프에 있는 어드민 목록. Reference for the GET /admins endpoint in the
    Wallet Policy Engine Internal API API.
sidebar:
  label: 어드민 목록 조회
  badge: GET
title: 어드민 목록 조회
type: openapi-operation
---
요청자의 초대 권한 부여 스코프에 있는 어드민 목록.

**스코프 파라미터가 없는 것이 계약이다** — 세션의 부여가 그것을 정한다. 초대와 달리
여러 스코프를 가진 요청자를 거부하지 않는다: 초대는 새 부여를 어디에 붙일지 하나로
정해야 해서 거부하는 것이고, 읽기에는 그 선택이 없어 가진 스코프를 합친다.

Errors:
- 401 AUTHENTICATION_FAILED: 세션이 확정되지 않았다
- 403 ADMIN_DIRECTORY_FORBIDDEN: 목록 권한이 없다

<Operation source="reference-policy" id="policy-admin-directory-list" />
