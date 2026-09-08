---
name: admin-policy-change
description: Blockchain Manager Admin의 버전형 정책·sweep 컨트랙트·allowance cap·밴드S·boost·출시 게이트 변경 흐름을 구현하거나 리뷰하는 절차. 변경 요청, diff, Quorum 승인, 활성화, pause/resume, 정책 snapshot, 외부 drift를 다루는 작업에서 사용한다.
---

# Admin Policy Change

자산 이동 조건을 바꾸는 작업을 요청·승인·활성화·실행 증적으로 분리한다. 화면의 승인 표시가 아니라 Domain과 DB가 불변식을 강제하게 한다.

## 1. 정본과 소유권을 확인한다

1. `docs/design/08-bcm-admin.md`, `06-sweep.md`, `03-bcm-db.md`를 읽는다.
2. `.claude/rules/admin-safety.md`와 `policy-lifecycle.md`를 읽는다.
3. 변경 대상이 BCM 실행 정책인지 Fireblocks 정책 관리 서비스·TAP·Callback·multisig 소관인지 가른다.
4. 외부 소관을 BCM이 직접 편집하거나 승인했다고 기록하지 않는다. 기대값과 재조회한 실제값만 대조한다.

`docs/design/`의 설계 정본이 없거나 상태코드·정족수·소유권이 미확정이면 사용자 요청·확정 결정을 먼저 반영한다. 결정이 없는 정책은 사용자에게 확인한다.

## 2. 불변 변경 모델을 만든다

- 기존 활성 정책·컨트랙트를 덮어쓰지 않고 새 버전과 변경 요청을 만든다.
- 변경 요청 생성, 승인·거절, 활성화를 서로 다른 오퍼레이션으로 둔다.
- 요청자와 최종 승인자를 분리한다.
- 일반 변경·자금 실행은 요청자 외 독립 승인자 1명, 보안 변경·재개는 요청자 외 서로 다른 승인자 2명과 그중
  `BCM_SECURITY_APPROVER` 1명을 요구한다. 이 정족수와 역할 조합을 서버에서 검사한다.
- 같은 범위에는 활성 버전 하나만 허용하고 DB 제약을 최종 방어로 둔다.
- 승인 대상 snapshot hash와 현재 상태가 다르면 승인을 거절하고 새 diff를 요구한다.
- 요청·승인·활성화 재시도가 중복 버전·승인·실행을 만들지 않게 한다.
- 취소·거절·대체 관계도 삭제하지 않고 남긴다.

## 3. hard ceiling과 외부 경계를 검사한다

- Admin 운영 정책은 배포 hard ceiling보다 좁아야 한다.
- 컨트랙트 활성화·재개는 pinned block 기준의 독립 RPC 2곳에서 chainId·code hash·불변값이 모두 일치하는지와
  pause·운영자·감사 증적·출시 게이트를 재검사한다.
- TAP·Callback·Gasless·multisig 상태는 외부 재조회 결과로 확인한다.
- 조회 실패·stale evidence·drift를 성공으로 처리하지 않는다.
- 실행에는 적용한 정책 버전 또는 snapshot hash를 남긴다.
- 선기록된 실행은 이후 정책 변경으로 의미가 바뀌지 않는다.
- 밴드S snapshot·이동안 계산은 DAW-CORE, 승인된 지시의 검증·멱등 실행·추적은 BCM 소관으로 유지한다.
- 첫 hot→cold 경로는 고객 vault sweep·출금 풀 회수→omnibus→TAP 고정 외부 cold만 허용하고 cold→hot 서명은 Admin이 수행하지 않는다.

## 4. 중지와 재개를 비대칭으로 다룬다

- 중지는 신속 경로를 둘 수 있지만 대상·사유·작업자·시각을 남긴다.
- 재개·상향·컨트랙트 활성화·자금 이동은 최신 상태 재조회와 강화된 승인을 요구한다.
- 컨트랙트 교체는 기존 신규 실행 중지 → 진행 실행 대사 → 구 allowance 회수 → 0 확인 → 신규 활성화·승인 순서를 지킨다.
- 구·신 컨트랙트 allowance가 동시에 열린 상태를 정상 완료로 보지 않는다.

## 5. 필수 테스트

- 요청자 자신의 최종 승인 거절.
- 부족한 정족수와 잘못된 역할 거절.
- 보안 변경·재개에서 두 승인자 중 보안 승인자가 없거나 승인자가 같은 사람인 경우 거절.
- 같은 승인자의 중복 승인 멱등.
- 동시 활성화에서 하나만 성공.
- 승인 화면을 연 뒤 정책이 바뀐 stale snapshot 거절.
- hard ceiling 초과·외부 drift·검증 만료 fail-closed.
- 컨트랙트의 독립 RPC 2곳 중 실패·stale·chainId/code hash/불변값 불일치 fail-closed.
- 중복 활성화 요청과 응답 유실 재시도 무중복.
- pause는 허용하지만 같은 조건에서 resume은 거절되는 비대칭.
- 실행 이력이 과거 정책 snapshot을 계속 재현.

## 6. 리뷰 결과에 답할 질문

- 누가 요청했고 누가 최종 승인했는가?
- 정확히 어떤 이전/신규 값과 snapshot을 승인했는가?
- DB가 동시성·중복을 어떻게 막는가?
- 어떤 hard ceiling과 외부 상태를 확인했는가?
- 실행과 감사에서 당시 정책을 재현할 수 있는가?
- 실패·취소·대체·재시도도 사라지지 않는가?
