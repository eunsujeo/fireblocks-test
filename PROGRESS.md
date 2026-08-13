# PROGRESS — 세션 핸드오프

> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치

- **Phase 7 T7.6까지 완료했다. 다음 작업은 Phase 8 첫 항목인 tx 대사다.**
- RBF 대체 거래의 웹훅·단건/목록 조회·고객 이벤트를 최초 root `txId`·`externalTxId`로 접는다.
  `txHash`는 confirmation 또는 COMPLETED 성공 증거가 있는 실제 승자 물리 거래 값을 쓴다.
- active 아닌 지연 실패는 무시한다. 미결 boost가 있으면 active FAILED를 유예하고, 막힘 점검이 원+대체 계열을
  전부 재조회해 성공을 우선 채택하며 전원 FAILED일 때만 root 실패와 TXFL outbox를 같은 트랜잭션에 기록한다.
- FAILED 유예 시 `stall_alrt_dttm`을 비우고 관찰 시각을 전진시켜 stale 뒤 재점검한다. 종결 root의 REQUESTED
  boost가 externalTxId 조회에도 없으면 실패로 추측하지 않고 예외로 올려 job 경고·heartbeat 실패로 가시화한다.
- sweep approve·batch RBF는 CONTRACT_CALL 원 calldata 재실행 근거가 없어 실측/담당자 확답 전까지 intent 전
  경보-only다. waas-wiki 02(`a2d7f6e`)와 설계 사본(`30c68f3`), OpenAPI·생성물을 동기화했다.
- `./gradlew check ktlintCheck --rerun-tasks` 전체 430건 그린. 최종 design-sync는 15개 사본 byte-identical,
  code-reviewer는 Critical/Major 회귀 없음으로 통과했다.
- 테스트·구현은 red/green 별도 커밋으로 보완했다. 기존 종결 테스트를 구현 커밋 `f9f4cc7`에서 설계에 맞게
  바꾼 절차상 예외는 리뷰에서 지적됐으며, assertion 약화가 아닌 02 종결 정상 경로 정렬이었음을 기록한다.

## 다음 작업

- Phase 8 tx 대사: Fireblocks `GET /v1/transactions`를 `after=createdAt`, `orderBy` 미지정으로 페이징하고
  `bcm_job_m` 마지막 성공 커서 이후 종결 원어(COMPLETED·FAILED·출금 REJECTED·BLOCKED)만 `bcm_tx_l`과 비교한다.
- 일치·벤더에만 있음·우리에게만 있음·상태 불일치와 CONFIRMED 웹훅 유실 복구를 단위/통합 테스트로 먼저 고정한다.
  자동 정정은 하지 않고 불일치 리포트만 낸다.

## 리뷰 후속·외부 조건

- 계열 승자가 cnfm>0 뒤 reorg로 뒤바뀌는 경우는 confirmation 감소 금지와 충돌하므로 설계 판단이 필요하다.
- FAILED boost 뒤 늦은 웹훅의 벤더 생성 가능성, COMPLETED hash 보장, sweep 종결 재관찰의 reconciling 진입은
  실측·QnA 또는 Phase 8 회수 경로를 확인한다. boost persistence 경합 분기 직접 테스트도 보강 후보다.
- stall stale 시간이 boost claim TTL보다 길다는 설정 불변식과 EVM 네트워크 판별 하드코딩은 후속 개선 후보다.
- `docs/design/`은 AI 직접 수정 금지다. 실연동 전 TAP·Callback·gasless, 컨트랙트 감사와 회수 훈련이 필요하다.
- `TXRJ`는 코어 회신 후 단일 enum 상수만 교체한다. 경보 채널은 PLAN #13 확정 전 logging adapter다.
