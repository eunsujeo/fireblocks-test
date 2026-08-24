# PROGRESS — 세션 핸드오프

> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치

- **Phase 10 T10.7 E2E + converge 완료. 다음은 Phase 11 T11.0 로컬 블록체인 + Fireblocks Stub 계약·설계 정본이다.**
- Admin Frontend+BFF는 DAW-CORE와 분리된 독립 기능 테스트 도구다. `bcm-admin`은 loopback FUNCTION_TEST에서만 시작하고
  브라우저는 `/bff/admin/*`만 호출한다. 공유 환경은 mTLS+5분 이하 단기 JWT 구현 전까지 fail-closed한다.
- 브라우저/BFF E2E는 키보드·스크린리더, 로컬 시각+UTC 원문, 중복 클릭, 장시간 단계·금지 사유, 민감정보 비포함과
  서버 판정 기반 네트워크 제한 출시를 검증한다.
- V9/V10은 sweep 실행에 활성 policy·contract·evidence snapshot을 NOT NULL로 고정하고 생성·`READY→SUBMITTING` 때
  gate, binding, 최신 VALID evidence, item/batch cap을 같은 advisory/row lock 순서로 재검사한다.
- SWEEP STOP은 신규 READY batch·target claim을 막고 기존 SUBMITTING 회수는 허용한다. 출금 STOP은 신규 요청과 FAILED
  재시도만 막고 REQUESTED/SUBMITTED 회수는 유지하며, 모든 WITHDRAWAL claim은 `gate→submission row` 순서를 사용한다.
- APPROVE STOP 중 기존 관찰·동일 ID 회수와 비상 `approve(0)`은 열고 신규 확대는 막는다. 강화 재개는 요청자 외 2명 중
  보안 1명, 최신 외부 check·증적, allowance 회수 완료를 DB가 다시 확인한 뒤에만 열린다.
- allowance 회수·웹훅 복구·강화 재개 append-only 원장과 Admin 비상 읽기 화면은 승인 문맥, 호출 intent/결과,
  최신 관찰값·시각·오류·금지 사유를 보존한다. 인증 경계 전 브라우저 mutation route는 없다.
- OpenAPI paths 18/schemas 64, 부트스트랩 39개 테이블, JUnit 689건+프론트 13건(실패·오류·skip 0), CI 116 task,
  `ktlintCheck`, OpenAPI/Admin 생성물 freshness와 `git diff --check`가 통과했다.
- `docs/design` 17개는 clean wiki 정본 `3d4c8db`와 byte 동일하다. Dependency-Check의 기존
  `kafka-clients 4.2.1 / CVE-2026-41115` 1건 외 새 경고는 없다.
- Codex GPT-5 reviewer agent(상속 reasoning effort)가 `1ec44fa..b1e5719`을 순차 검토했다. design-sync와
  code-reviewer 모두 검토 commit `b1e5719`에서 Critical 0/Major 0이며 code-reviewer Minor 0이다.

## 다음 작업

- T11.0에서 BCM이 실제 사용하는 Fireblocks Vault/Asset/Transaction/Fee/Webhook 계약과 실제·Stub·미지원 경계를 표로 고정한다.
- waas-wiki에 로컬 통합환경 설계를 먼저 작성하고, 배포 조합·키 경계·reset 소유권·실 Fireblocks 계약 테스트 승인 경계를
  사용자 확인 후 `docs/design/` 사본으로 동기화한다. 서비스 사본은 직접 편집하지 않는다.

## 리뷰 후속·외부 조건

- 공유 Admin mutation 공개는 mTLS+단기 JWT 인증/인가 구현 뒤에만 가능하다.
- 계열 승자의 cnfm>0 뒤 reorg, FAILED boost 뒤 늦은 웹훅과 COMPLETED hash는 설계 판단·벤더 실측이 필요하다.
- `bcm_job_m.markSucceeded` 다중 인스턴스, pending 실패 격리, 대사 제외 ID·보관 scan은 PLAN #44·#45 후속이다.
- Kotlin 2.4.20 GA·Boot 4.1 호환 확인 후 PLAN #41 suppression 제거와 build cache 재활성화가 필요하다.
