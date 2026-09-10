---
search:
  tags:
    - Request
    - GET
seo:
  description: >-
    상태 조회. 이벤트 유실 시 재동기화용이자 서명 연산… Reference for the GET
    /request/{policyRequestId} endpoint in the Wallet Policy Engine Internal API
    API.
sidebar:
  label: 판단 요청 조회
  badge: GET
title: 판단 요청 조회
type: openapi-operation
---
상태 조회. 이벤트 유실 시 재동기화용이자 서명 연산 소비자(owning service)의 실행 근거다 —
이벤트는 깨우는 신호이고 실행 근거는 조회다(§4). 어드민 액션은 집행이 결정 소유자와
같은 프로세스라 실행 전 재확인이 불요하다(D-65).

Errors: (코드 값 정본은 PolicyRequestErrorCode — D-88)
- 401 UNAUTHENTICATED: 인증 실패
- 403 FORBIDDEN_ROLE: 서비스 크리덴셜의 역할이 이 표면을 부를 수 없다 (인증 필터가 낸다)
- 404 NOT_FOUND: 그 policyRequestId가 없거나 caller의 것이 아니다 — **둘을 합쳐 답한다.**
갈라 답하면 남의 요청 id에 403이 돌아가 그 id의 존재가 드러난다

<Operation source="reference-policy" id="policy-request-get-detail" />
