# 해결된 스펙·설계 항목

> `PLAN.md`에서 2026-08-21 분리한 완료 이력이다. 현재 열린 항목은 [PLAN.md](../../PLAN.md)를 따른다.

| # | 내용 | 상태 |
|---|---|---|
| 1 | **ChainEvent 에 이벤트 id 없음** — openapi.yaml 은 "txId 또는 externalTxId 유일 기준", 02-bcm-flow 는 컨슈머 dedup 키 = `evnt_id`(outbox UUID v7) | ✅ 해결 (2026-08-04) — `eventId` 필수 필드 추가, dedup 문구 정정, v0.0.3 재생성 |
| 2 | **poison 웹훅 격리 방식** | ✅ 해결 (2026-08-07) — `bcm_whk_l.prcs_stcd` P/S/F + `rtry_cnt` + `err_msg`. V1과 수신 초기값(P·0)을 반영, 워커는 Phase 4에서 구현 |
| 5 | **ChainEvent 에 금액·발신 주소 필드 없음** | ✅ 해결 (2026-08-07) — 스펙 v0.4.0에 문자열 `amount` 필수, nullable `from` 추가. 입금은 from을 항상 채운다 |
| 6 | **submitTransaction 멱등 재요청 응답 미정의** | ✅ 해결 (2026-08-07) — 스펙 v0.4.0 이 "같은 키+같은 내용 202 / 다른 내용 409" 를 확정하고, **v0.5.0 이 "같은 내용"의 범위**(자금 이동 7값 · `note`·`travelRule` 제외 · amount 는 금액 비교)와 무응답 시 재시도 안전·`external/{externalTxId}` 확인 경로를 문서화. 저장은 `bcm_sbmt_l` 제출 원장(03 신설), 복구 규칙은 02 출금 절 |
| 7a | **스펙 보완 — Phase 2 표면** — maxLength·asset 매핑·HOT_OPS 어휘·무인증 명시 | ✅ 해결 (2026-08-05) — **스펙 v0.0.5**: ref 64·externalTxId 128·accountId 64·asset 16(`tkn_smbl` 대응 명시) maxLength, HOT_OPS 어휘 제거(사용자 결정 — 추측 금지), 인증 절 신설(없음 — #12) |
| 7b | **스펙 보완 — Phase 4·5 표면** | ✅ 해결 (2026-08-07) — v0.4.0 에서 순서 보장(합성 발행)·허용 전이 표(subStatus·networkStatus 열 포함) 이벤트 절 반영, `tx_stcd` 매핑은 02 로 이관. **v0.5.0 에서 남은 둘 해소** — `transactionsOf` 의 `after` 를 `required: false` 로 내리고 "cursor 없는 첫 요청에만 필수" 명시(커서 요청이 무시될 값을 필수로 받던 모순 제거) · `submitTransaction` 에 `404` 추가(`from`·`to` 의 없는 `accountId`). FAILED 행 subStatus 는 대표값 + "그 외" 로 두고 전체 열거는 하지 않는다 — 벤더 열거가 닫힌 집합이라는 근거가 없다 |
| 8 | **가상 스레드 채택 여부** | ✅ 해결 (2026-08-06) — **가상 스레드 채택 · `StructuredTaskScope` 불채택**(preview, `--enable-preview` 금지). 조건 2가지 — 수신 동시성 명시 상한 · relay/워커는 병렬화 대상 아님. 규칙은 [.claude/rules/virtual-thread.md](.claude/rules/virtual-thread.md) 로 이동, CLAUDE.md 3절 기록 |
| 9 | **사내 Spring Boot BOM 채택 여부** — 기존 프로젝트는 Boot 4.0 기반, 4.0 은 OSS 지원 2026-12 종료 | ✅ 해결 (2026-08-05) — **Boot 4.1 유지·BOM 불채택** (사용자 결정). 사내 BOM 이 4.1.x 를 내면 재합류 검토 |
| 10 | **원문 바이트 보존 방식** | ✅ 해결 (2026-08-06) — **`bcm_whk_l.payload` JSONB → TEXT** + `payload_hash CHAR(64)`(수신 `byte[]` 의 SHA-256) + `sign_vl TEXT`(서명 헤더 원문). `bcm_raw_tx_l` 은 세 값을 복사만 하고 재계산하지 않는다. 03·99 개정 + 사본 동기화 완료. JSONB 유지 + `payload_raw` 병기 안은 원본이 둘이 돼 기각 |
| 11 | **sweep 목적지 과거 불일치** | ✅ 해결 (2026-08-05) — **옴니버스 vault**. CLAUDE.md 3절·Phase 6·waas-wiki 06 반영 및 사본 동기화 완료 (2026-08-10) |
| 12 | **서비스 간 인증** — 01 미확정, openapi.yaml 에 securitySchemes 없음 | ✅ 해결 (2026-08-05) — **인증 없음** (사용자 결정 — 내부망 경계 신뢰). openapi.yaml 에 무인증 명시는 차기 스펙 개정(#7a)에 포함 |
| 13 | **경보 채널 구체 수단** — 01 미확정 (막힘·귀속 불명은 별도 알림 채널). Phase 4·7 은 **포트(인터페이스) 추상화**로 진행 — 구체 수단(어느 메신저/알림 시스템)은 뒤에 바인딩 | ✅ 해결 (2026-08-17) — 배포 제품에 종속되지 않는 Bearer 인증 HTTP 운영 수신기로 바인딩하고 `route`·`type`으로 downstream 채널을 분리한다. 고객 데이터 토픽과 분리하고, 배포 환경별 실제 메신저 연결은 수신기 소관. 채널 장애는 원 처리를 롤백하지 않으며 실패 로그·메트릭을 남긴다 |
| 17 | **tx 갱신의 DB 레벨 방어 3종** | ✅ 해결 (2026-08-07) — `SELECT FOR UPDATE`, 컨펌 수·갱신 시각 `GREATEST`, 최초 탐지·감사 갱신 제외, PK/UNIQUE `ConflictException` 변환. 신규 tx 동시 경합은 트랜잭션 롤백 후 이긴 행을 잠가 재판정하며 PostgreSQL 동시 테스트로 고정 |
| 18 | **FINALIZED→REJECTED 전이가 02 표에 없음** | ✅ 해결 (2026-08-07) — 확정 후 동결이므로 발행·반영. 도메인 전이표와 30조합 계약 테스트 반영 |
| 19 | **`bcm_raw_tx_l` 파티션 생성 주체** — 부모만 생성돼 파티션 없인 INSERT 전부 실패. 배포 시 vs 보관 배치 시 결정 | ✅ 해결 (2026-08-13) — 대상 월 시작 전에 배포 역할이 월별 파티션을 선생성한다. 런타임 애플리케이션은 DDL 권한 없이 DML만 수행하고, 누락 시 보관·인박스 정리·성공 heartbeat를 함께 실패시킨다. waas-wiki `3b033ca`, 사본 `1788071` |
| 16 | **일시 `VARCHAR(16)` 의 값 포맷** | ✅ 해결 (2026-08-05) — **`yyyyMMddHHmmss` 14자** (사용자 확정 — 은행권 관례, 여유 2자). 변환은 support 유틸 단일 관리 — 코어 규약 확인 시 한 곳 조정. CLAUDE.md 3절 반영 |
| 20 | **(network, symbol) → 벤더 assetId 변환 표** | ✅ 해결 (2026-08-06) — 07-asset-master의 `bcm_vndr_ast_m`. 벤더 경계에서 DB 조회, 미등록은 `ASSET_NOT_SUPPORTED`; 실제 assetId 값은 Admin 조회·검증 후 등록 |
| 26 | **일시 14자 컬럼의 zone 규약 (KST vs UTC)** (T2.5 code-reviewer C1) | ✅ 해결 갱신 (2026-08-14) — **모든 DB `_dttm`·`_dt`·`base_dt`를 UTC로 통일.** DB 시각용 Clock 빈 2곳은 `blockchain-manager-application`(이를 `bcm-api`·`bcm-webhook`이 공유)과 `bcm-bat`의 `Clock.systemUTC()`이고 벤더 epoch도 공통 유틸에서 UTC로 변환한다. API는 ISO 8601 UTC(`Z`), 화면·정산·보고서에서만 필요한 시간대로 변환한다. 2026-08-06 KST 결정은 대체됐다. |
| 27 | **Admin API OpenAPI 계약 부재** | ✅ 해결 (2026-08-06) — 스펙 v0.2.0에 벤더 중립 Admin API 7개와 요청·응답 스키마 확정, 스펙 자동 대조 테스트 추가 |
| 32 | **Admin 일시 예시 자릿수** — OpenAPI `Network.syncedAt`·`AssetMapping.registeredAt` 예시는 12자인데 확정 규약과 실제 응답은 `yyyyMMddHHmmss` 14자다. | ✅ 해결 (2026-08-21) — 두 예시를 14자로 수정하고 생성물을 재생성했다. |
| 33 | **자산 후보 조회 fan-out과 벤더 rate limit** — Admin 검색마다 채택 네트워크별 자산을 끝까지 페이징했다. | ✅ 해결 (2026-08-24) — 모든 Fireblocks 네트워크 자산을 일 1회/명시 one-shot으로 PostgreSQL 읽기 전용 캐시에 원자 갱신하고, Admin은 prefix·FTS 인덱스에서 최대 50건만 찾는다. 자산 0건도 포함한 네트워크별 `READY/STALE/NEVER_SYNCED`를 반환하며 미지원 후보는 선택을 막고 등록 시에는 지원 Network와 Fireblocks 주소를 다시 검증한다. |
| 38 | **막힘 상태·RBF hash 보관 불일치** | ✅ 해결 (2026-08-12) — DB 상태는 후보 선별만 하고 조치 직전 벤더 단건 조회로 `CONFIRMING`·txHash·0 confirmation을 확인한다. `bcm_tx_l`은 root 한 행에 active tx id/hash를 보관하고, stuck 웹훅은 선택적 가속 신호일 뿐 correctness 기준으로 삼지 않는다. waas-wiki 02·03·99와 사본 동기화 완료 |
| 39 | **sweep CONTRACT_CALL canonical `cc-v1` 재계산 불가** | ✅ 해결 (2026-08-13) — `bcm_sbmt_l.call_data TEXT`에 정규화된 calldata를 저장하고 SWEEP_APPROVE/SWEEP_BATCH 존재·소문자 짝수바이트 hex를 DB와 코드에서 강제한다. 원장 왕복 후 cc-v1 hash 재계산 테스트로 고정. waas-wiki 03 `fe92927`, 사본 byte-동일·design-sync 통과 |
| 48 | **밴드S 외부 cold 일반 전송의 Gasless 근거 없음** | ✅ 보수적으로 해결 (2026-08-18) — `EXTERNAL_COLD`는 `useGasless=false`로 제출한다. 향후 켜려면 02·06 또는 벤더 QnA에서 지원 여부·수수료 부담을 먼저 확정한다. |
| 14 | **03 스키마 미확정 3건** — 약어 · 감사 센티넬 · subStatus 보관 | ✅ 해결 (2026-08-05, 사용자 위임으로 프로젝트 자체 확정) — ① 약어는 03 표기 그대로(`bcm`·`vndr`·`vlt`·`noti`·`swp`) = 프로젝트 약어집. DAW-CORE 약어집 등장 시 대조·조정 ② 센티넬 `empno='SYSTEM'` · `brcd='9999'` — 코드에선 단일 상수로 관리 ③ **subStatus·networkStatus 를 `bcm_tx_l` 에 보관**(사용자 결정 — 이벤트 미탑재는 유지). **반영 필요: waas-wiki 03 개정(컬럼 추가·미확정 절 정리) + 사본 동기화 — Phase 1 착수의 첫 선행 작업 (미실행)** |
