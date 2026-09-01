# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~27 완료. Phase 15는 계속 보류.**
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 구현 — T14.27 계정·주소 생성 결과 회수
- V20 `bcm_acnt_crtn_l`·`bcm_addr_crtn_l`은 벤더 호출 전 생성 의도, 현재 멱등 키 세대·마지막 POST 준비 시각, vault 이름·assetId snapshot을 보관한다.
- 첫 호출은 Fireblocks 생성, 미완료 재시도는 exact vault 이름·wallet 주소를 전 page 조회해 유일 후보를 회수한다.
- 후보 없음은 남은 키 창이 최장 호출시간 전체를 수용할 때만 현재 키를 재사용하고, 마지막 POST 준비 + 최장 호출시간 + 24시간 + 정밀도 여유 1초의 안전시각 뒤 최신 시도만 CAS로 새 키를 준비한다.
- 호출 상한은 5분 이하로 제한하고 완료도 키 세대를 행 잠금 아래 검사한다. 옛 호출 결과는 새 세대 매핑을 선점할 수 없다.
- cooldown은 영구 `CONFLICT`와 분리한 `CREATION_RETRY_LATER`·올림한 재시도 초로 응답한다. 준비 시각은 실제 호출 증거가 아닌 보수적 상한이다.
- 복수 후보·cursor 반복은 계정 HTTP 409·주소별 `CONFLICT`로 격리한다. 벤더 결과와 공개 매핑은 원자 완료한다.
- 다른 요청이 먼저 완료한 경합도 404로 오인하지 않고 완료 매핑으로 수렴한다. PLAN 미해결 #24·#25를 해결 이력으로 옮겼다.
- Fireblocks 실제 mutation은 실행하지 않았으며 Stateful Stub·MockWebServer·PostgreSQL로만 검증했다.
- Spring Boot 4.1.1(Framework 7.0.9)로 올려 CVE-2026-59313/59314를 해소했고 lockfile 11개를 동기화했다.

## 검증 완료 (2026-09-01)
- 실패 우선 회귀 테스트로 벤더 성공 뒤 DB 실패, 24시간 경계의 키 회전·재시도 시각, 복수 후보/cursor 반복, assetId snapshot을 고정했다.
- 계정·주소 완료 세대 경합, 만료 직전 재사용 금지·호출 상한·24시간 경계, 응답 assetId 불일치 테스트는 보정 전 실패를 확인했고 targeted test가 통과했다.
- waas-wiki 정본 02·03·10·QnA와 서비스 사본을 byte 동일하게 동기화했다.
- 독립 design-sync는 Critical/General/Minor 0건, code-reviewer는 Critical/Minor 0건으로 커밋 가능 판정했다.
- 전체 `./scripts/ci.sh`가 그린이다. Dependency-Check는 기존 PLAN #42 Kafka Medium 1건만 보고하고 게이트를 통과했다.

## 다음 작업
- 비차단 테스트 보강 후보는 실제 PostgreSQL latch 동시성 및 공개 매핑 insert 뒤 강제 fault rollback 검증이다.
- 열린 설계 항목은 PLAN 표를 기준으로 외부 조건이 추가로 확정된 항목부터 진행한다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.
