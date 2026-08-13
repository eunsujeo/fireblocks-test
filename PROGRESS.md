# PROGRESS — 세션 핸드오프

> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치

- **Phase 7 T7.5를 완료했다. 다음 작업은 T7.6 E2E + converge다.**
- 막힘 조치 직전 벤더를 다시 조회한 뒤, 네트워크별 기능 게이트·최대 시도 안에서 출금 RBF를 제출한다.
- `bcm_boost_l` REQUESTED intent와 `bst-` UUID v7 externalTxId·claim을 선커밋하고, TTL 만료 후는
  externalTxId 조회로 회수한 뒤 미발견일 때만 저장된 hash·fee·gasless·원 제출 자금 이동 필드로 재제출한다.
- boost SUBMITTED/new txId와 root active 전환은 한 트랜잭션에서 기록한다. 원 거래가 먼저 승자가 된 경합에서도
  벤더에 실존하는 `new_tx_id`는 롤백하지 않고 남긴다.
- V1 `bcm_boost_l`을 최신 03 스키마와 맞췄고 gasless_yn은 `VARCHAR(1)`이다. 배포 DB가 없어 V1을 제자리 수정했다.
- 자동 boost는 기본 비활성(`automatic-boost-enabled-networks: []`)이다. claim TTL은 벤더 회수+제출 최장시간보다
  길어야 기동하며 기본 180초다.
- Fireblocks 공식 문서상 CONTRACT_CALL RBF도 새 거래는 TRANSFER로 생성된다. 원 calldata 재실행 근거가 없어
  sweep approve·batch는 추가 실측/담당자 확답 전까지 intent 생성 전 경보-only다.
- 테스트 커밋 `e8a225e`, 구현 커밋 `aa16d1f`; `./gradlew check ktlintCheck --rerun-tasks` 전체 407건 그린이다.
- 첫 code-reviewer의 Critical 2건(root 경합 결과 유실·미확인 sweep CONTRACT_CALL RBF)과 Major 지적을 반영했다.
  재리뷰는 Claude 세션 한도로 실행되지 않았으며 T7.6 converge에서 다시 돌린다.

## 다음 작업

- T7.6에서 boost external/new txId로 대체 웹훅을 root에 접고, 고객 이벤트에 root txId/externalTxId만 내보낸다.
- 응답/웹훅 선도착·원/대체 거래 채굴 경합의 승자 txId/hash 복귀, active 아닌 drop 무시, 종결 최신 관찰 재흐림을 구현한다.
- 동시 실행·이미 교체됨·최대 시도 경보 PostgreSQL E2E 후 design-sync·code-reviewer를 다시 통과한다.

## 주의·외부 조건

- **2026-08-13 사용자가 현재 변경의 commit·push를 명시적으로 허용했다.**
- `docs/design/`은 AI 직접 수정 금지다. 기존 DB는 없다는 사용자 확인에 따라 V1을 직접 수정했고 V2는 만들지 않았다.
- 실연동 전 TAP approve 매칭·Callback·gasless, gas/M/network records 지연, 컨트랙트 감사와 전체 회수 훈련이 필요하다.
- `TXRJ`는 코어 회신 후 단일 enum 상수만 교체한다. 경보 채널은 PLAN #13 확정 전 logging adapter다.
