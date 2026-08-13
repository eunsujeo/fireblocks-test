# PROGRESS — 세션 핸드오프

> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치

- **Phase 6 T6.11 구현·로컬 검증까지 완료했다. 체크박스와 Phase 완료는 Claude Code의 design-sync·code-reviewer converge 뒤다.**
- 설계 정본은 waas-wiki `6801113`; 02·03·06·93~95·98 사본은 현재 byte-동일하다.
- 고객 vault별 제한 allowance, 운영 계정 batch CONTRACT_CALL, `SweepExecution 1:N SweepItem`, network records +
  receipt `SweepLeg` 항목 대사를 채택했다. approve와 batch는 `SWEEP_APPROVE`·`SWEEP_BATCH` 제출 원장으로 분리된다.
- allowance는 온체인이 정본이다. 0이면 cap approve 후 재관측까지 APPROVING이며, cap 변경은 active item 없음 →
  approve(0) → 온체인 0 → 새 cap 순서다. 긴급 회수는 별도 게이트에서 REVOKING→REVOKED를 추적한다.
- batch는 ACTIVE·현재 컨트랙트·충분 allowance를 잠금 재검증하고 실행·N개 항목·N개 target claim을 원자 생성한다.
  운영 계정별 READY/SUBMITTING은 하나이며 중단·relay 거절 뒤에도 같은 execution/external id를 재사용한다.
- calldata는 UUID v7 bytes16, token, 주소순 owner/최소단위 금액이다. `batch-v1` canonical hash로 실행 의도를 고정한다.
- 최상위 batch 종결/network records 완료는 RECONCILING 진입 조건뿐이다. 벤더 COMPLETED, 원천 vault별 records,
  receipt `SweepLeg`가 실행·순번·원천·요청/실제금액·실패코드까지 맞아야 COMPLETED/PARTIAL로 종결한다.
- 성공 target은 잔액 최소 미만이고 신규 확정 입금이 없을 때만 삭제한다. 실패·잔존은 claim을 해제하며,
  누락·중복·불일치는 claim을 보존하고 경보한다. sweep은 고객 토픽에 발행하지 않는다.
- 정상 승인·긴급 회수·batch/TAP/Callback/Universal Gasless/컨트랙트 검증 게이트 9종은 배포 기본 false다.
- T6.11 PostgreSQL/Flyway E2E는 vault 2개의 approve 선기록→cap 재관측→batch 단일 제출→1 성공·1 실패 대사→
  전부 approve(0)·온체인 0 재관측을 통과했다. 중복 batch 무제출, gasless, outbox 0건도 함께 검증했다.
- Callback 검증 플래그가 false면 approve·batch·긴급 회수 세 경로가 벤더 호출 전에 fail-closed임을 고정했다.
- T6.11 테스트 커밋은 `ed3faef`다.
- `./gradlew check ktlintCheck` 전체 379건 그린, OpenAPI 생성물 신선도를 확인했다.
- 기존 Phase 7 T7.0~T7.4 구현은 보존돼 있다. Phase 6 converge 전 T7.5 RBF 제출은 뒤로 둔다.

## 다음 작업

- Claude Code에서 Phase 6 design-sync와 code-reviewer를 실행하고 발견 사항을 반영한다.
- 둘 다 통과하면 PLAN의 T6.11·Phase 6 체크박스와 이 파일을 갱신한다.

## 주의·외부 조건

- **2026-08-13 사용자가 현재 변경의 commit·push를 명시적으로 허용했다.**
- `docs/design/`은 직접 수정 금지다. 기존 DB는 없다는 사용자 확인에 따라 V1을 직접 수정했고 V2는 만들지 않았다.
- 실연동 전 TAP approve 매칭·Callback·gasless, gas/M/network records 지연, 컨트랙트 감사와 전체 회수 훈련이 필요하다.
- 새 원격 `fireblocks-test`는 빈 저장소다. Git 백업 `../blockchain-manager-svc.git-backup-20260810-before-reinit`은 삭제 금지다.
- `TXRJ`는 코어 회신 후 단일 enum 상수만 교체한다. 경보 채널은 PLAN #13 확정 전 logging adapter다.
