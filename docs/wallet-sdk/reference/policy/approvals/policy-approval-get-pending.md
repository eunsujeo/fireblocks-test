---
search:
  tags:
    - Approvals
    - GET
seo:
  description: >-
    승인 가능 role 기준 대기 목록. Reference for the GET /approvals endpoint in the Wallet
    Policy Engine Internal API API.
sidebar:
  label: 승인 대기 목록 조회
  badge: GET
title: 승인 대기 목록 조회
type: openapi-operation
---
승인 가능 role 기준 대기 목록.

**양쪽 문에서 열린다** — `/approvals`와 `/platform/approvals`가 같은 오퍼레이션이고
계정의 소속이 어느 경로를 지날 수 있는지 정한다(policy-api.tsp 문 표).

**빈 페이지가 끝을 뜻하지 않는다.** 자격 판정이 SQL 술어로 좁혀지지 않아 스캔이 상한만
지고 필터가 뒤에서 도므로, 자격 밖 항목만 담긴 페이지는 비어 나오면서 커서가 다음을
가리킨다. 끝 신호는 `cursor`가 null인 것 하나다.

Errors:
- 400 INVALID_REQUEST: 커서가 형태 밖이다 (limit은 서버 상한으로 접힌다)
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다

<Operation source="reference-policy" id="policy-approval-get-pending" />
