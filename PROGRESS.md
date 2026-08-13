# PROGRESS — 세션 핸드오프

> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치

- **Phase 8 T8.1 종결 거래 대사까지 완료했다. 다음 작업은 T8.2 확정 원본 보관이다.**
- `tx-reconciliation`은 기본 비활성 10분 주기다. workspace `GET /v1/transactions`를 source/order 없이
  최대 500건씩 페이징하고, `bcm_job_m.last_scs_dttm` 경계를 1ms 겹쳐 createdAt 창을 이어 붙인다.
- 벤더 원어 COMPLETED·FAILED·vault 발신 REJECTED/BLOCKED만 root 거래와 비교해 일치·vendor-only·
  manager-only·status mismatch를 로깅 리포트한다. 종결 상태 불일치는 자동 정정하지 않는다.
- 목록 창 밖의 오래된 `SUBMITTED`·`CONFIRMED`는 active 물리 tx 단건 조회로 확인한다. 종결이 확인되면 기존
  상태기계+outbox 트랜잭션을 재사용해 입금을 복구하고, SWEEP_BATCH는 실행 원장을 RECONCILING으로 옮긴다.
- RBF 계열은 성공 증거를 우선하고 active가 아닌 지연 실패를 제외한다. 반복 벤더 cursor나 처리 예외가 나면
  성공 heartbeat를 전진시키지 않는다.
- `./gradlew check ktlintCheck --rerun-tasks` 전체 440건 그린. 설계 사본은 waas-wiki와 byte 동일하다.
  Phase converge용 Claude agent 실행은 세션 한도(20:30 KST 재설정)로 결과가 없었으며 Phase 8 종료 때 재실행한다.

## 다음 작업

- T8.2 착수 전에 PLAN #19 `bcm_raw_tx_l` 일자 파티션 생성 주체를 확정한다. 현 V1은 부모만 있어 파티션 없이는
  INSERT가 실패한다. 배포 시 선생성 또는 보관 배치의 안전한 생성 중 하나를 설계 정본에 먼저 반영해야 한다.
- 확정 원본 보관은 마지막 COMPLETED 웹훅의 payload와 수신 시 계산한 payload_hash를 그대로 옮기고,
  이관 성공 뒤에만 처리된 `bcm_whk_l` 보존 기간 정리를 수행하는 PostgreSQL 테스트부터 작성한다.

## 리뷰 후속·외부 조건

- 계열 승자가 cnfm>0 뒤 reorg로 뒤바뀌는 경우는 confirmation 감소 금지와 충돌하므로 설계 판단이 필요하다.
- FAILED boost 뒤 늦은 웹훅의 벤더 생성 가능성과 COMPLETED hash 보장은 실측·QnA가 필요하다.
  boost persistence 경합 분기 직접 테스트도 보강 후보다.
- stall stale 시간이 boost claim TTL보다 길다는 설정 불변식과 EVM 네트워크 판별 하드코딩은 후속 개선 후보다.
- `docs/design/`은 AI 직접 수정 금지다. 실연동 전 TAP·Callback·gasless, 컨트랙트 감사와 회수 훈련이 필요하다.
- `TXRJ`는 코어 회신 후 단일 enum 상수만 교체한다. 경보 채널은 PLAN #13 확정 전 logging adapter다.
