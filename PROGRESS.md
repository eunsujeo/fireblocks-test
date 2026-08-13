# PROGRESS — 세션 핸드오프

> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치

- **Phase 6 approve + transferFrom 배치의 T6.10 항목별 결과 대사까지 완료했다. 다음 구현은 T6.11 E2E·출시 게이트·converge다.**
- 설계 정본은 waas-wiki 커밋 `6801113`이다. 고객 vault별 제한 allowance, 운영 계정 batch CONTRACT_CALL,
  `SweepExecution 1:N SweepItem`, network records + receipt `SweepLeg` 항목 대사를 채택했다.
- V1을 직접 수정해 `bcm_swp_auth_m`·`bcm_swp_exec_l`·`bcm_swp_item_l`, target item FK를 추가했다.
  기존 DB는 없다는 사용자 확인에 따라 V2는 만들지 않았다.
- `bcm_sbmt_l.tx_dvcd`는 WITHDRAWAL/INTERNAL/SWEEP_APPROVE/SWEEP_BATCH이고 batch 원장은 `swp_exec_id`를 보관한다.
- 실행·N개 항목·N개 target claim은 한 DB 트랜잭션이다. 한 target이라도 이미 claim이면 전부 롤백된다.
- allowance는 `(accountId, network, symbol, sweepContractAddress)`별 cap·마지막 온체인 관찰·승인 상태를 보관한다.
  온체인 값이 정본이며 ACTIVE 상태만 믿고 batch에 넣으면 안 된다.
- EVM JSON-RPC로 `allowance`·`decimals`를 읽고 `approve` calldata를 ABI 인코딩한다. approve는 가스 자산 CONTRACT_CALL +
  Universal Gasless 요청이며 `SWEEP_APPROVE`·`cc-v1` canonical hash·claim·400 후 externalTxId 조회 복구를 적용한다.
- allowance 0은 cap 승인 후 온체인 재관측 전까지 APPROVING이다. 기존 allowance·cap 변경은 active item이 없을 때
  approve(0)→온체인 0→새 cap 순서이며, 긴급 회수는 별도 게이트에서 REVOKING→REVOKED를 추적한다.
- 정상 승인·긴급 회수·TAP 두 정책·Callback·Universal Gasless 게이트는 application.yaml에서도 모두 false가 기본이다.
- 최상위 SWEEP_APPROVE/SWEEP_BATCH 웹훅은 고객 이벤트를 발행하지 않고 target도 해제하지 않는다.
  batch 종결은 RECONCILING 진입 조건일 뿐이며 항목 대사 전 성공 처리를 금지한다.
- `transaction.network_records.processing_completed`도 실행을 RECONCILING으로 옮긴다. 배치 정산기는 벤더 거래가 COMPLETED이고
  source vault별 network records와 receipt `SweepLeg` N개가 실행·순번·원천·요청/실제금액·실패코드까지 맞을 때만 종결한다.
- 실행은 전부 성공이면 COMPLETED, 일부 실패면 PARTIAL이다. 성공 target은 잔액이 최소 미만이고 잔액 관찰 사이 신규 확정 입금이
  없을 때만 삭제하며, 실패·잔존 target은 claim을 해제한다. 누락·중복·불일치는 claim을 보존하고 경보한다.
- 운영 ABI는 `batchSweep(bytes16,address,(address,uint256)[])`, event는 실행·순번·owner가 indexed인 `SweepLeg`다.
  `batch-v1`은 network·symbol·token/sweeper·UUID v7·주소순 항목을 LF로 canonicalize하고 SHA-256한다.
- 배치 실행·N개 항목·N개 target claim은 allowance 행을 accountId순으로 잠가 ACTIVE·현재 컨트랙트·충분 금액을 재검증한 뒤 원자 생성한다.
  운영 계정별 READY/SUBMITTING은 DB 부분 UNIQUE로 하나뿐이며 중단·relay 거절 뒤에도 같은 execution/external id를 재사용한다.
- calldata는 UUID 원문 bytes16, token, 주소순 owner/토큰 최소단위 금액 tuple 배열이며 잘못된 UUID·순서·정밀도는 RPC/제출 전에 거절한다.
- batch/TAP/Callback/gasless/컨트랙트 검증 게이트는 모두 false 기본이고 전부 열린 경우에만 후보 조회·제출한다.
- 변경 전 건별 `SweepSubmissionService`·스케줄러·건별 E2E는 제거했다. 후보 잔액 선별 코드는 다음 batch 준비에서 재사용한다.
- PostgreSQL/Flyway·웹훅·Kafka와 allowance/contract-call·부분 성공 대사를 포함한 전체 `./gradlew check ktlintCheck`가 성공했다.
- 기존 Phase 7 T7.0~T7.4 구현은 보존되어 있다. Phase 6 batch 완료 전 T7.5 RBF 제출은 잠시 뒤로 둔다.

## 다음 구현

- T6.11: approve 준비→온체인 재확인→batch 선기록/제출→부분 성공 대사의 PostgreSQL E2E를 완성한다.
- Callback 불일치·중복 실행·전체 `approve(0)` 회수·고객 토픽 무발행과 모든 출시 게이트 fail-closed를 고정한 뒤 converge한다.

## 주의·남은 외부 조건

- **2026-08-13 사용자가 현재 변경의 커밋·push를 명시적으로 허용했다.**
- Phase 6·7 변경은 설계 `dc84ff1`, 구현 `0109e17`, 테스트 `df31b7d`와 이 인수인계 갱신 커밋으로 분리했다.
- `docs/design/`은 직접 수정 금지다. waas-wiki 커밋 `6801113`의 03·06·95 사본은 사용자 복사 후 byte-동일 확인했다.
- 이전 V1 적용 DB가 생겼다면 Flyway repair가 아니라 드롭 후 재생성한다.
- 새 원격 `fireblocks-test`는 빈 저장소다. 이전 Git 메타데이터 백업 `../blockchain-manager-svc.git-backup-20260810-before-reinit`은 사용자 확인 없이 삭제하지 않는다.
- `TXRJ`는 코어 회신 후 단일 enum 상수만 교체한다. 운영 경보 채널은 PLAN #13 확정 전 포트 뒤 logging adapter다.
- 실연동 전 외부 확인은 #34~#37이고, batch 출시는 TAP/Callback/gasless, gas·M·network records 지연, 컨트랙트 감사와 전체 approve(0) 회수 훈련 뒤다.
