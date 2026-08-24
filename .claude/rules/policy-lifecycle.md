---
paths:
  - "**/*Policy*.kt"
  - "**/*Contract*.kt"
  - "**/sweep/**/*.kt"
  - "**/db/migration/*.sql"
---
# Policy Lifecycle

- 정책·컨트랙트는 불변 버전으로 추가하고 활성 행을 덮어쓰지 않는다.
- 개념 흐름은 `초안 → 검토 → 승인 → 활성 → 대체`이며 실제 상태코드·전이는 설계와 OpenAPI에서 확정한다.
- 같은 적용 범위에는 활성 버전 하나만 허용하고 DB 제약을 최종 방어로 둔다.
- 승인 대상 snapshot과 현재 snapshot이 다르면 stale 요청을 거절한다.
- 일반 변경·자금 실행은 요청자 외 독립 승인자 1명, 보안 변경·재개는 요청자 외 서로 다른 승인자 2명과 그중
  `BCM_SECURITY_APPROVER` 1명을 요구한다. 거절·만료·snapshot 변경 뒤의 승인은 재사용하지 않는다.
- 실행 원장에는 적용한 정책 버전 또는 snapshot hash를 남긴다.
- 선기록된 실행은 이후 정책 변경으로 의미가 달라지지 않는다.
- 외부 TAP·Callback·온체인 상태는 기대값과 재조회한 실제값을 구분하고 drift를 숨기지 않는다.
- 컨트랙트 활성화·재개 증적은 pinned block 기준의 독립 RPC 2곳이 같은 chainId·code hash·불변값을 반환해야 유효하다.
- pause와 resume을 대칭으로 취급하지 않는다. resume은 원인 해소·최신 검증·강화된 승인을 요구한다.
