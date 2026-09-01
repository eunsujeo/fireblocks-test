# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~27 완료. Phase 15는 계속 보류.**
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 구현 — T14.28 생성 원장 PostgreSQL 경합·rollback 검증
- 계정·주소 각각 새 키 세대 준비를 `REQUIRES_NEW` 트랜잭션에서 보유하고 옛 세대 완료를 다른 스레드에서 겹쳤다.
- 옛 완료는 준비 트랜잭션이 끝날 때까지 실제로 대기하고, 새 세대를 읽은 뒤 `CREATION_RETRY_LATER`로 거절되어 공개 매핑을 만들지 않는다.
- test trigger는 완료 원장 UPDATE 직전 같은 트랜잭션의 공개 매핑 INSERT를 확인하고 강제 예외를 발생시킨다.
- 예외 뒤 계정·주소 공개 매핑은 없고 생성 원장은 `SUBMITTING`·미완료 값 그대로여서 두 변경의 원자 rollback을 고정했다.
- 프로덕션 코드·DB migration·설계 계약은 변경하지 않았고 실제 Fireblocks mutation도 실행하지 않았다.

## 검증 완료 (2026-09-01)
- `AccountPersistenceTest` 14건과 persistence test ktlint가 통과했다.
- `FOR UPDATE` 두 곳을 제거한 회귀 상태에서는 계정·주소 동시성 테스트가 모두 실패했고, 원복 뒤 다시 통과했다.
- 전체 `./scripts/ci.sh`가 그린이다. Dependency-Check는 기존 PLAN #42 Kafka Medium 1건만 보고하고 게이트를 통과했다.

## 다음 작업
- 열린 설계 항목은 PLAN 표를 기준으로 외부 조건이 추가로 확정된 항목부터 진행한다.
- Phase 15는 운영 논의 재개 전까지 보류한다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.
