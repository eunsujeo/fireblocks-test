# PROGRESS — 세션 핸드오프

> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치

- **Phase 8 T8.1~T8.3 구현 완료, T8.4 converge의 외부 리뷰만 남았다.**
- tx 대사는 벤더 `createdAt` 안정화 창과 `bcm_tx_l.vndr_crt_dttm`을 UTC로 비교한다. 초 단위 경계를
  1ms/999ms 보정하고, 종결 번역은 벤더 translator, 비교·복구 판단은 domain 정책에 모았다.
- 창 밖 `SUBMITTED`·`CONFIRMED`는 DB 원자 claim, 30초/1분/5분/15분/1시간 백오프, 실행당 100건,
  최대 7일 추적으로 단건 조회한다. 새 관찰이 실제 적용되면 대사 체크포인트를 초기화한다.
- 원본 보관은 커서 하한 없이 미보관 COMPLETED 원문을 재탐색한다. 500건×최대 20배치를 각각 커밋하고,
  적체를 비운 실행만 정리+성공 heartbeat를 별도 커밋한다. 미보관 COMPLETED 원문은 root 상태와 무관하게 보존한다.
- 수수료 견적은 등록 자산별 LOW/MEDIUM/HIGH를 같은 관측 시각으로 저장하며 일반 제출 MEDIUM,
  boost는 저장 fee level의 제출시각 이하 최근 견적을 대응한다.
- 2026-08-14 확정으로 모든 DB `_dttm`·`_dt`·`base_dt`를 UTC로 통일했다. Clock 빈 2곳은
  `Clock.systemUTC()`, 벤더 epoch와 대사 API 경계도 공통 UTC 유틸을 쓴다.
- `docs/design/02-bcm-flow.md`·`03-bcm-db.md`·`98-batch-sweep.md`는 현재 waas-wiki와 byte 동일하다.
- `./gradlew check ktlintCheck --rerun-tasks` 전체 468건 그린.

## 다음 작업

- 2026-08-14 code-reviewer와 design-sync를 최종 UTC 상태로 실행했으나 둘 다 Claude 세션 한도로 종료됐다.
  **14:50 KST 제한 해제 후** 새 순차·세션 재개 스크립트로 먼저
  `./scripts/converge-review.sh design-sync d5610fc`, 통과 뒤 `code-reviewer`를 실행하고 지적을 반영한다.
- 두 리뷰가 통과하면 PLAN T8.4와 Phase 8 체크박스를 닫고 PROGRESS를 완료 상태로 갱신한다.

## 리뷰 후속·외부 조건

- 계열 승자가 cnfm>0 뒤 reorg로 뒤바뀌는 경우는 confirmation 감소 금지와 충돌하므로 설계 판단이 필요하다.
- FAILED boost 뒤 늦은 웹훅의 벤더 생성 가능성과 COMPLETED hash 보장은 실측·QnA가 필요하다.
- `bcm_job_m.markSucceeded` 다중 인스턴스 회귀 방지, pending 한 건 실패 격리, N+1/보관 scan 개선은 Phase 9 후보다.
- `docs/design/`은 AI 직접 수정 금지다. 실연동 전 TAP·Callback·gasless, 컨트랙트 감사와 회수 훈련이 필요하다.
- `TXRJ`는 코어 회신 후 단일 enum 상수만 교체한다. 경보 채널은 PLAN #13 확정 전 logging adapter다.
