---
paths:
  - "**/admin/**"
  - "**/*Admin*.kt"
  - "docs/api/openapi.yaml"
---
# Admin Safety

- Blockchain Manager Admin은 BFF만 호출 주체로 둔다. 브라우저가 BCM·Fireblocks·RPC를 직접 호출하지 않는다.
- BFF→BCM private listener는 mTLS 서비스 신원과 `aud=bcm-admin-api`인 5분 이하 단기 서명 JWT를 함께 검증한다.
  JWT의 직원번호·부점코드·역할·세션·요청 ID는 검증된 claim만 사용한다.
- `X-Employee-No`·`X-Branch-Code`는 감사 정보이지 인증·인가 수단이 아니다.
- 프론트 권한 표시는 편의 기능이다. 가능한 action과 상태 전이는 서버가 판단한다.
- 변경 요청, 승인·거절, 활성화·실행을 한 오퍼레이션으로 합치지 않는다.
- 요청자와 최종 승인자를 분리하고 정족수·역할을 Domain과 DB에서 방어한다. 일반 변경·자금 실행은 요청자 외
  독립 승인자 1명, 보안 변경·재개는 요청자 외 서로 다른 승인자 2명과 그중 `BCM_SECURITY_APPROVER` 1명이 필요하다.
- 정책·컨트랙트·승인·감사 이력을 직접 수정하거나 물리 삭제하지 않는다.
- Admin 정책은 배포 hard ceiling과 TAP·Callback·컨트랙트 강제를 완화할 수 없다.
- 외부 조회 실패·stale evidence·drift·해석 불가는 fail-closed다.
- 컨트랙트 활성화·재개는 pinned block 기준으로 서로 독립된 RPC 2곳의 chainId·code hash·불변값이 모두 일치해야 한다.
- 밴드S snapshot·이동안은 DAW-CORE가 계산하고 BCM은 승인된 지시만 검증·실행한다. 첫 hot→cold 경로는 고객 vault
  sweep과 출금 풀 회수로 omnibus에 모은 뒤 omnibus에서 TAP 고정 외부 cold 주소로만 나간다. cold→hot 서명은 외부 cold 소관이다.
- 중지는 신속할 수 있지만 재개·상향·활성화·자금 이동은 최신 상태와 강화된 승인을 요구한다.
- raw webhook payload·서명·시크릿·RPC 자격을 Admin 응답·로그에 노출하지 않는다.
- 모든 시간은 API UTC `Z`, DB UTC 코어 포맷을 지킨다. 금액은 문자열/BigDecimal 계약을 유지한다.
