---
search:
  tags:
    - PIP Source Kinds
    - GET
seo:
  description: >-
    소스 종류 카탈로그 조회. read-only다 — 이 표면에 쓰기… Reference for the GET
    /pip-source-kinds endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: PIP 소스 종류 목록 조회
  badge: GET
title: PIP 소스 종류 목록 조회
type: openapi-operation
---
소스 종류 카탈로그 조회. **read-only다** — 이 표면에 쓰기 오퍼레이션이 없는 것이
"종류는 코드, 인스턴스는 번들"의 표면 쪽 표현이다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 없거나 유효하지 않다
- 403 DRAFT_FORBIDDEN: 발행 권한 role의 부여가 하나도 없다 (스코프는 가리지 않는다)

<Operation source="reference-policy" id="pip-source-kind-list" />
