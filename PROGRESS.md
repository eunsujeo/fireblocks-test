# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 Admin 운영 등록·진단 UX까지 완료. Phase 15는 사용자가 운영 논의를 재개할 때까지 보류.**
- 공유 환경의 운영 화면·Network/Asset·정책/컨트랙트 workflow 소유자는 DAW-ADMIN이다. 이 저장소 Admin은
  DAW-CORE에 의존하지 않는 로컬 개발·진단 콘솔이며 제거해도 API/Webhook/BAT가 동작한다.

## 이번 구현
- Admin Network 기본 화면은 BCM 연결분만 표시하고 Advanced에서 Fireblocks 전체 catalog를 진단한다.
- 자산 검색은 검색 간 최대 20개 선택을 유지한다. 서버는 전 항목을 Fireblocks 최신 원본으로 검증한 뒤 현재 매핑과
  변경 snapshot을 한 트랜잭션으로 저장한다. 실패하면 저장 0건이며 index/network/symbol/reason이 API→BFF→UI로 전달된다.
- Fireblocks 전체 vault와 BCM 계정을 읽기 전용 대조해 `MANAGED/UNMANAGED/MISSING_IN_FIREBLOCKS`와 전체 식별자를 표시한다.
- Contracts·Policies는 registry 목록뿐 아니라 활성 binding/evidence, sweep 실행 설정과 금지 사유를 함께 보여 준다.
  배포·서명은 별도 contracts release와 Fireblocks Security Admin Vault/TAP/DAW-ADMIN 승인 경계다.
- Sweep readiness는 BAT가 실제 활성 policy·contract·evidence·release와 실행 gate 순번/상태를 검증한 증명만 사용한다.
  STOP/RESUME 후에는 이전 증명을 재사용하지 않으며, 후보 없음도 같은 실행 문맥을 검증한 뒤 성공으로 기록한다.
- waas-wiki 06·07·08·10에 Security Admin Vault와 DAW-ADMIN 소유권을 반영했다. 고객 계정·출금·밴드S 계산처럼
  실제 DAW-CORE 책임은 유지하고 관리면만 구분했다.
- Flyway는 애플리케이션/lock에서 제거됐다. 빈 로컬 DB와 Testcontainers는 persistence manifest 순서로 SQL을 직접 적용한다.

## 검증 완료 (2026-08-24)
- `./scripts/ci.sh` 전체 통과(`CI 그린`): 모든 모듈·스크립트 안전성·production boundary·Admin Node 26건 포함.
- OpenAPI 생성물 paths 21/schemas 74 및 Admin Kotlin 타입 fresh, `git diff --check`·ktlint 통과.
- waas-wiki 설계 사본 byte 동일. 독립 design-sync와 Codex code-reviewer(93파일)는 Critical 0 / Major 0.

## 다음 작업
- Phase 15는 보류를 유지한다. 재개 시 PLAN T15.0 결정표부터 진행하고 실제 배포·Fireblocks 변경은 별도 승인한다.
- 공유 Admin/대규모 workspace 전에 PLAN #51 vault 전체 대사를 cursor paging·비동기 원장으로 전환한다.

## 외부 조건·후속
- Phase 15 전제는 Linux+systemd, 초기 API/Webhook/BAT 1/1/1. 실제 운영 배포는 기능 점검 후 별도 승인한다.
- 운영 DB SQL은 DBA가 직접 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.
- 공유 Admin mutation은 private listener, mTLS, 5분 이하 JWT 인증/인가 구현 전까지 닫는다.
- 실제 Fireblocks 변경 호출과 `manual-fireblocks` golden test는 명시 승인 경계를 유지한다.
