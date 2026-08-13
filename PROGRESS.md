# PROGRESS — 세션 핸드오프

> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치

- **Phase 8 T8.2 확정 원본 보관까지 완료했다. 다음 작업은 T8.3 수수료 견적 시계열이다.**
- `tx-reconciliation`은 기본 비활성 10분 주기다. workspace `GET /v1/transactions`를 source/order 없이
  최대 500건씩 페이징하고, `bcm_job_m.last_scs_dttm` 경계를 1ms 겹쳐 createdAt 창을 이어 붙인다.
- 벤더 원어 COMPLETED·FAILED·vault 발신 REJECTED/BLOCKED만 root 거래와 비교해 일치·vendor-only·
  manager-only·status mismatch를 로깅 리포트한다. 종결 상태 불일치는 자동 정정하지 않는다.
- 목록 창 밖의 오래된 `SUBMITTED`·`CONFIRMED`는 active 물리 tx 단건 조회로 확인한다. 종결이 확인되면 기존
  상태기계+outbox 트랜잭션을 재사용해 입금을 복구하고, SWEEP_BATCH는 실행 원장을 RECONCILING으로 옮긴다.
- RBF 계열은 성공 증거를 우선하고 active가 아닌 지연 실패를 제외한다. 반복 벤더 cursor나 처리 예외가 나면
  성공 heartbeat를 전진시키지 않는다.
- `raw-transaction-archive`는 기본 비활성 일 배치다. 활성화할 때 양의 `retentionDays` 운영 설정이 필수다.
  성공 커서 경계를 포함해 마지막 COMPLETED 원문·수신 해시·서명을 그대로 월 파티션에 이관한다.
- 월별 파티션은 배포 역할이 `db/operations/create_bcm_raw_tx_partitions.sql`로 대상 월 전에 선생성한다.
  런타임은 DML만 수행하며 파티션 누락·정리 실패 시 적재·인박스 삭제·성공 heartbeat가 모두 롤백된다.
- 처리 완료(S) 인박스만 운영 보존일 뒤 정리한다. P/F와 아직 보관되지 않은 FINALIZED COMPLETED 원문은 보존한다.
- `./gradlew check ktlintCheck --rerun-tasks` 전체 449건 그린. 02·03 설계 사본은 waas-wiki `3b033ca`와 byte 동일하다.
  Phase converge용 Claude agent는 Phase 8 종료 때 재실행한다.

## 다음 작업

- T8.3 수수료 견적 시계열은 02에 동작만 있고 저장 테이블·견적 벤더 API·제출 시각 대응 키가 아직 없다.
  구현 전에 waas-wiki 02·03에서 저장 모델과 수집/대응 계약을 확정하고 사본을 동기화한다.

## 리뷰 후속·외부 조건

- 계열 승자가 cnfm>0 뒤 reorg로 뒤바뀌는 경우는 confirmation 감소 금지와 충돌하므로 설계 판단이 필요하다.
- FAILED boost 뒤 늦은 웹훅의 벤더 생성 가능성과 COMPLETED hash 보장은 실측·QnA가 필요하다.
  boost persistence 경합 분기 직접 테스트도 보강 후보다.
- stall stale 시간이 boost claim TTL보다 길다는 설정 불변식과 EVM 네트워크 판별 하드코딩은 후속 개선 후보다.
- `docs/design/`은 AI 직접 수정 금지다. 실연동 전 TAP·Callback·gasless, 컨트랙트 감사와 회수 훈련이 필요하다.
- `TXRJ`는 코어 회신 후 단일 enum 상수만 교체한다. 경보 채널은 PLAN #13 확정 전 logging adapter다.
