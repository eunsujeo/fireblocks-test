# blockchain-manager 구현 로드맵

> 설계 문서는 [docs/design/](docs/design/) 사본 (정본은 waas-wiki), HTTP API 계약은 [docs/api/openapi.yaml](docs/api/openapi.yaml) (그대로 구현).
> 각 Phase 는 **세로줄(동작하는 얇은 경로)** 단위 — 층별로 넓게 깔지 않는다.

## 현재 위치

- [x] Phase 0 — 프로젝트 스캐폴드 (2026-08-04)
- [x] Phase 1 — 도메인 + DB (2026-08-05)
- [x] Phase 2 — API 공통 규약 + 계정·주소·잔액 (2026-08-05)
- [x] Phase 3 — 웹훅 수신 (2026-08-06)
- [x] Phase 4 — 판단 워커 + outbox + relay (입금 E2E) (2026-08-07)
- [x] Phase 5 — 출금·내부이체 (출금 E2E) (2026-08-10)
- [x] Phase 6 — sweep (approve + transferFrom 배치로 재개) (2026-08-13)
- [x] Phase 7 — 막힘 점검 · 자동 boost (2026-08-13)
- [x] Phase 8 — 배치 3종 — tx 대사 · 원본 보관 · 수수료 시계열 (2026-08-14)
- [x] Phase 9 — 운영 보강 (2026-08-17)
- [ ] Phase 10 — Blockchain Manager Admin

## 작업 규칙 (모든 Phase 공통)

- **task 층** — Phase 착수 시 첫 작업은 그 Phase 를 체크박스 task 로 분해하는 것이다. task 마다
  완료 기준(어떤 테스트가 통과하면 done)과 근거 설계 절을 붙인다. 분해 결과는 이 문서의 해당 Phase 아래에 둔다.
- **세션 단위** — 1 세션 = task 1~2개 = 리뷰 가능한 diff 1개. 세션이 끝나면 [PROGRESS.md](PROGRESS.md) 갱신.
- **Phase 마무리(converge)** — 완료 기준 통과 후 ① 독립 읽기 전용 design-sync ② 지적 반영 후 독립 code-reviewer를
  **순차 실행** ③ 체크박스와 PROGRESS 갱신. Claude Code는 `./scripts/converge-review.sh <agent> <base>`로 재개하고,
  Codex는 동일한 `.claude/agents/` 체크리스트를 읽은 별도 reviewer agent/session으로 대체할 수 있다. 구현 세션의 자기 승인은
  금지하며 마지막 성공 리뷰의 도구·범위·commit을 PROGRESS에 기록한다. 셋이 끝나야 Phase 종료다.

## 테이블 ↔ Phase 대응 (고아 테이블 방지)

| 테이블 | 만드는 곳 | 쓰는 코드가 생기는 곳 |
|---|---|---|
| `bcm_acnt_m` · `bcm_addr_m` | Phase 1 | Phase 2 (발급) · Phase 4 (귀속) |
| `bcm_whk_l` | Phase 1 | Phase 3 (적재) · Phase 4 (집기) |
| `bcm_tx_l` · `bcm_outbox_l` | Phase 1 | Phase 4 |
| `bcm_swp_trgt` | Phase 1 | Phase 6 |
| `bcm_swp_auth_m` | **Phase 6 배치 전환** | Phase 6 (allowance 관찰·approve·긴급 회수) |
| `bcm_swp_exec_l` · `bcm_swp_item_l` | **Phase 6 배치 전환** | Phase 6 (최상위 실행 1:N 항목·대사) |
| `bcm_boost_l` | Phase 1 | Phase 7 |
| `bcm_job_m` | Phase 1 | Phase 7 (막힘 점검 주기) · Phase 8 (대사 커서·heartbeat) |
| `bcm_raw_tx_l` | Phase 1 | Phase 8 (일 배치 보관) |
| `bcm_fee_qt_l` | **Phase 8** | Phase 8 (자산별 네트워크 수수료 견적 시계열 · 제출 시각 대응) |
| `bcm_sbmt_l` | **Phase 5** (03 신설 — 2026-08-07) | Phase 5 (멱등·분류) · Phase 6 (sweep 제출) · Phase 7 (미결 점검) |

## Phase 0 — 프로젝트 스캐폴드

Gradle 멀티모듈 골격 + 빌드·테스트 파이프라인. 코드보다 **개발 루프**를 먼저 세운다.

- rootProject.name = `blockchain-manager`, base 패키지 `com.whatto.bcm`, 모듈 구조는 CLAUDE.md 4절
- version catalog — Boot 4.1.x · Kotlin 2.3.x · JDK 25 ([docs/tooling.md](docs/tooling.md) 확정값)
- Flyway + Testcontainers(PostgreSQL) 가 도는 빈 통합 테스트 1개 — 신품 메이저 조합(Boot 4.1·JUnit 6·Testcontainers 2.0·Batch 6·Jackson 3)의 호환 리스크를 여기서 조기 검증
- ArchUnit 모듈 의존성 테스트 — core API 를 일반 `@Test` 로 (junit6 엔진 미출시)
- lint/format (ktlint — hook 과 동일 도구) + CI 스크립트
- Gradle **dependency locking** (`gradle.lockfile` 커밋) — 의존성 통제의 기반
- CI 에 docs/api **생성물 신선도 체크** — `python3 build.py` 재생성 후 diff 없음 (spec.js·api.md 가 openapi.yaml 과 어긋난 채 커밋되는 것 방지)

**완료 기준**: `./gradlew build` 그린. 아키텍처 테스트가 레이어 위반을 실제로 잡는지 1회 확인.

### task (2026-08-04 분해)

- [x] **T0.1 Gradle 골격** — settings.gradle.kts(7 leaf 모듈) · `gradle/libs.versions.toml` · wrapper 9.6.1(sha256 고정) · JDK 25 toolchain(foojay 자동 프로비저닝).
  완료: `./gradlew projects` 에 7 leaf 모듈 · 빈 소스로 `./gradlew build` 그린. 근거: CLAUDE.md 4절 · tooling.md 1절
- [x] **T0.2 테스트 파이프라인** — starter-test(JUnit 6)·MockK 배선 + bcm-api 에 Flyway + Testcontainers(PostgreSQL · `@ServiceConnection`) 통합 테스트 1개.
  완료: 통합 테스트 그린 = 신품 메이저 조합(Boot 4.1·JUnit 6·TC 2.0·Jackson 3) 호환 확인. 근거: docs/testing.md 스택 표
- [x] **T0.3 ArchUnit 모듈 규칙** — domain 무의존 · api 계층 Repository 직접 의존 금지 · 물리 컬럼 매핑(spring-data-relational) infra 한정. core API 를 일반 `@Test` 로.
  완료: 규칙 테스트 그린 + 고의 위반 1회 실검출 확인 후 원복 (커밋 0c63db2 메시지에 증적). 근거: docs/standards/architecture.md
- [x] **T0.4 개발 루프 마감** — ktlint Gradle 연동 · dependency locking(`gradle.lockfile` 커밋) · CI 스크립트(build + docs/api 생성물 drift 체크, untracked 포함).
  완료: `./scripts/ci.sh` 로컬 그린. 근거: tooling.md 4절

Phase 0 이월 사항 → Phase 1 에서 회수:
- DB 배선(starter-data-jdbc·Flyway·pg driver)이 부트스트랩 편의상 bcm-api 에 직접 있다 — Phase 1 에서 infra/persistence 로 이동 (architecture.md "persistence = DB 소관")
- 통합 테스트 컨테이너는 현재 1클래스라 싱글턴 규칙과 동치 — 두 번째 통합 테스트 클래스부터 공용 베이스(싱글턴 컨테이너)로 재구성 (docs/testing.md)

## Phase 1 — 도메인 + DB

계약 로직을 인프라 없이 먼저 고정한다.

- `bcm_` 11테이블 Flyway 마이그레이션 (03-bcm-db 9개 + 07-asset-master 카탈로그·매핑 2개 — 코어 규약 타입)
- 도메인: TxStatus 5값(openapi.yaml 번역 표) · **허용 전이 표**(02-bcm-flow) · 이벤트(evt_typ_dvcd TXCK/TXCF/TXFL/TXRJ, evnt_stcd P/D/F/S)
- ★ tx 식별 설계에 **boost 의 txId 접기를 전제**로 둔다 — 대체 거래(새 txId)가 원 txId 로 접혀 들어오므로, 거래 식별이 "웹훅의 txId = bcm_tx_l 의 키" 라는 가정으로 굳으면 Phase 7 에서 다시 연다
- Repository 인터페이스(domain) + Spring Data JDBC 구현(infra/persistence)
- 멱등의 최종 방어선 = **DB unique 제약** — `ref` · `(accountId, asset)` · `ext_tx_id` 유니크가 03 스키마에 있는지 대조, 없으면 미해결 표 등재 (앱 로직만으로는 동시 요청 레이스를 못 막는다)
- ★ 착수 선행 작업: **#14 결정의 설계 반영** — waas-wiki 03 개정(tx_l 에 subStatus·networkStatus 컬럼 추가 · 미확정 절 정리) 후 docs/design 사본 동기화. 이벤트 도메인 모델은 **금액 필드 추가 여지**를 열어 둔다(#5 가 Phase 4 게이트로 확정되면 additive 로 수용)
- Phase 0 이월 회수 ①: **DB 배선(starter-data-jdbc·Flyway·pg driver)을 bcm-api → infra/persistence 로 이동**
- Phase 0 이월 회수 ②: 통합 테스트 공용 베이스 `IntegrationTestSupport`(싱글턴 컨테이너 — .claude/rules/testing.md) 도입 — 두 번째 통합 테스트 클래스가 생기는 시점이 이 Phase 다
- 자체 skill 2종 작성(tooling.md 2절 후보, 2026-08-05 확정) — **`db-migration`**(03 대조 → 코어 규약 타입 → 감사 4컬럼+센티넬 → 왕복 테스트 절차) · **`integration-test`**(싱글턴 컨테이너·`@ServiceConnection` 배선) — 첫 마이그레이션·첫 테스트를 만들며 절차를 skill 로 굳힌다

**완료 기준**: 전이 표 전 케이스 단위 테스트 (FINALIZED→FAILED reorg 반영, FINALIZED→CONFIRMED 무시, (없음)→FINALIZED 감지 합성, FAILED 종결 등). 테이블 매핑 왕복 테스트.

### task (2026-08-05 분해)

- [x] **T1.0 선행** (2026-08-05, 커밋 947fca5) — waas-wiki 03 개정(Stage 162) + 사본 byte-동일 동기화
- [x] **T1.1 도메인 — TxStatus·전이 표** (2026-08-05, 커밋 19c6e18) — 30조합 전수 계약 테스트 red→green.
  근거: docs/design/02 96~137행 · openapi.yaml 상태 절
- [x] **T1.2 도메인 — 이벤트 모델** (2026-08-05) — EventType(3토픽)·OutboxEventType(TXCK/TXCF/TXFL/TXRJ)·OutboxStatus(P/D/F/S)
  + 상태→유형 매핑 red→green. REJECTED 는 코어 회신 전까지 임시 TXRJ 를 상수 한 곳에서 관리(#15). 근거: 03 bcm_outbox_l·시나리오
- [x] **T1.3 Flyway V1 — 9테이블** (2026-08-05, 커밋 5980381) — 03 그대로 · 멱등 유니크 3종 확인 ·
  스키마 검증 테스트 · db-migration skill 채록
- [x] **T1.4 Repository 포트 + Spring Data JDBC 구현** (2026-08-05) — Account·DepositAddress·TxRecord 도메인 모델
  + 포트 3종 + 어댑터 3종(복합 PK 는 SQL 매핑). DB 배선 persistence 로 이동(이월 ①) · 조립 배선(scanBasePackages +
  PersistenceConfig) · persistence 에 allopen(CGLIB 실측). 왕복·제약 테스트 9건 + 조립 검증 그린
- [x] **T1.5 싱글턴 컨테이너 베이스** (2026-08-05, 커밋 86e84e1) — 이월② 회수 · integration-test skill 채록
- [x] **T1.6 converge** (2026-08-05) — design-sync "차이 없음 정합" · code-reviewer "커밋 가능(계약 위반·Critical 0)".
  지적 반영: null·PK 충돌 테스트 추가 · SELECT * 제거 · KDoc 물리명 제거. 게이트 성격 지적은 미해결 #17·#18·#19 등재

## Phase 2 — API 공통 규약 + 계정·주소·잔액

입금 귀속(주소 → 계정)의 전제가 되는 부분을 먼저 만든다 — 주소 매핑 없이는 감지가 전부 귀속 불명만 낸다.

- 착수 전 게이트 전부 해소: #7a(스펙 v0.0.5) · #9(4.1 유지) · #12(무인증) · #16(일시 14자)
- 공통 규약: 응답 envelope(`data`/`meta.requestId`/`pagination`) · 에러 형식(`error.code` 6종) · 커서 페이지네이션 골격
- `createAccount`(ref 멱등) · `createDepositAddress`((accountId, asset) 멱등) · `depositAddressOf` · `balanceOf`
- infra/client: Fireblocks 클라이언트 — vault 생성·주소 발급·잔액 조회 (JWT 서명 · 백오프)

**완료 기준**: 스펙 준수 테스트 — 각 오퍼레이션 응답이 openapi.yaml 스키마와 일치(검증 라이브러리로 자동 대조). 멱등 재요청이 같은 결과. 벤더 호출은 mock.

### task (2026-08-05 분해)

- [x] **T2.0 게이트 해소** — 스펙 v0.0.5(#7a) · 일시 14자 확정(#16, CLAUDE.md 3절) · 기존 픽스처 14자 정합
- [x] **T2.1 공통 규약** (2026-08-05, 커밋 ebff880) — 응답 envelope · 에러 6종 CodeEnum(CodeEnumType) · sealed 도메인 예외 4종 +
  DomainExceptionResolver(@RestControllerAdvice) · RequestIdFilter(자체 UUID) · support CoreDateTimes(14자). red→green 18건.
  웹 스택은 Boot 4 모듈화 스타터(webmvc·webmvc-test — 커밋 42ce636, 사용자 승인)
- [x] **T2.2 infra/client** (2026-08-05) — WalletVendorPort(domain) + FireblocksClient — JWT 서명(RS256·uri/nonce/iat/exp/sub/bodyHash) ·
  429 백오프(Retry-After 존중) · Idempotency-Key · VendorApiException 변환. red→green 11건 + 핸들러 1건.
  벤더 근거: JWT 공식 문서 + fireblocks-openapi-spec 실물 — 정확한 소재는 github.com/fireblocks/fireblocks-openapi-spec
  의 `api-spec-v2.yaml` (T2.5 에서 재검증 2026-08-05: Idempotency-Key 헤더명·24h 유효·VaultAsset total 산식 모두 실물 문구와 일치.
  로컬 미러 `~/Workspace/fireblcoks-docs` 28-account-balances·114/230-idempotency 실측과도 일치)
- [x] **T2.3 계정·주소 API** (2026-08-05) — AccountService(선조회→벤더→저장, UNIQUE 경합 시 이긴 값 재조회 — 03) +
  AccountController(@Valid·경로 @Size — maxLength 경계 400) + 어댑터 충돌의 ConflictException 변환(#17③ 선회수) +
  미발급 조회 `data: null` 직렬화. red→green 19건. **스펙 스키마 자동 대조는 T2.5 로 이월** — 검증 라이브러리(swagger-request-validator)의
  OpenAPI 3.1 지원 검증 + 의존성 승인이 선행. 신규 미해결 #20(asset 변환)·#21(memoTag 비영속)
- [x] **T2.4 balanceOf** (2026-08-05) — GET balance 200/404/400 + `BalanceData`(locked = lockedAmount + frozen 합산 —
  스펙 locked 정의) + support `CoreAmounts`(BigDecimal 문자열 합산·평문 출력). 벤더 호출은 T2.2 의 `balanceOf` 재사용.
  red→green 10건(support 5 · 서비스 2 · 컨트롤러 3)
- [x] **T2.5 converge** (2026-08-05) — design-sync "차이 없음 정합 + 스펙 공백 3건"(#22·#23·#24 등재) ·
  code-reviewer "Critical 1(Clock zone)·Major 3" → C1(빈 이동, zone 은 #26)·M1(백오프 상한)·M2(asset 패턴) 반영(커밋 c9efa84),
  M3 은 #25 등재. 벤더 근거(24h·total 산식·헤더명) 공개 스펙 실물 재검증. 스키마 자동 대조 도입(커밋 496f1c7, 이월 회수) ·
  fireblocks-docs MCP 제거(조건 집행). 전 모듈 `./gradlew build` 그린
- [x] **T2.6 벤더 자산 매핑** (2026-08-06) — `bcm_blkc_m` 카탈로그 + `bcm_vndr_ast_m`(PK `(network,symbol)` ·
  vendorAssetId UNIQUE · network FK) + DB 조회 기반 벤더 assetId 변환 + 미지원 자산 발급 전 전체 400 + 벤더 중립 Admin API 7개.
  등록은 채택 network의 벤더 자산을 끝까지 조회해 blockchainId·컨트랙트 주소를 대조하고 실제 직원·부점 감사를 저장한다.
  converge에서 채택/해제 원자성, chainId set-once·감사 규약, Fireblocks 중첩 필드 위치를 보강했다. 실제 assetId 시드는 넣지 않음.

## Phase 3 — 웹훅 수신

fbhook PoC 에서 검증된 경로를 이식한다 (`~/Workspace/fbhook` 참고).

- [x] **T3.1 수신 기반** — V1 원문 TEXT/hash/sign + raw sign, 당시 KST Clock(2026-08-14 UTC로 대체), 가상 스레드와 HTTP 연결 상한
- [x] **T3.2 웹훅 세로줄** — `POST /webhook` RS512 detached JWS 원문 검증 → `bcm_whk_l` 적재 → 200,
  서명 없음·불일치 401, `noti_id` 순차·동시 중복 무시. JWKS 최초 실패·낯선 kid는 외부 호출을 증폭하지 않게 timeout·cooldown 처리
- [x] **T3.3 converge** — design-sync 사본 13개 byte-동일·핵심 계약 정합 확인. code-reviewer 지적의 필수 필드 결손,
  JWKS 5xx/cooldown, 동시 PK 경합 테스트를 보강한 뒤 "남은 Critical 없음·커밋 가능" 판정. `./gradlew check` 그린

- ★ 착수 전 게이트 **해소 완료 (2026-08-06)** — #10(원문 바이트 보존) · #8(가상 스레드) · #26(zone) 모두 확정. 03·99 개정 + 사본 동기화 완료
- 수신 endpoint: 즉시 200 · `noti_id` PK dedup · 원문 그대로 `bcm_whk_l` 적재
- 서명 검증: 벤더 JWKS 공개키 · RS512 detached JWS · **원문 바이트로 검증** (파싱 후 재직렬화 금지 — 실측 확인)
- **본문 바이트를 한 번 읽어 세 곳에 같은 `byte[]` 를 쓴다** — 서명 검증 · `payload`(TEXT) 저장 · `payload_hash`(SHA-256 소문자 hex). 서명 헤더 원문은 `sign_vl` 에 함께 적재 (03 · 셋 다 수신 시점에만 만들 수 있다)
- JWKS 조회는 타임아웃 + 수신 스레드와 분리
- 가상 스레드 활성(`spring.threads.virtual.enabled=true`) + **수신 동시성 명시 상한** — 커넥션 풀이 실질 상한이라 무제한이면 폭주 시 커넥션 대기로 쌓인다 ([.claude/rules/virtual-thread.md](.claude/rules/virtual-thread.md))
- Clock 빈 2곳(`bcm-api`·`bcm-bat`)을 당시 KST 로 교체 — 2026-08-14 모든 DB 일시·일자 UTC 결정으로 재교체 (#26)

**완료 기준**: 96-payload-sample 실물 payload 로 통합 테스트 — 정상 적재 / 서명 없음·가짜 서명 401 / noti_id 중복 무시. **적재된 `payload` 바이트가 수신 본문과 완전히 같고**(저장했다 꺼낸 값으로 서명 재검증 통과), `payload_hash` 가 그 바이트의 SHA-256 과 일치.

## Phase 4 — 판단 워커 + outbox + relay (입금 E2E)

입금 감지→확정이 DAW-CORE 까지 닿는 첫 E2E 세로줄.

- 워커: 인박스 SKIP LOCKED 폴링 → 귀속(Phase 2 주소 매핑) → 전이 판정 → **한 트랜잭션**(bcm_tx_l + bcm_outbox_l(P) + prcs_stcd=S)
- tx 행은 `SELECT FOR UPDATE`로 잠근 뒤 판정하고, 갱신 SQL은 `GREATEST(cnfm_cnt)`·`GREATEST(last_chng_dttm)`와
  최초 탐지/감사 컬럼 제외로 감소·최초 흔적 덮어쓰기를 DB에서 방어한다 (#17 해결)
- **귀속 불명 분기** — 매핑에 없는 주소의 입금은 큐에 싣지 않고 **별도 알림 채널**로 통지 (02·01 설계). 채널은 **포트 추상화**(#13 — 구체 수단은 뒤에 바인딩). 해소 절차는 설계 미확정(02 "확인 후 확정") — 통지까지만 구현
- **입금 외 분류**(출금·내부·sweep 발신 웹훅 — 02 분류 표)는 이 Phase 미배선 — 인박스에 표시 후 보류하는 방침을 task 분해에서 확정 (Phase 5·6 에서 배선)
- **poison 격리** — `prcs_stcd=P/S/F` + `rtry_cnt` + `err_msg`. 실패 상한 초과 시 F 격리·경보, 원인 해소 후 P 로 되돌려 재처리
- 감지 합성: 앞 단계 미발행 시 감지 이벤트 먼저 발행 (02 계약)
- relay: P→S, 같은 계정 내 evnt_id 순차, Kafka `deposit-events` (파티션 키 = accountId), 메시지 형태는 ChainEvent 스키마 (`eventId` = outbox evnt_id)
- cnfm_cnt·last_chng_dttm 감소 금지

**완료 기준**: Testcontainers(PostgreSQL+Kafka) E2E — 웹훅 2건(감지·확정) 투입 → 토픽에서 순서대로 2건 소비. 순서 역전 도착(확정 먼저) 시 감지 합성 후 2건 발행. relay 발송 후 S 표시 전 크래시는 같은 `eventId` 재발행이 가능하며 컨슈머 dedup 으로 이중 효과를 막는다(at-least-once). **귀속 불명 입금이 큐에 실리지 않고 알림 채널로 빠짐.** poison 건이 정상 건 처리를 막지 않음. 소비한 메시지가 openapi.yaml `ChainEvent` 스키마와 자동 대조 통과.

### task (2026-08-07 분해)

- [x] **T4.0 tx DB 방어** — `SELECT FOR UPDATE`, 컨펌/시각 `GREATEST`, set-once 보존, UNIQUE 경합 재조회
- [x] **T4.1 판단 워커 + outbox** — 인박스 `SKIP LOCKED` → 자산/주소 귀속 → Fireblocks 원어 번역+DCCP → 전이 판정 →
  tx/outbox/인박스 S 원자 커밋. 확정 선도착 감지 합성, 귀속 불명 무발행 알림, poison P→F 격리, UUID v7 순서 보장
- [x] **T4.2 Kafka relay + E2E** — 계정별 `evnt_id` 순차 발송, P→S/재시도→F, PostgreSQL+Kafka 소비 및 ChainEvent 스키마 대조

## Phase 5 — 출금·내부이체 (출금 E2E)

돈이 나가는 경로의 E2E — 입금과 대칭으로 완료 기준을 세운다.

- ★ 착수 전 게이트 **해소 완료 (2026-08-07)** — #6(멱등 응답)·#7b(스펙 보완) 확정. 02·03 개정 + 사본 동기화 + 스펙 v0.6.0 완료
- **선행: `bcm_sbmt_l` 제출 원장을 `V1__bcm_core_tables.sql` 에 추가** (03 신설) — `ext_tx_id` PK · `req_hash`·`hash_vrsn` · `sbmt_stcd` · `tx_dvcd` · `vndr_tx_id` 부분 UNIQUE.
  ★ **V2 를 만들지 않는다** — 프로덕션 전이라 V1 이 유일한 baseline 이고, Phase 1 이후 스키마 변경(자산 두 컬럼 분리 · accountType · Phase 3 payload TEXT)을 전부 V1 제자리 수정으로 처리해 왔다. 로컬 DB 는 재생성한다. 첫 배포 이후에야 증분 마이그레이션으로 전환한다
- `submitTransaction`(`externalTxId` 멱등) · `transactionOf` · `transactionByExternalTxId` · `transactionsOf`(커서 — asc 증분 폴링 계약 포함)
- **멱등은 벤더 호출 전에 끝난다** — 원장 INSERT(REQUESTED, 소유권 동시 획득) → 커밋 → 벤더 호출(트랜잭션 밖) → UPDATE(SUBMITTED). PK 충돌 시 02 의 분기 표대로: 내용 다름 409 · SUBMITTED 202(기존 txId) · REQUESTED+유효 소유권은 503 · REQUESTED+만료는 소유권 탈취 후 벤더 조회 먼저 · FAILED 는 재제출
- **동시 제출 소유권(claim)** — `claim_id`·`claim_exp_dttm` 을 **조건부 UPDATE 한 번(CAS)** 으로 잡는다(읽고 쓰면 그 사이에 끼어든다). 소유자만 벤더 호출. 후발 요청은 **기다리지 않고** `503 SUBMIT_IN_PROGRESS` + `Retry-After`. 만료 뒤 뺏은 소유자는 **제출 전에 벤더 `external_tx_id` 조회부터** — 건너뛰면 그게 이중 출금이다. `SUBMITTING` 상태를 안 쓰는 이유는 소유자 사망 시 영구 고착이라서
- ★ **벤더 제출 호출 명시적 타임아웃 반영 완료** — Fireblocks 전용 풀링 HTTP 클라이언트에 connect/read timeout을 적용하고, `claim-ttl-seconds` 가 재시도·백오프와 400 후속 조회를 포함한 제출 흐름의 보수적 최장시간보다 반드시 길도록 조립 설정에서 검증한다
- **`sbmt_stcd` 전이는 경로별로 다르다** (03 전이 표) — `FAILED → SUBMITTED` 회수는 **웹훅·미결 점검 경로만** 허용하고 제출 응답 경로로는 불가. 이미 다른 `vndr_tx_id` 가 있으면 회수 대신 격리
- **제출 4xx 분류** (02 표) — `400`은 오류 코드로 가르지 않고 전부 externalTxId 조회 후 거래 있음→SUBMITTED·없음→FAILED·조회 실패→REQUESTED로 판정한다. `409`·`422`만 즉시 FAILED, 그 밖의 4xx는 REQUESTED 유지 — 애매한 걸 확정 거절로 읽으면 나간 거래를 놓친다
- **canonical 요청 해시** — 고정 7줄 줄바꿈 결합의 SHA-256(03). `amount` 만 정규화(`stripTrailingZeros().toPlainString()`), 나머지는 원문 그대로. `note`·`travelRule` 제외. 규칙은 support 유틸 한 곳
- 벤더 `400`만으로 중복과 검증 실패를 가르지 않는다 — `GET /v1/transactions/external_tx_id/{externalTxId}` 로 실재 여부를 확인한 뒤에만 SUBMITTED/FAILED를 확정한다
- 출금 상태 변경은 웹훅 경로(Phase 3~4)로 합류 → `withdrawal-events` 발행 (**파티션 키 = 출금 풀 vault 의 accountId**, externalTxId 로 우리 요청과 대응)
- 내부이체(delta 정산) 제출 → `internal-events` 발행 (파티션 키 = 출발 계정 accountId)
- **웹훅 분류를 제출 원장 기준으로 전환** — Phase 4 는 vault 발신을 전부 무시(`managedVaultSource` → Ignored)했다. 이제 `data.externalTxId` 로 원장을 찾아 `tx_dvcd` 대로 가른다(02 분류 표). 원장에 없는 vault 발신은 발행하지 않고 알림 채널 통지. 찾은 행의 `vndr_tx_id` 가 비어 있으면 이때 채운다
- 미결(`REQUESTED`) 제출 점검은 **Phase 7 막힘 점검에 합류** — 재시도만 오면 회복되므로 Phase 5 안전망은 재시도 경로로 충분하다

**완료 기준**: **출금 E2E** — 제출 → SUBMITTED → (웹훅 투입) → CONFIRMED → FINALIZED 가 `withdrawal-events` 로 순서대로 소비. 같은 externalTxId 재제출 무중복(벤더 mock 검증 포함). internal-events 발행 E2E 1건. 커서 계약 테스트(마지막 페이지에서도 nextCursor 채움, asc 재요청 시 증분만). 스펙 스키마 대조.

**멱등 계약 테스트** — ① 같은 키 + 같은 내용 재제출이 **벤더를 다시 부르지 않고** 처음 txId 로 202 ② 같은 키 + 금액만 다른 재제출이 409 ③ `"1.50"` 재제출이 `"1.5"` 와 같은 것으로 판정 ④ `note`·`travelRule` 만 다른 재제출이 202 ⑤ **벤더 호출 후 UPDATE 실패**를 주입하고 재시도했을 때 벤더 조회로 회수해 202(이중 제출 없음) ⑥ 벤더 400(중복)이 500 이 아니라 회수 후 202.

**분류 테스트** — sweep 제출 건의 웹훅이 deposit·withdrawal·internal 어느 토픽에도 실리지 않음. 원장에 없는 vault 발신 웹훅이 발행 없이 알림 채널로 감.

**동시성 테스트** — 같은 externalTxId 를 동시에 N개 넣었을 때 **벤더 submit 호출이 정확히 1회**. 소유권 만료 후 재시도가 벤더 조회를 먼저 하고, 있으면 제출하지 않고 202. `FAILED` 행에 웹훅이 오면 `SUBMITTED` 회수, 다른 `vndr_tx_id` 면 격리. 제출 응답 경로로는 `FAILED` 가 안 뒤집힘.

### task (2026-08-07 분해)

- [x] **T5.0 제출 원장 기반** — V1 `bcm_sbmt_l`, 도메인 모델·Repository·JDBC 어댑터, canonical v1 요청 해시와 금액 정규화. PK·부분 UNIQUE·인덱스·왕복 계약 테스트 (2026-08-07 완료)
- [x] **T5.1 벤더 거래 포트·어댑터** — 제출, `externalTxId` 회수 조회, txId 단건, 커서 목록. 모든 400은 회수 조회 후 실재 여부로 판정하고, 409·422 확정 거절과 그 밖의 4xx·5xx/타임아웃을 복구 규칙에 맞게 변환 (2026-08-07 완료)
- [x] **T5.2 제출 API·멱등 상태 머신** — 원장 선커밋 → 벤더 호출 → SUBMITTED/FAILED 갱신, 같은 요청 202·다른 요청 409·REQUESTED 회수. `POST /transactions` 스펙 대조 (2026-08-07 완료)
- [x] **T5.3 거래 조회 API** — externalTxId·txId 단건과 계정별 커서 목록. 첫 요청 after 필수·cursor 요청 조건 무시·마지막 nextCursor 보존 계약 테스트 (2026-08-07 완료)
- [x] **T5.4 vault 발신 웹훅 분류** — 제출 원장의 WITHDRAWAL/INTERNAL/sweep 계열 기준 라우팅, 선도착 웹훅의 vendor txId 회수, 미등록 vault 발신 무발행 알림 (2026-08-07 완료; sweep 계열은 T6.7에서 APPROVE/BATCH로 교체)
- [x] **T5.5 출금·내부이체 E2E + converge** — 제출→SUBMITTED→CONFIRMED→FINALIZED 토픽 소비, 재제출 무중복, internal 1건·sweep 오분류 방지, OpenAPI 스키마 대조 후 design-sync·code-reviewer. claim CAS·503/Retry-After·만료 회수·제출 흐름 timeout, 400 조회 판정, FAILED 웹훅 회수, 자체 커서·주소 nullable을 반영했다. 2026-08-07 code-reviewer는 "커밋 가능·Critical 없음", design-sync는 최신 waas-wiki 기준 "구현 정합" 판정. 2026-08-10 waas-wiki 02·03 사본 byte-동일 동기화 완료

## Phase 6 — sweep (approve + transferFrom 배치)

**2026-08-12 설계 변경 확정: 고객 vault별 제한 allowance + 운영 계정의 `batchSweep` 한 건. 목적지는 옴니버스 vault다.**
TAP → Co-signer Callback → 목적지 불변 sweep 컨트랙트가 3중 통제하고, 최상위 거래 아래 `SweepExecution 1:N SweepItem`을 둔다.
건별 일반 전송 구현은 폐기했으며 아래 T6.0~T6.6은 변경 전 구현 이력이다. 활성 경로는 T6.7부터다.

- 트리거 판정은 **02 현행 기준(주기 + 최소 금액, 운영 설정값)으로 한정** — 06 의 비율·가스비 정책은 원화 환산 입력(미결)과 수수료 시계열(Phase 8 산출물)을 요구해 지금은 순서가 역전된다. 후속 확장으로 분리
- `bcm_swp_trgt` 대상 추출 → allowance 재확인/approve → 실행·항목 선기록 → 운영 계정 batch CONTRACT_CALL → 항목별 대사
- approve와 batch 제출은 `bcm_sbmt_l`에 각각 `SWEEP_APPROVE`·`SWEEP_BATCH`로 남기고 `externalTxId`를 붙인다.
- sweep은 고객 이벤트 토픽에 싣지 않는다. 항목 정합은 network records + receipt `SweepLeg`로 확인한다.
- sweep 거래도 막힘 점검 대상이지만 Universal Gasless + RBF 조합은 별도 기능 게이트 기본 비활성이다.

**완료 기준**: allowance·approve(0)·실행 1:N·canonical hash·batch 제출 멱등·부분 성공 대사·출시 게이트 fail-closed 테스트.
**오분류 방지** — approve·batch 최상위 웹훅은 어느 고객 토픽에도 발행하지 않는다.

### 변경 전 건별 구현 이력 (2026-08-10~12, 현재 실행 경로에서는 폐기)

- [x] **T6.0 범위 고정** — Phase 6은 고객 vault별 Fireblocks 일반 전송이며 목적지는 옴니버스 vault다.
  M은 한 주기의 처리 상한일 뿐 온체인 묶음이 아니고, 98의 배치 컨트랙트 모델은 구현하지 않는다.
  완료: 06의 채택안과 PLAN 범위가 일치하고 후속 task가 리뷰 가능한 세로줄로 분해됨. 근거: 02 sweep 절 · 06 "실행 방식 — 건별 일반 전송"
- [x] **T6.1 sweep 대상 작업 큐** (2026-08-10) — `bcm_swp_trgt` 도메인 모델·Repository 포트·JDBC 어댑터를 구현한다.
  확정 입금의 멱등 마킹, 미제출 대상 조회, 제출 tx 기록, 종결 후 재조회 가능 전환, 잔액 미달 행 삭제를 원자 연산으로 제공한다.
  완료: 복합키 멱등·조회 순서/상한·상태 변경·삭제 persistence 통합 테스트 그린. 근거: 03 `bcm_swp_trgt` · 입금 시나리오
- [x] **T6.2 FINALIZED 대상 마킹** (2026-08-10) — 입금이 처음 FINALIZED로 전이되는 판단 트랜잭션에서 (계정, 네트워크, 심볼)을 마킹한다.
  완료: 중복 FINALIZED와 같은 자산의 복수 입금에도 대상 행은 하나이고, outbox·tx 갱신과 같은 트랜잭션임을 통합 테스트로 검증. 근거: 02 sweep · 06 Finalized 조건
- [x] **T6.3 트리거 조회·스케줄러** (2026-08-11) — 운영 설정의 주기·자산별 최소 금액·한 주기 M을 적용하고 현재 이용가능잔액이 큰 순서로 대상을 고른다.
  오래된 미제출 대상부터 `scanLimit`까지만 잔액을 조회한 뒤 M개를 고른다. 최소 미달은 제거하고 진행 중 행은 제외하며, 최소 금액 미설정 자산은 보류한다.
  스케줄러는 기본 비활성이고 옴니버스 SYSTEM 계정 설정이 없거나 잘못되면 벤더 잔액 호출 전에 실패한다.
  완료: 최소 경계값·M 정렬·진행 중 제외·잘못된 목적지 fail-closed 단위 테스트 그린. 근거: 02 sweep · 06 트리거/M개 선정
- [x] **T6.4 건별 일반 전송 제출** (2026-08-12) — 대상마다 `swp-` + UUID v7 externalTxId와 canonical hash를 만들고 `bcm_sbmt_l`에 `SWEEP`으로 선기록한 뒤
  claim·만료 회수·400 후 조회 등 Phase 5 제출 안전 경계를 그대로 적용해 옴니버스 vault로 gasless 일반 전송한다.
  대상 행 잠금과 열린 SWEEP 원장 재사용으로 동시 실행을 직렬화하고, 실제 호출 직전에 시도 횟수를 별도 커밋한다.
  완료: 같은 대상 동시 실행 무중복·만료 claim 회수 시 원 externalTxId/내용 재사용·400 후 조회/실패 전이·1 대상=1 벤더 거래 단위 테스트 그린. 근거: 02 멱등/claim/sweep 분류 · 03 `bcm_sbmt_l`
- [x] **T6.5 sweep 종결 합류** (2026-08-12) — SWEEP 웹훅 종결 시 대상의 `swp_tx_id`를 비워 다음 주기에 잔액을 재확인하게 하고 고객 토픽은 발행하지 않는다.
  진행 중 `SUBMITTED`·`CONFIRMED`는 유지하고, 종결 `FINALIZED`·`REJECTED`·`FAILED`만 같은 벤더 거래가 연결된 대상을 해제한다.
  완료: 성공·탈락 종결 뒤 재조회 가능, 비종결 상태 유지, deposit·withdrawal·internal 무발행 통합 테스트 그린. 근거: 02 웹훅 라우팅 · 03 sweep 시나리오
- [x] **T6.6 E2E + converge** (2026-08-12) — 실제 PostgreSQL/Flyway와 가짜 벤더로 FINALIZED 마킹→잔액 판정→건별 제출→종결→미달 정리 세로줄을 검증했다.
  선행 종결 웹훅과 제출 마감은 target→tx 잠금 순서를 통일하고, 잔액 HTTP는 DB 트랜잭션 밖에서 호출한 뒤 target 잠금+신규 FINALIZED tx 집합 재검사로 삭제 경쟁을 막는다.
  유효 claim·미종결 제출은 다음 선정을 보류하고 만료 원장은 최초 금액으로 회수하며, 대상별 실패는 경보 후 다음 건을 계속 처리한다. finality 설정 오류는 payload poison 격리 대신 P 상태 재시도로 남긴다.
  완료: `./gradlew check ktlintCheck` 314건 그린, OpenAPI 생성물 신선, code-reviewer 신규 Critical 0·design-sync Phase 6 설계 정합 판정. 근거: PLAN 공통 완료 규칙

### batch 재구현 task (2026-08-12 분해)

- [x] **T6.7 1:N 원장·allowance 기반** — V1에 `bcm_swp_auth_m`·`bcm_swp_exec_l`·`bcm_swp_item_l`과 target item FK,
  제출 원장 `SWEEP_APPROVE`·`SWEEP_BATCH` 분류를 반영한다. 실행·항목·target claim을 한 트랜잭션으로 선기록하고
  기존 건별 제출/종결 경로를 제거한다. 완료: PostgreSQL 왕복·중복 claim 전체 롤백·sweep 고객 이벤트 무발행 테스트 그린.
  근거: 02 sweep 흐름 · 03 sweep 4테이블 · 06 allowance/실행 · 98 채택 결론
- [x] **T6.8 allowance 준비·긴급 회수** (2026-08-12) — 고객 vault·컨트랙트별 온체인 allowance를 재조회하고 부족할 때만 제한 cap을
  `SWEEP_APPROVE` 원장 선기록 후 CONTRACT_CALL로 제출한다. 0이 아닌 cap 변경은 active item 없음 → `approve(0)` 확정·0 재관찰 →
  새 cap 승인 순서로 직렬화한다. TAP·Callback·Universal Gasless 출시 게이트는 기본 비활성으로 둔다.
  완료: EVM JSON-RPC `allowance`·`decimals` 조회와 `approve` ABI 인코딩, Fireblocks gasless CONTRACT_CALL, `cc-v1` canonical hash와
  제출 claim/400 조회 회수, 정상 승인·긴급 회수의 독립 게이트를 구현했다. 제출 직후 ACTIVE 금지, cap 변경 0 선행, active item 차단,
  `REVOKING`→온체인 0→`REVOKED`, 같은 externalTxId 멱등을 테스트하고 `./gradlew check ktlintCheck` 전체 그린으로 확인했다.
- [x] **T6.9 batch 실행 준비·제출** (2026-08-12) — 같은 network·symbol의 ACTIVE allowance 후보를 M개 이내로 묶고 정렬된 canonical hash,
  calldata, `SweepExecution 1:N SweepItem`을 만든다. 운영 계정 호출을 직렬화하고 `SWEEP_BATCH` 원장과 execution id를 연결해
  gasless CONTRACT_CALL을 멱등 제출한다. 완료: 운영 ABI `batchSweep(bytes16,address,(address,uint256)[])`와 `batch-v1` canonical을 확정하고,
  UUID v7·주소순·토큰 최소 단위 calldata, allowance 행 잠금 재검증, 실행·항목·target claim 원자 생성, 운영 계정별 READY/SUBMITTING
  부분 UNIQUE 직렬화, 프로세스 중단 및 relay 거절 뒤 같은 execution/external id 복구를 구현했다. 출시 게이트는 기본 비활성이며
  `./gradlew check ktlintCheck` 전체 그린으로 확인했다. 근거: 03 실행 1:N·canonical · 06 운영 ABI/권한 경계
- [x] **T6.10 항목별 결과 대사** (2026-08-12) — 최상위 batch 종결과 network records 처리 완료는 실행을
  `RECONCILING`으로만 바꾼다. 벤더 거래·원천 vault별 network records와 receipt `SweepLeg`를 요청 N개에 fail-closed로 대조해
  실제 금액·실패 코드·로그 위치와 `COMPLETED/PARTIAL`을 기록한다. 성공은 잔액과 신규 확정 입금을 재확인해 target을 정리하고,
  실패·잔존은 claim을 해제해 다음 회차로 되돌린다. 누락·중복·불일치는 실행과 claim을 보존하고 경보하며, 최상위 실패는 항목을
  `RETRY`로 돌린다. 완료: EVM receipt 디코딩·Fireblocks network records 매핑·웹훅/배치/DB 통합 테스트 및
  `./gradlew check ktlintCheck` 전체 그린. 근거: 02 batch 대사 · 03 실행 1:N · 06 운영 ABI · 93~95 실측
- [x] **T6.11 E2E + 출시 게이트 + converge** (2026-08-13) — approve 준비→온체인 재확인→batch 선기록/제출→부분 성공 대사,
  중복 실행·Callback 불일치·전체 `approve(0)` 회수·고객 토픽 무발행을 검증한다. 실측 전 기능 게이트가 fail-closed임을 고정하고
  최신 설계 사본 동기화 뒤 design-sync·code-reviewer를 통과해야 Phase 6을 다시 완료한다.
  2026-08-13 PostgreSQL/Flyway E2E로 고객 vault 2개의 approve 선기록→온체인 cap 재관측→batch 1:N 선기록·단일 제출→
  1건 성공·1건 실패 대사→두 vault 전부 `approve(0)`·온체인 0 재관측을 검증했다. 같은 claim의 중복 batch 무제출,
  sweep outbox 0건, gasless 요청, Callback 미검증 시 approve·batch·긴급 회수 전 경로 차단과 배포 기본 게이트 9종 false도 고정했다.
  2026-08-13 converge에서 실행 경보 운영 빈과 저장된 tx hash 대사 회귀를 보강했다. 이어 `bcm_sbmt_l.call_data`에 정규화된
  calldata를 저장하고 타입별 존재·소문자 짝수바이트 hex 제약과 cc-v1 금액 정밀도 경계를 고정해 #39를 해소했다.
  `./gradlew check ktlintCheck` 391건 그린, code-reviewer Critical 0·커밋 가능 판정이다. waas-wiki 03은 `fe92927`로 개정했고
  최신 03·06을 포함한 설계 사본 전부가 정본과 byte-동일하다. design-sync도 Critical 0·Phase 6 완료 가능으로 판정했으며
  OpenAPI 생성물 재생성 후 diff 없음까지 확인했다.

## Phase 7 — 막힘 점검 · 자동 boost

막힌 tx 는 웹훅이 오지 않는다 — 주기 작업이 DB 에서 찾아서 처리한다 (02 "막힘 점검 · 자동 boost").

- [x] **T7.0 공개 문서 기준 착수 게이트 조사** (2026-08-12) — Fireblocks가 문서화한 자동 boost는
  Gas Station의 auto-fueling 거래에 한정된다. 일반 EVM 발신은 `transaction.alert.stuck`으로 개입 필요를 알리고,
  Create Transaction의 `replaceTxByHash`로 RBF를 수행하라고 안내한다. 따라서 일반 gasless relay가 자동으로
  처리해 준다고 가정하지 않고 매니저 boost 트리거를 유지한다. 단, 벤더의 비공개 운영 보장은 담당자 확답 전까지 #3에 남긴다.
- [x] **T7.1 RBF 벤더 호출 경계** (2026-08-12) — 벤더 중립 요청 모델에 교체 대상 온체인 hash를 선택값으로 추가하고,
  Fireblocks 어댑터에서만 `replaceTxByHash`로 변환한다. 일반 제출에는 필드가 나가지 않고 RBF 제출에는 정확히 실리는 테스트 그린.
- [x] **T7.2 주기 작업 상태·미결 제출 회수** (2026-08-12) — `bcm_job_m` heartbeat를 공통 저장하고, 오래된
  `REQUESTED` 중 유효 claim·최근 점검을 제외해 원자 예약한다. externalTxId 조회에서 거래가 확인된 건만 `SUBMITTED`로
  회수하며 미발견·오류는 체크 시각·횟수만 갱신하고 절대 백그라운드 재제출하지 않는다. 재점검 간격은 벤더 단건 조회 최장
  시간보다 길게 검증한다. 완료: PostgreSQL 예약·heartbeat 통합 테스트, 미발견 무제출·부분 실패 단위 테스트, 전체 check 그린.
- [x] **T7.3 root 거래·최신 관찰 모델** (2026-08-12) — waas-wiki 02·03에서 실제 Fireblocks 기준으로 정합을 확정하고 사본을
  byte-동일 동기화했다. `bcm_tx_l`은 최초 vendor tx를 논리 root로 유지하고 `actv_tx_id`·`tx_hash`로 현재 물리 거래를
  추적한다. 웹훅 hash는 null→최초 값으로만 채우고 빈 관찰로 지우거나 다른 값으로 바꾸지 않는다. active id UNIQUE와
  막힘 후보 partial index를 V1에 반영하고 PostgreSQL·실물 웹훅 통합 테스트로 DB와 이벤트 hash 동일성을 검증했다.
- [x] **T7.4 막힘 조회·최신 관찰·경보** (2026-08-12) — 오래된 `SUBMITTED`·`CONFIRMED` root 후보의 active tx를
  벤더 단건 조회하고, `CONFIRMING`·hash 있음·0 confirmation·우리 EVM 출금/sweep만 boost 가능으로 분류한다. 체인 전·입금·
  채굴 뒤 확정 지연·식별자/hash 불일치는 boost 없이 경보하며, 자동 boost 기능 게이트는 기본 비활성이라 가능 건도 경보 전용이다.
  후보 snapshot의 active id·마지막 변경 시각을 CAS해 경보 중복·오래된 관찰 반영을 막고 `bcm_job_m` heartbeat를 남긴다.
  정상 상태 진행 시 `stall_alrt_dttm`을 비워 새 막힘을 다시 감지한다. 종결 최신 관찰의 정상 경로 재흘림은 T7.6 세로줄에서 닫는다.
- [x] **T7.5 RBF 제출·이력·txId 접기** (2026-08-13) — 시도 직전 벤더를 재조회하고 `bst-` UUID v7 intent·claim을
  선커밋한 뒤 HIGH fee·gasless·`replaceTxByHash`로 출금 RBF를 제출한다. 만료 claim은 externalTxId 조회부터 회수하고,
  최대 시도·claim 원자 획득·root active 조건부 전환을 PostgreSQL 테스트로 고정했다. 물리 승자 경합에서도 벤더에 실존하는
  `new_tx_id`는 set-once로 남긴다. V1 `bcm_boost_l`은 최신 03 정의와 맞춰졌다. Fireblocks 공식 문서상 CONTRACT_CALL
  RBF도 새 거래는 TRANSFER로 생성되므로 sweep은 원 호출 재실행 근거가 확인될 때까지 intent 생성 전 경보-only로 두었다.
- [x] **T7.6 E2E + converge** (2026-08-13) — 대체 웹훅·조회·고객 이벤트를 root txId/externalTxId로 접고,
  성공 증거가 있는 원/대체 물리 거래의 승자 hash를 반영한다. FAILED는 살릴 대체 거래가 있으면 유예하고 막힘 점검이 계열 전체를
  재조회해 성공 우선·전원 실패 때만 확정한다. 유예 시 stall 경보를 비워 재관찰을 보장하고, 미회수 REQUESTED boost는 주기 실패로
  가시화한다. PostgreSQL 경합·outbox 원자성 테스트와 전체 430건, design-sync·code-reviewer를 통과했다.

- 주기 작업(예: 5분, `bcm_job_m` heartbeat): DB에서 오래 미확정 후보를 고르고 조치 직전 벤더 단건 조회
- **미결 제출 점검 합류** (Phase 5 에서 이월) — `bcm_sbmt_l` 의 오래된 `REQUESTED` 를 훑어 벤더 `external_tx_id` 조회로 마감한다. 재시도가 오지 않은 건을 회수하는 경로다
- **벤더 `CONFIRMING` + txHash + 0 confirmation** → 자동 boost 후보 — Admin 정책·기능 게이트 안에서 RBF
- **대체 거래(새 txId)를 원 txId 로 접어 발행** — 백엔드는 boost 를 모른다 (Phase 1 의 tx 식별 설계가 여기서 회수된다)
- **체인 전 지연 또는 confirmation 1 이상 확정 지연** → 경보만. 입금·미제출 거래도 boost하지 않는다
- relay 자동 처리는 공개 문서상 보장되지 않아 boost 트리거를 유지한다. 비공개 운영 보장은 담당자 확답 전까지 #3에 남긴다.

**완료 기준**: 접기 테스트 — 대체 txId 의 웹훅이 원 txId 의 거래로 반영되고, DAW-CORE 에는 원 txId 기준 이벤트만 나감. 최대 시도 초과 시 경보 전환. boost 이력이 bcm_boost_l 에 남음.

## Phase 8 — 배치 3종 (bcm-bat)

- **tx 대사**: 벤더 `GET /v1/transactions` 페이징 (`after`=createdAt, `orderBy` 미지정 — QnA 확답 패턴) ↔ bcm_tx_l 대조. **종결 건만** (벤더 원어 COMPLETED · FAILED · 출금 REJECTED · BLOCKED). 대조 범위는 `bcm_job_m` 커서(마지막 성공 시각)로 이어붙임. 불일치 리포트 (자동 정정은 하지 않는다 — 운영 판단)
- **원본 보관 일 배치**: finalize 건의 마지막 벤더 COMPLETED 알림 payload 를 `bcm_whk_l` → `bcm_raw_tx_l`(base_dt 파티션, payload_hash) 이관 + whk_l 처리 후 N일 정리 (03 보존 규칙)
- **수수료 견적 시계열**: 주기 작업이 견적을 시계열로 기록, 제출 건에 제출 시각 시세 대응 (02 수수료 관측)

**완료 기준**: 대조 로직 단위 테스트 (일치 / 벤더에만 있음 / 우리에만 있음 / 상태 불일치). CONFIRMED 멈춤 건이 대사로 복구되는 케이스(02 — 확정 웹훅 유실 보완). 보관 배치 후 whk_l 정리·raw_tx_l 적재 검증.

### task (2026-08-13 분해)

- [x] **T8.1 종결 거래 대사·웹훅 유실 복구** (2026-08-13) — workspace 거래 목록을 `orderBy` 없이
  `createdAt` 성공 커서부터 겹쳐 페이징하고, 벤더 종결 원어만 root 거래와 비교해 일치·벤더에만 있음·매니저에만 있음·
  상태 불일치를 리포트한다. 이미 종결된 불일치는 자동 정정하지 않고, 창 밖의 오래된 `SUBMITTED`·`CONFIRMED`는
  단건 조회로 확인해 입금 outbox와 SWEEP_BATCH 항목 대사 진입을 정상 처리 경로로 복구한다.
  완료: 분류·페이징·커서 실패 단위 테스트와 PostgreSQL 입금/Sweep 복구 세로줄, 전체 440건·ktlint 그린.
  근거: 02 최종 안전망/이력·대사 · 03 웹훅 유실 복구/`bcm_job_m` · 90 트랜잭션 조회 API
- [x] **T8.2 확정 원본 보관·인박스 정리** (2026-08-13) — 배포 역할이 대상 월 전용 SQL로 월별 파티션을
  선생성하고 런타임은 DML만 수행한다. 성공 커서 경계를 포함해 FINALIZED 거래의 마지막 COMPLETED 웹훅 원문·수신 해시·
  서명을 무가공 이관한 뒤, 운영 설정 보존일이 지난 S 인박스만 정리한다. 보관·정리·성공 heartbeat는 한 트랜잭션이라
  파티션 누락이나 정리 실패 시 모두 롤백된다. 완료: 파티션 범위·멱등/최신 원본·입출금 주소·원문 byte 동일·실패 원자성
  PostgreSQL 테스트와 전체 449건·ktlint 그린.
  근거: 03 원본 보관/보존 규칙 · CLAUDE.md 수신 원문 byte 보존
- [x] **T8.3 수수료 견적 시계열** (2026-08-13) — 등록 자산별 Fireblocks network fee LOW·MEDIUM·HIGH를
  기본 비활성 5분 주기로 수집하고 `bcm_fee_qt_l`에 같은 관측 시각으로 적재한다. 30초 미만 주기는 거부하며,
  모든 벤더 응답을 먼저 받은 뒤 견적과 성공 heartbeat를 한 트랜잭션에 기록한다. 동일 초 PK 멱등, 일반 제출 MEDIUM·
  boost 저장 fee level의 요청 시각 이하 최근 견적 대응과 미래 견적 제외를 PostgreSQL 테스트로 고정했다.
  완료: 전체 456건·ktlint 그린. 근거: 02 수수료 관측 · 03 `bcm_fee_qt_l`
- [x] **T8.4 E2E + converge** (2026-08-14) — 전체 468건·ktlint 그린, OpenAPI 생성물 diff 없음과 설계 사본
  16개 byte 동일을 확인했다. design-sync 정합, code-reviewer Critical 0·커밋 가능 판정을 `01bf807`에서 통과했다.
  근거: PLAN 공통 완료 규칙

## Phase 9 — 운영 보강

- 인박스 적체·outbox P 잔량·relay 지연·`bcm_job_m` heartbeat 메트릭 + 경보
- 01 "매니저가 내보내는 신호" 표 **전 행** — 웹훅 수신 생존(마지막 수신 시각·수신 오류율·서명 검증 실패율) · **대사 누락 건수**(0 이탈 시 메트릭 + 운영 알림 — 설계 명시) · 벤더 호출 오류율(429 포함)
- tx 대사 운영 안전성 — 자동 추적 중단 즉시 알림·상시 잔량, 창 대조 종결 건의 단건 claim 쿼터 소모 방지,
  stale FAILED 관찰의 대사 중단 상태 초기화 방지
- 미보관 COMPLETED 인박스 잔류 건수와 root 비FINALIZED 장기 잔류를 가시화한다.
- resend_failed 수동 러너 (97 실측: 202 total 은 호출 시점 실패분, 배차는 분 단위)
- **웹훅 구독 상태 확인·재활성화** 도구/런북 — 99 "DB 만 다운 → 조용한 정지" 복구의 마지막 겹. 재기동 시 재전송 API 1회 자동 호출 여부(02)도 이때 결정
- stuck(`transaction.alert.stuck`) 벤더 신호를 별도 운영 경보(`transaction.stall.detected`)로 전달 + 경보 채널 구체 수단 바인딩(#12·#13 해소)
- 의존성 취약점 스캔(OWASP dependency-check 급)을 CI 에 추가 — CLAUDE.md 5절 선언의 이행

**완료 기준**: 02 신호 표 전 행이 메트릭 endpoint 에서 관측됨(로컬 E2E 로 scrape 확인). resend_failed 러너 모의 벤더 실행 검증. 재활성화 런북이 실제 절차로 검증됨(구독 비활성 모의 후 복구).

### task (2026-08-17 분해)

- [x] **T9.1 메트릭 기반·DB 신호** (2026-08-17) — `bcm-api` HTTP·`bcm-bat` JMX에 운영 메트릭 endpoint를 열고,
  인박스 판단 적체·outbox P 잔량과
  최장 지연·`bcm_job_m` 작업별 마지막 실행/성공 시각을 DB snapshot으로 수집한다. 애플리케이션 인스턴스가 여러 대여도 DB 값을
  정본으로 노출하고, 메트릭 조회가 워커 행을 잠그거나 상태를 바꾸지 않게 한다. 완료: PostgreSQL fixture로 backlog/heartbeat
  이름·값·태그와 두 Boot 애플리케이션 조립을 검증했고, 전체 472건·ktlint·API 생성물 그린. 근거: 02 "매니저가 내보내는
  신호" heartbeat·판단 적체·발행 적체, 03 `bcm_whk_l`·`bcm_outbox_l`·`bcm_job_m`
- [x] **T9.2 수신·벤더·대사 경로 신호** (2026-08-17) — 웹훅 마지막 수신 시각·수신 결과·서명 검증 실패,
  벤더 호출 결과(429 별도), tx 대사가
  복구한 웹훅 누락 건수를 counter/gauge로 기록한다. 대사 누락이 0에서 벗어나면 운영 알림 포트도 호출하며 목록·단건 조회 또는 처리
  실패 시 성공 창을 전진시키지 않는 기존 계약을 보존한다. 완료: 정상/서명 실패/수신 실패, 429/기타 벤더 실패, 누락 0/N과
  목록·단건·복구 실패를 단위·통합 테스트로 고정하고 Actuator endpoint에서 전 행을 확인했다. 전체 478건·ktlint·API 생성물
  그린. 근거: 02 "웹훅 수신"·"대사"·"매니저가 내보내는 신호", 97 서명·재시도 실측
- [x] **T9.3 대사·보관 장기 잔류 안전성** (2026-08-17) — 자동 추적 최대 나이 초과를 즉시 알리고 상시 잔량으로 노출한다. 창 대조에서 이미 종결된
  건이 단건 claim 쿼터를 소모하지 않게 하고, stale FAILED 관찰이 대사 중단 상태를 초기화하지 않게 한다. 미보관 COMPLETED 인박스와
  root 비FINALIZED 장기 잔류를 메트릭으로 노출한다. 완료: 최대 나이 중단을 벤더 조회 전에 기록·알림하고, 창 종결 root를 SQL claim 전에
  제외하며, stale FAILED의 추적 상태 보존과 동일·최신 보관 원문 제외를 PostgreSQL 회귀 테스트로 고정했다. Actuator scrape와 전체
  483건(실패·오류·skip 0), ktlint·API 생성물 검사를 통과했다.
  근거: 02 "거래 상태 대사"·"원본 보관", 03 대사 추적 컬럼, Phase 8 리뷰 후속
- [x] **T9.4 웹훅 복구 도구·런북** (2026-08-17) — `bcm-bat` JMX 수동 endpoint로 구독 상태 조회·재활성화와
  `resend_failed` 접수를 벤더 포트 뒤에 구현했다. 시작 시 자동 실행은 중복·관리자 권한 경계를 넘어가므로 두지 않았고, 필수 이벤트가
  하나라도 빠지거나 재활성화 검증이 실패하면 재전송하지 않는다. `total`은 즉시 재수신 수가 아닌 비동기 배차 수로 반환하며 운영 절차와
  5분 후 재확인, 24시간보다 오래된 공백의 tx 대사 복구를 런북에 고정했다. 관리 API 실측·설계 동기화 전에는 기본 비활성이다.
  완료: 비활성 구독 모의 → 재활성화 → resend 접수와
  JMX 조립을 검증했고 전체 494건(실패·오류·skip 0), ktlint·API 생성물 검사를 통과했다.
  근거: 02 웹훅 유실 복구, 97 `resend_failed` 실측, 99 "DB만 다운 → 조용한 정지", 미해결 #40
- [x] **T9.5 운영 경보 채널 바인딩** (2026-08-17) — API·BAT의 기존 경보 포트 11개를 데이터 토픽과 분리된 설정형 HTTP
  운영 수신기로 연결했다. `route`·`type` 계약으로 webhook·event-delivery·transaction·sweep·reconciliation·asset을 분리하고,
  식별자·enum·건수·예외 클래스만 보내며 시크릿·원문 payload·주소·금액·예외 메시지는 제외한다. 운영 채널 timeout·연결 실패·2xx 외
  응답은 원 자금/웹훅/대사 처리를 롤백하지 않고 ERROR 로그와 `bcm.operational.alert.delivery` 실패 counter를 남긴다. 활성화 상태에서
  endpoint가 없거나 잘못되면 시작을 거부한다. 완료: route 변환·민감정보 비노출·Bearer HTTP 전달·503 격리·API/BAT Spring 조립과
  전체 501건(실패·오류·skip 0), ktlint·API 생성물 검사를 통과했다. 근거: 01 별도 경보 채널, 02 막힘 점검, error-handling 규칙
- [x] **T9.6 CI 의존성 취약점 게이트** (2026-08-17) — 공식 최신 Gradle 플러그인 OWASP Dependency-Check 12.2.2로
  전체 leaf `runtimeClasspath`를 aggregate 검사하고 HTML·JSON·SARIF·JUnit 보고서를 생성한다. CVSS 7.0 이상, 분석 오류와
  미사용 suppression에서 fail-closed하며 CI는 configuration cache·parallel·build cache를 끄고 매번 task를 재실행한다. 최초 NVD
  JSON 2.0 feed 26개를 공식 `.meta` 본문 SHA-256으로 검증해 DB를 구축했다. 실검출한 pgJDBC CVE-2026-54291은 42.7.12,
  Log4j CVE-2026-49844는 2.25.5로 올렸다. Kotlin build-cache CVE-2026-53914는 실제 build cache를 차단하고 runtime JAR 오탐만
  PLAN #41·2026-09-30 만료 purl 규칙으로 제한했다. 임시 pgJDBC 42.7.11 fixture가 CVE-2026-54291로 exit 1을 내는 것과 원복 후
  정상 스캔·`./scripts/ci.sh` 전체 501건·ktlint·API 생성물 그린을 확인했다. 근거: CLAUDE.md 5절·6절, error-handling 규칙
- [x] **T9.7 converge** (2026-08-17) — Claude Code `design-sync`를 먼저 실행해 스키마·전이표·웹훅·토픽·02 신호 표
  정합을 확인하고, 공개 웹훅 포트의 Actuator 노출·경보 severity 판단·보관 gauge 필터 차이를 반영했다. 이어 `code-reviewer`의
  Critical 4건을 관리 리스너 분리, 계약 assertion 복원, 신호별 refresh 실패 격리, Webhooks V2 관리 조작면 기본 비활성+#43으로
  해소해 Critical 0·커밋 가능 판정을 받았다. 최종 `./scripts/ci.sh`는 전체 505건(실패·오류·skip 0), ktlint, 의존성 스캔,
  API 생성물 검사를 통과했다. 설계 정본 후속은 #40·#43, 성능 후속은 #44·#45로 추적한다. 근거: CLAUDE.md 6절, PLAN 공통 작업 규칙

## Phase 10 — Blockchain Manager Admin

블록체인 매니저 전용 운영 콘솔. 네트워크·자산·거래·sweep/allowance·boost를 조사하고, 매니저가 사용하는
sweep 컨트랙트·실행 정책·밴드S 이동안·비상 조치를 버전·승인·감사 경계 안에서 관리한다. 전사 권한 관리,
일반 컨트랙트 배포, TAP·multisig 직접 편집, DAW-CORE 원장·환율/NAV 정본은 범위 밖이다.

- 프론트는 BFF만 호출하고 BCM·Fireblocks·RPC를 직접 호출하지 않는다.
- Admin 정책은 배포 hard ceiling과 TAP·Callback·컨트랙트 강제를 완화할 수 없다.
- 프론트가 금액·밴드S·상태 전이를 계산하지 않고 서버가 action과 금지 사유를 제공한다.
- 변경 요청·승인·활성화·실행을 분리하며 실행 원장에 적용 정책 version/snapshot을 남긴다.
- 읽기 전용 → 변경·승인 → 밴드S 수동 승인 실행 → 비상운영 → 제한 자동화 순으로 연다.

### task (설계 초안 2026-08-14)

- [x] **T10.0 범위·UX·권한 정본** (2026-08-17) — waas-wiki `BC/설계/08-bcm-admin.md`와 영향 문서 01·02·03·06·07을
  확정하고 `docs/design/` 사본을 동기화한다. Admin/BFF 배치, 역할·정족수, 컨트랙트/RPC 증적 정본,
  밴드S 계산 주체·콜드 이동 경로를 미확정에서 해소한다. 사내 디자인 시스템이 없으므로 기본·의미·컴포넌트
  디자인 토큰을 정본화하고 Dashboard·Transaction Detail·Policy Approval·Band S Simulation 기준 화면으로 검증한다.
  완료: 사본 byte-동일 + 화면/상태/권한/비상 흐름·디자인 토큰·기준 화면 사용자 승인.
  근거: 08 Admin · 06 sweep · 07 asset master
  - 사용자 승인한 `docs/admin-reference/`의 결정안·디자인 토큰·4개 기준 화면을 정본에 반영했다. 역할별 정족수,
    mTLS+단기 JWT, 2-RPC 증적, DAW-CORE 밴드S 계산, 단일 omnibus→외부 cold와 이중 공제 금지를 확정했다.
    후속 사용자 결정으로 Frontend+BFF 소유권은 DAW-CORE와 분리한 독립 Blockchain Manager Admin으로 변경했다.
    실제 정본 `/Users/mob/Workspace/eunpus`를 `../waas-wiki`로 연결하고 6개 사본 byte-동일, OpenAPI 재생성 무변경,
    양쪽 `git diff --check`를 확인했다. Claude CLI 로그아웃으로 design-sync는 시작 전 중단됐으며 T10.1에서 인증 후 재실행한다.
- [x] **T10.1 AI 작업 경계** (2026-08-17) — Admin safety·UX·policy lifecycle rules와 admin-feature·admin-policy-change skill,
  design-sync·code-reviewer·test-writer 검사를 검증한다.
  완료: skill 형식 검증 + 대표 조회/정책변경 작업 forward test. 근거: CLAUDE.md 작업 규율 · 08 완료 기준
  - 두 skill의 `quick_validate.py`와 `agents/openai.yaml`, 검사 agent 3개의 frontmatter 검증을 통과했다. mTLS+단기 JWT,
    위험 등급별 정족수, 컨트랙트 독립 2-RPC, DAW-CORE 계산/BCM 실행과 단일 omnibus 경계를 rules·skills·agents에 보강했다.
    `docs/ai/admin-boundary-forward-test.md`의 조회/보안 변경 dry-run 2건은 안전하지 않은 입력을 거절해 모두 PASS였다.
    실제 Claude Code design-sync는 workspace 미신뢰·로그아웃으로 시작 전 중단되어 code-reviewer는 순차 규칙상 실행하지 않았다.
- [x] **T10.2 읽기 전용 기반** (2026-08-17) — 별도 Admin Frontend+BFF 셸, OpenAPI 생성 타입, 대시보드·통합검색·
  네트워크·자산 화면을 구현한다. 완료: loading/empty/error/forbidden/stale 상태와 URL 필터 보존 E2E. 근거: 08 정보 구조·UX
  - DAW-CORE와 별도로 배포하는 `bcm-admin` 모듈을 추가했다. FUNCTION_TEST 모드는 Admin bind와 BCM target이 모두
    loopback일 때만 시작하고 BCM의 기존 읽기 API만 호출하며, 공유 환경 모드는 mTLS+5분 이하 단기 JWT 구현 전까지
    fail-closed한다. 브라우저는 `/bff/admin/*`만 호출하고 상태 변경 route는 제공하지 않는다.
  - 저장소 OpenAPI에서 BCM Admin 응답 타입을 생성하고 freshness를 `check`에 연결했다. Kotlin 12건·Node 2건,
    모듈 check·전체 ktlint·API 생성물 검사를 통과했고, 실제 Spring 서버와 Chrome에서 네 화면의 조회 응답 및
    `q=base&chainId=8453&adopted=true&testnet=false` URL 필터 복원을 확인했다. 정본 영향 문서 01·02·07·08과 사본은 byte-동일하다.
- [x] **T10.3 운영 조사** (2026-08-17) — 거래 타임라인, reconciliation·boost, sweep 실행 1:N,
  allowance·fee quote 연결 조회를 구현한다. 완료: 식별자 하나로 제출→웹훅→발행→대사→boost/sweep을 재현하는
  PostgreSQL+UI E2E. 근거: 02·03·08
  - root·active·external transaction ID, boost 교체 ID, sweep 실행/외부/벤더 거래 ID를 단일 조사 식별자로 해석하고,
    구조화된 제출·웹훅·outbox·대사·boost·sweep·allowance·당시 fee quote만 UTC 타임라인으로 반환한다. 원문 payload,
    signature, callData, 벤더 자산 ID는 계약과 화면에서 제외했다.
  - 독립 Admin BFF와 거래 상세 화면을 연결해 root/active 구분, 로컬+UTC 시각, 1:N 스윕, 전체 ID 복사,
    FRESH/STALE/PARTIAL/404를 표시한다. 상세 소스는 100건 상한과 소스별 `TRUNCATED` 근거를 갖는다.
  - PostgreSQL 어댑터 3건, 실제 PostgreSQL→BCM API 통합 1건, OpenAPI 계약 2건, BFF 서비스/컨트롤러·HTTP E2E,
    Node 상태 3건을 포함한 `./gradlew check` 110 task와 전체 ktlint·API 타입 freshness, 실제 Headless Chrome 데스크톱·
    500px 반응형 렌더링이 통과했다.
- [x] **T10.4 컨트랙트·정책** (2026-08-17) — 불변 컨트랙트 레지스트리, 온체인 검증·외부 drift, 버전 정책, 변경 요청·diff·
  Quorum 승인·활성화를 구현한다. 완료: 요청자 분리·stale snapshot·동시 활성화·hard ceiling fail-closed 테스트.
  근거: 03·06·08
  - 컨트랙트/검증 증적/정책/변경 요청/결정/Admin action의 append-only 원장 6개와 활성 binding 2개를 V2로 추가하고,
    요청자-승인자 분리·위험도별 정족수·결정 중복 방지·단일 활성화를 도메인과 PostgreSQL 제약으로 함께 방어한다.
  - 컨트랙트 활성화 직전에 독립 RPC 2곳의 runtime code hash를 다시 수집한다. 불일치·오류·승인 후 drift는 증적만 남기고
    binding을 만들지 않는다. 정책은 배포 hard ceiling을 넘거나 ceiling 설정이 없으면 fail-closed한다.
  - 동일 idempotency key 재시도는 같은 결과를 반환하며, 동시 활성화는 정확히 한 건만 성공한다. OpenAPI/BFF/Admin에는
    서버가 계산한 상태·diff·영향·정족수·증적을 읽기 전용으로 노출했다. mTLS+단기 JWT 전에는 변경 HTTP route를 열지 않는다.
  - 도메인·PostgreSQL·BCM API·BFF HTTP 기능 테스트와 stale/hard ceiling/2-RPC drift 시나리오, 전체
    `./gradlew check` 110 task, ktlint·OpenAPI/API 타입 freshness가 통과했다.
- [x] **T10.5 밴드S** (2026-08-17) — 입력 snapshot·현황·simulation·이동안·수동 승인·멱등 실행·완료 대사를 구현한다.
  완료: stale/누락 입력 차단, simulation/실행 snapshot 동일성, 정책 상하한·부분 실패 E2E. 근거: 06 밴드S · 08
  - 2026-08-17 기반 완료: 03 정본에 DAW-CORE 계산/BCM 검증 경계를 반영한 immutable snapshot·proposal·item·execution·
    event 물리 계약을 추가하고 V3 6개 테이블과 append-only, 자금 실행 정족수, 만료·완전성·활성 정책, 연속 event 제약을 구현했다.
    누락·만료·정책/input hash 불일치, 단일 omnibus·고정 cold 목적지, dependency, 부분 실패 상태 파생의
    도메인 테스트와 PostgreSQL 제약 테스트, 총 30개 테이블 부트스트랩 및 관련 ktlint가 통과했다.
  - snapshot/proposal source ID 멱등 수신, 활성 정책·서버 생성 hash 검증, `BAND_S/FUND` 요청과 기존 독립 승인 lifecycle,
    실행 intent·item 전체 reservation·응답 유실 재조회, 연속 제출/관찰 event와 `PARTIAL` 대사를 application/persistence로 연결했다.
    실제 PostgreSQL 정책 활성화→DAW 입력→승인→예약 재시도→item 완료/실패 세로줄과 기존 Admin 관련 회귀가 통과했다.
  - `GET /admin/band-s`와 독립 Admin BFF/UI를 연결해 입력·정책·제안 hash, 밴드 비율·이동안, 승인·예약, item별 최신
    실행 상태를 한 응답으로 표시한다. 서버가 `BLOCKED/STALE/PENDING/APPROVED/REJECTED/EXPIRED/EXECUTING/PARTIAL/COMPLETED/FAILED`와
    금지 사유를 파생하며 브라우저에는 mutation route를 열지 않았다. OpenAPI 계약, 실제 PostgreSQL `PARTIAL` 조회,
    BFF HTTP E2E, Node route와 전체 `./gradlew check` 110 task가 통과했다. 테스트 DB 풀은 컨텍스트당 2개로 제한했다.
  - item마다 결정적 externalTxId와 `SUBMIT_INTENT`를 먼저 남긴 뒤 기존 claim·400 조회·응답 유실 회수 경계로 제출한다.
    제출 원장 계열은 `BAND_S`로 분리해 고객 토픽에 발행하지 않으며, 내부 vault 이동과 고정 cold 주소를 원 제안 그대로
    벤더에 전달한다. cold deposit은 외부 관찰 전용으로 차단하고 확정 거절만 item `FAILED`로 마감한다.
  - PostgreSQL+모의 벤더 E2E가 원장 선기록, 제출 멱등, 부분 실패 대사를 검증한다. 같은 실행 예약의 동시 요청은 정책
    바인딩 잠금 뒤 재조회해 단일 실행 ID로 수렴한다. stale·dependency·누락 입력 도메인 계약과 전체 `ktlintCheck check`
    110 task, 설계 02·03 정본/사본 byte 동일, `git diff --check`가 통과했다.
- [ ] **T10.6 비상 운영** — 네트워크/sweep/approve 중지, 웹훅 구독 복구·`resend_failed`, 컨트랙트 외부 pause 확인,
  전체 `approve(0)` 회수와 강화된 재개 승인을 구현한다.
  완료: 중지→회수→외부 상태 재조회→재개 장애 훈련. 근거: 06 비상 회수 · 07 네트워크 장애 · 08
  - [x] **T10.6.0 실행 게이트 원장** — 네트워크별 `WITHDRAWAL/SWEEP/APPROVE` 신규 실행 중지를 append-only로 기록하고,
    운영자 역할·사유·작업 티켓·멱등·동시 중지를 Domain과 PostgreSQL 제약으로 방어한다.
    - V4 `bcm_exec_gate_evt_l`과 append-only trigger, 범위 sequence·작업자 멱등 UNIQUE를 추가했다. 운영자 단일 중지와
      PostgreSQL 동시 요청의 단일 event 수렴, 빈 DB 부트스트랩을 검증했다. 정본 03을 먼저 고치고 사본과 byte-동일하게 맞췄다.
  - [x] **T10.6.1 실행 경로 차단·조회** — 출금 제출만 선택적으로 막고 입금 감지·주소·잔액·이미 제출된 거래는 유지한다.
    sweep batch와 정상 allowance approve는 각각 차단하되 비상 `approve(0)` 회수는 열어 둔다. 서버 계산 상태를 조회에 노출한다.
    - 출금·sweep batch·정상 allowance approve의 신규 실행만 차단하고 기존 REQUESTED/SUBMITTED·SUBMITTING과
      APPROVING/REVOKING 관찰·동일 ID 회수를 유지한다. 회수 완료 뒤 신규 cap 승인은 중지 상태에서 시작하지 않으며 비상
      `approve(0)`은 별도 안전 게이트로 열어 뒀다.
    - `GET /admin/execution-gates`와 BFF·`/admin/emergency` 읽기 화면은 채택 네트워크별 3개 범위의 OPEN/STOPPED,
      중지 감사 문맥과 신규 실행·기존 복구·비상 회수 허용 여부를 서버 계산값으로 표시한다. mutation route는 만들지 않았다.
      OpenAPI paths 18/schemas 59, 전체 `check` 110 task, ktlint, 생성물 freshness와 `git diff --check`가 통과했다.
  - [x] **T10.6.2 외부 pause 관찰** — TAP batch 차단·컨트랙트 pause·운영자 제거는 직접 서명하지 않고 외부 상태 재조회와
    증적만 기록하며 실패·drift·미확인은 완료로 처리하지 않는다.
    - V5 `bcm_ext_ctrl_evdc_l`에 TAP 관리면과 pinned block 독립 RPC 2곳의 pause·빈 운영자 집합 hash를 한 snapshot으로
      append-only 저장한다. 작업자 멱등, 독립 endpoint, 상태·hash·CONFIRMED 물리 제약과 갱신/삭제 거절을 PostgreSQL로 방어한다.
    - 서버는 `CONFIRMED/DRIFT/STALE/UNCONFIRMED/ERROR`와 issue를 계산하고 외부 source 미설정·예외도 민감정보 없는 ERROR
      증적으로 남긴다. `GET /admin/execution-gates`와 BFF·비상 화면에는 네트워크별 최신 증적만 읽기 전용으로 표시하며
      mutation route는 열지 않았다. 조회 시 만료를 STALE로 재계산하고 외부 호출은 DB 트랜잭션 밖에서 수행한다.
      OpenAPI paths 18/schemas 60, CI build 116 task·ktlint·Dependency-Check를 통과했고 design-sync 정합·code-reviewer Critical 0이다.
  - [x] **T10.6.3 allowance 전량 회수** — 승인된 자금 실행 요청으로 vault별 `approve(0)`을 선기록·멱등 제출하고,
    진행 중 batch가 없으며 온체인 allowance 0을 재확인할 때까지 부분 진행 상태를 유지한다.
    - V6에 회수 실행 snapshot·item·event 3개 append-only 원장과 `ALLOWANCE_REVOKE/FUND` 요청 연결을 추가했다. 요청·예약·제출
      직전에 활성 binding, 양수 allowance 대상 완전성, source vault·owner·token drift, 진행 batch·active item을 DB guard로 막는다.
    - 요청자 외 독립 승인 뒤 전 항목 예약, `SUBMIT_INTENT`+기존 제출 원장 REQUESTED 원자적 선기록, 같은 externalTxId 응답 유실
      회수, 항목 실패 격리와 온체인 0 재확인만으로 COMPLETED 파생을 연결했다. 0이 아닌 재관찰도 현재 projection을 갱신한다.
    - `GET /admin/execution-gates`와 BFF·비상 화면은 전체/0 확인/제출 중/실패 건수, vault별 최신 event와 관찰값·시각을 읽기 전용으로
      표시한다. OpenAPI paths 18/schemas 62, 관련 5개 모듈 전체 테스트와 `check ktlintCheck` 110 task가 통과했다.
  - [x] **T10.6.4 웹훅 복구** — 구독 상태 재조회·재활성화·`resend_failed` 수동 실행을 접수/결과로 분리하고 호출 시각·범위·응답을 감사한다.
    - V7 요청·event append-only 원장은 요청자와 독립 승인자, 필수 이벤트 snapshot/hash, 호출 intent와 구조화 결과를 보존한다.
      연속 sequence·허용 전이·필드 조합·필수 이벤트 충족과 요청자/승인자 분리를 PostgreSQL guard로 재검사한다.
    - 기본 비활성 JMX는 접수와 실행 endpoint를 분리한다. 각 벤더 호출 전에 intent를 별도 transaction으로 commit하고 4xx는 안전한
      실패 코드로 종결하며 5xx·응답 유실은 자동 재호출하지 않고 timeout 뒤 AMBIGUOUS로 계산한다. 재전송 범위는 호출 시각 기준 24시간이다.
    - `GET /admin/execution-gates`·BFF·비상 UI는 승인 문맥, 상태·호출/결과 시각, 구독 전후·누락 이벤트, 범위·예약 건수·오류를
      읽기 전용 표시한다. OpenAPI paths 18/schemas 63, 부트스트랩 37개 테이블, 전체 641건과 `check` 110 task가 통과했다.
  - [x] **T10.6.5 강화 재개·훈련** — 원인 해소·최신 snapshot·외부 drift 없음·회수 완료를 재검사하고 요청자 외 승인자 2명과
    보안 승인자 1명의 정족수 뒤에만 재개한다. 중지→외부 조치→회수→재개 장애 훈련 E2E로 닫는다.
    - V8 재개 요청·외부 재검사 append-only 원장은 현재 STOPPED, 활성 SWEEP binding, 최신 VALID contract evidence, 완료된 allowance
      회수, 원인 증적과 기대 운영자 hash를 고정한다. RESUMED trigger가 최신성·READY check·강화 정족수·RESUME intent를 재검사한다.
    - application service는 TAP과 pinned block 독립 RPC 2곳을 transaction 밖에서 새로 읽고, 멱등 입력 변경·drift·stale·회수 미완료·
      단일 승인·자기 승인·동시 재개를 fail-closed한다. 성공 event 뒤에만 게이트가 OPEN이며 브라우저 mutation route는 열지 않았다.
    - `GET /admin/execution-gates`·BFF·비상 UI는 재개 상태와 승인/보안 정족수, 최신 check·원인 증적·금지 사유를 읽기 전용 표시한다.
      OpenAPI paths 18/schemas 64, 부트스트랩 39개 테이블, JUnit 654건+프론트 6건과 `check` 110 task·ktlint가 통과했다.
- [x] **T10.7 E2E + converge** — 권한·접근성·UTC·민감정보·중복 클릭·동시 승인·장시간 작업 UX와 네트워크별
  제한 출시 게이트를 검증하고 design-sync→code-reviewer를 통과한다. 근거: PLAN 공통 완료 규칙 · 08 완료 기준
  - 브라우저/BFF E2E는 키보드·스크린리더 경계, 로컬 시각+UTC 원문, 중복 실행 방지, 장시간 상태·금지 사유,
    민감정보 비포함과 서버 판정 기반 네트워크 제한 출시를 고정했다.
  - V9/V10과 application 경계는 활성 policy·contract·evidence snapshot, cap 하향, 실행 게이트, 신규 sweep batch와
    `READY→SUBMITTING`, 출금 REQUESTED 회수·FAILED 재시도를 같은 잠금 순서와 최신 statement snapshot으로 재검사한다.
  - JUnit 689건+프론트 13건(실패·오류·skip 0), CI 116 task·ktlint·OpenAPI freshness가 통과했고 독립
    design-sync와 code-reviewer가 전체 범위 `1ec44fa..b1e5719`을 Critical 0/Major 0으로 판정했다.

**완료 기준**: 운영자가 조사→변경 요청→승인→실행→대사→감사를 재현하고, 정책·컨트랙트·밴드S·비상 조치가
DB 동시성 방어·외부 drift 검사·hard ceiling·fail-closed·사용자 흐름 E2E를 통과한다.

## Phase 11 — 로컬 블록체인 + Fireblocks 통합 테스트 환경

Fireblocks 사용 가능 여부와 무관하게 계속 사용하는 BCM 전용 통합 테스트 장치다. 단순 fixture 응답기가 아니라 기존
`FireblocksClient`의 HTTP 계약, 상태 전이·웹훅·복구와 실제 EVM 잔액·receipt·event log를 함께 검증한다. 운영 벤더 구현을
교체하는 Domain Port나 프로덕션 코드의 테스트 전용 분기는 만들지 않는다.

### 확정 경계 (2026-08-18)

- 코드는 이 저장소의 독립 `test-support` 영역에 두고, `FireblocksClient`의 Base URL과 EVM RPC만 환경 설정으로 바꾼다.
- 허용 조합은 `STUB+LOCAL`, `FIREBLOCKS+TESTNET`, `FIREBLOCKS+MAINNET`이며 `FIREBLOCKS+LOCAL`과 `STUB+MAINNET`은
  시작 검증에서 거부한다. 같은 실행 중 Admin에서 모드를 바꾸지 않는다.
- 원격 대상은 **폐쇄망 일반 Linux 서버**다. Docker를 요구하지 않고 Anvil 실행 파일, Stub fat JAR, chain bootstrap,
  컨트랙트 artifact, config, systemd unit, start/stop/reset/health-check를 버전·checksum이 있는 `tar.gz`로 배포한다.
- 원격 BCM은 이미 설치·운영 중인 PostgreSQL·Kafka를 사용한다. 로컬 체인 배포 파일은 둘을 포함·설치·초기화하지 않는다.
  PostgreSQL·Kafka 컨테이너는 개발자 PC와 CI의 전체 E2E에서만 사용한다.
- 원격 `reset`은 Stub 상태와 Anvil snapshot/seed만 다룬다. 기존 PostgreSQL·Kafka의 BCM 데이터를 지우지 않으며, 전체 E2E
  초기화가 필요하면 전용 테스트 DB/topic과 실행 ID 범위를 갖춘 별도 BCM 절차로 수행한다.
- Stub·Anvil·bootstrap은 외부 통신 없이 loopback/서버 내부 포트로만 노출한다. 실제 Fireblocks Secret과 실제 RPC URL이
  들어오면 로컬 모드는 시작을 거부한다. 폐쇄망 반입 artifact는 연결 환경에서 미리 빌드·검사하고 고정 버전으로 전달한다.
- Fireblocks API 요청 JWT는 기본 로컬 경로에서 API key·Bearer 존재와 payload 형식만 확인한다. RS256·nonce·bodyHash 오류는
  작은 인증 계약 테스트로 분리한다. Webhook은 기존 BCM 검증 경로를 우회하지 않도록 단일 로컬 키·JWKS로 RS512 서명한다.
- 블록체인 트랜잭션은 Stub의 결정적 테스트 EVM 키로 실제 서명해 Anvil에 raw transaction을 제출한다. API RSA 키,
  Webhook RSA 키, EVM secp256k1 키는 서로 분리하고 Git에 시크릿을 저장하지 않는다.
- EVM/ERC-20을 1차 범위로 한다. EVM의 BAND ERC-20은 포함 가능하지만 네이티브 BandChain은 별도 로컬 체인 과제로 둔다.
- Universal Gasless 로컬 검증은 EIP-7702 프로토콜 동작과 BCM의 관측 결과를 대상으로 한다. Fireblocks MPC·TAP·실제 relayer를
  복제하거나 동일하다고 주장하지 않으며, 확정된 `approve + transferFrom` batch sweep을 EIP-7702 직접 pull로 바꾸지 않는다.

### task (계획 2026-08-18)

- [ ] **T11.0 계약·설계 정본** (2~3인일) — BCM이 실제 사용하는 Vault/Asset/Transaction/Fee/Webhook API, 상태·오류·필드와
  실제/시뮬레이션/미지원 경계를 표로 고정한다. waas-wiki에 로컬 통합환경 설계를 먼저 작성하고 `docs/design/` 사본은 정본
  동기화로만 반영한다. 완료: 지원표·배포 조합·키 경계·reset 소유권·실 Fireblocks 계약 테스트 승인 경계 사용자 승인.
- [ ] **T11.1 테스트 모듈·실행 모드 기반** (3~4인일) — `test-support` 독립 실행 모듈, STUB/FIREBLOCKS와
  LOCAL/TESTNET/MAINNET 조합 검증, 내부 포트·health check를 만든다. 완료: 잘못된 조합·실 Secret·외부 RPC가 로컬 모드에서
  fail-closed하고 기존 FireblocksClient가 코드 분기 없이 Stub URL을 호출하는 조립 테스트 그린.
- [ ] **T11.2 결정적 로컬 체인** (4~5인일) — Anvil, 고정 chain ID·계정, 테스트 ERC-20, 운영과 같은 ABI의 Sweep
  컨트랙트, 배포 manifest, seed와 snapshot/revert를 만든다. 완료: 같은 seed가 같은 주소·잔액·컨트랙트를 만들고
  approve→batchSweep의 성공·부분 결과 event를 실제 receipt에서 재현한다.
- [ ] **T11.3 상태형 Fireblocks Stub** (5~7인일) — Vault/주소/잔액, 체인·자산 카탈로그, TRANSFER/CONTRACT_CALL,
  ID·externalTxId·목록 조회, fee 견적, Webhook 조회/변경/재전송을 현재 BCM 사용 범위만 구현한다. 완료: externalTxId 멱등,
  transaction 상태 머신, 실제 Anvil hash·receipt, 최소 Webhook 서명/JWKS 계약 테스트 그린.
- [ ] **T11.4 BCM 거래·sweep 세로줄** (4~5인일) — 입출금·내부이체, allowance approve, batch sweep 1:N,
  network records·`SweepLeg` 대사와 고객 토픽 비발행을 실제 BCM API/BAT 경로로 연결한다. 완료: 정상 및 batch 부분 결과가
  기존 원장·상태·대사 계약과 일치하고 Stub 전용 Domain Adapter가 없음을 아키텍처 테스트로 고정.
- [ ] **T11.5 실패·복구·초기화** (4~6인일) — HTTP 4xx/429/5xx·timeout·응답 유실, 중복/역순/유실 Webhook,
  BLOCKED/REJECTED/FAILED·장기 pending, 잔액/gas/allowance/nonce/revert, 동시 제출과 재대사를 시나리오화한다. CI는 매 실행
  폐기하고 원격 reset은 Stub+Anvil만 복원한다. 완료: `REAL_LOCAL`·`SIMULATED_VENDOR`·`REAL_FIREBLOCKS_ONLY` 분류와
  같은 seed의 결정적 재현, 기존 PostgreSQL·Kafka 무변경 검증.
- [ ] **T11.6 파일 배포·CI·실벤더 계약 검사** (3~5인일) — CPU 아키텍처별 Anvil, 전용 JRE/Stub, artifact와 systemd를
  checksum manifest가 있는 tar.gz로 만들고 폐쇄망 반입·설치·롤백 런북을 제공한다. 개발자/CI는 기존 Testcontainers의
  PostgreSQL·Kafka와 로컬 체인을 묶는다. 실제 Sandbox 호출은 사용자 명시 승인과 Secret이 있는 별도 작업에서만 golden
  contract test로 수행한다. 완료: 깨끗한 일반 Linux 서버 설치→기동→reset→재기동 smoke와 오프라인 무다운로드 검증.
- [ ] **T11.7 Universal Gasless** (14~22인일) — 먼저 Anvil Prague·사용 라이브러리의 EIP-7702 type-4 지원을 spike한다.
  통과하면 테스트 delegation 컨트랙트, Vault authorization/실행 의도, fee payer, replay/nonce/deadline 방어와 가스 대납
  실패를 구현한다. source native 0에서 gasless approve와 확정 batch sweep 경로를 검증하되 Fireblocks 내부 동작은 계약 결과만
  시뮬레이션한다. 완료: 정상·fee payer 부족·만료·재사용·내부 revert와 실제 Fireblocks 전용 미검증 항목의 명시적 구분.
- [ ] **T11.8 E2E + converge** — 한 명령 또는 systemd로 기동·상태 확인·reset·종료하고, 로컬/CI/원격 폐쇄망 세 경로의
  기능·장애·보안 경계를 검증한 뒤 design-sync→code-reviewer를 통과한다. 근거: PLAN 공통 완료 규칙.

**예상 공수**: 기본 통합 플랫폼 27~38인일 + Universal Gasless 14~22인일 = 총 41~60인일. 1명은 약 9~12주,
Kotlin 백엔드 1명과 EVM/Solidity 인력 0.5~1명이 병렬 작업하면 약 5~7주다. 운영 Sweep artifact가 없으면 5~10인일,
네이티브 BandChain까지 포함하면 10~18인일을 별도 추가한다.

**완료 기준**: 원격 파일 패키지가 Docker와 번들 PostgreSQL·Kafka 없이 폐쇄망에서 실행되고, 기존 BCM이 설정만으로 Stub/Anvil을
사용한다. 정상·실패·복구·batch sweep·gasless 경로가 실제 EVM 결과와 일치하며, 실제 Fireblocks 전용 보안·정책 동작은 과장 없이
별도 계약 테스트로 남긴다.

## 스펙-설계 불일치 · 미해결 (구현 전/중 해결)

| # | 내용 | 상태 |
|---|---|---|
| 1 | **ChainEvent 에 이벤트 id 없음** — openapi.yaml 은 "txId 또는 externalTxId 유일 기준", 02-bcm-flow 는 컨슈머 dedup 키 = `evnt_id`(outbox UUID v7) | ✅ 해결 (2026-08-04) — `eventId` 필수 필드 추가, dedup 문구 정정, v0.0.3 재생성 |
| 2 | **poison 웹훅 격리 방식** | ✅ 해결 (2026-08-07) — `bcm_whk_l.prcs_stcd` P/S/F + `rtry_cnt` + `err_msg`. V1과 수신 초기값(P·0)을 반영, 워커는 Phase 4에서 구현 |
| 3 | **relay 의 stuck 자동 처리 여부** — 자동이면 막힘 점검의 boost 트리거를 뺀다 | 🟡 공개 문서 기준 임시 해결 (2026-08-12) — 자동 boost는 Gas Station auto-fueling에만 명시되고, 일반 EVM은 stuck 알림+RBF API를 안내한다. 일반 gasless relay 자동 처리를 보장하지 않는 것으로 보고 트리거를 유지하되 담당자 확답 전까지 미해결 유지 |
| 4 | **귀속 불명 해소 절차** — 매핑 갱신 트리거·해소 후 이벤트 재흘림 | DAW-CORE 정합 후 확정 (02 미확정) — Phase 4 는 통지까지만 |
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
| 15 | **REJECTED 이벤트의 `evt_typ_dvcd` 미정** | 🟡 임시 해결 (2026-08-07) — 코어 회신 전까지 `TXRJ`를 `OutboxEventType` 한 곳에서 관리·발행. 회신 후 그 상수만 확정 또는 교체 |
| 20 | **(network, symbol) → 벤더 assetId 변환 표** | ✅ 해결 (2026-08-06) — 07-asset-master의 `bcm_vndr_ast_m`. 벤더 경계에서 DB 조회, 미등록은 `ASSET_NOT_SUPPORTED`; 실제 assetId 값은 Admin 조회·검증 후 등록 |
| 21 | **입금 주소 memoTag 비영속** — 스펙 Address.memoTag(Tag/Memo 체인용)가 있으나 03 `bcm_addr_m` 에 태그 컬럼이 없어 발급 후 재조회에서 돌려줄 수 없다. EVM 한정이면 무해(항상 null) — Tag/Memo 자산 지원 시 03 개정 필요 | Tag/Memo 자산 채택 시 — 설계(waas-wiki 03) 질의 |
| 22 | **GET 오퍼레이션의 400 이 스펙 응답 표면에 없음** (T2.5 design-sync) — 코드는 경로변수 maxLength 초과를 400 처리(파라미터 스키마는 스펙에 있음), `depositAddressOf`·`balanceOf` 응답 표면은 200·404 만 — 스펙 내부 비일관. 스펙에 400 추가 또는 GET 검증 제거 | 차기 스펙 개정 시 — 사용자 결정 |
| 23 | **balanceOf — "계정 있음·자산 지갑 미발급" 케이스 계약 미정의** (T2.5 design-sync, 중) — 벤더 4xx → VendorApiException → 500 으로 떨어짐. `depositAddressOf` 는 같은 구분을 `data: null` 로 명시하는데 balanceOf 는 침묵. DAW-CORE 가 주소 발급 전 잔액 조회 시 500 | 차기 스펙 개정 시 — DAW-CORE 정합 포함 사용자 결정 |
| 24 | **생성 오퍼레이션의 409 가 스펙 표면에 없음** (T2.5 design-sync) — UNIQUE 경합 후 재조회마저 실패하는 극단 경로에서 409 전파, 스펙 CONFLICT 는 submitTransaction 에만 표기. 정상 운영 도달 불가한 방어 경로 | 차기 스펙 개정 시 |
| 25 | **벤더 생성 성공 + 로컬 insert 실패(비-충돌) 복구 경로** (T2.5 code-reviewer M3) — 멱등 창(24h) 이후 재시도가 벤더 "이미 존재" 4xx → 500 영구 반복(고아 vault·지갑). "already exists" 식별 fallback 또는 벤더-로컬 대사 항목 필요 | Phase 8 대사 설계 시 함께 — 또는 조기 fallback 구현. **참고: 제출 경로는 같은 문제를 "원장 먼저, 벤더 나중 + 벤더 조회로 회수"로 풀었다(02 출금 절·#6)** — 계정·주소 생성에도 같은 형태를 쓸지 검토 |
| 26 | **일시 14자 컬럼의 zone 규약 (KST vs UTC)** (T2.5 code-reviewer C1) | ✅ 해결 갱신 (2026-08-14) — **모든 DB `_dttm`·`_dt`·`base_dt`를 UTC로 통일.** Clock 빈 2곳은 `Clock.systemUTC()`이고 벤더 epoch도 공통 유틸에서 UTC로 변환한다. API는 ISO 8601 UTC(`Z`), 화면·정산·보고서에서만 필요한 시간대로 변환한다. 2026-08-06 KST 결정은 대체됐다. |
| 27 | **Admin API OpenAPI 계약 부재** | ✅ 해결 (2026-08-06) — 스펙 v0.2.0에 벤더 중립 Admin API 7개와 요청·응답 스키마 확정, 스펙 자동 대조 테스트 추가 |
| 28 | **매핑 삭제와 주소 발급의 동시성** — 삭제 전 `existsByAsset` 확인과 DELETE가 단일 트랜잭션/제약이 아니어서, Admin 삭제와 최초 주소 발급이 정확히 경합하면 TOCTOU 가능. 장기 벤더 호출 동안 잠글지, FK/예약 상태를 둘지 설계 필요 | 실자산 운영 전 확정 |
| 29 | **물리 삭제 Admin 감사 흔적** — DELETE는 직원·부점 헤더를 필수로 받지만 `bcm_vndr_ast_m` 행을 물리 삭제하면 감사 4컬럼도 함께 사라진다. 삭제 이력 표·논리 삭제·외부 감사 로그 중 보존 방식을 07/03에서 확정해야 한다 | 실자산 운영 전 — 설계 결정 대기 |
| 30 | **카탈로그 동기화 다중 인스턴스 실행 제어** — 현재 각 bcm-bat 인스턴스의 `@Scheduled`가 동시에 실행될 수 있다. 중복 실행을 허용할지, `bcm_job_m`/DB lock으로 단일 실행할지 배치 운영 규약 확정 필요 | bcm-bat 다중 인스턴스 배포 전 |
| 31 | **07의 Fireblocks 응답 필드 위치 정정** — 공식 OpenAPI 실물은 체인 폐기 여부가 `metadata.deprecated`, 자산 소수 자릿수가 `onchain.decimals`인데 07 하단 표는 평면 필드처럼 적혀 있다. 구현은 중첩·평면 decimals를 모두 읽어 호환하고, 설계 사본 정정은 waas-wiki 담당 | 다음 설계 동기화 시 |
| 32 | **Admin 일시 예시 자릿수** — OpenAPI `Network.syncedAt`·`AssetMapping.registeredAt` 예시는 12자인데 확정 규약과 실제 응답은 `yyyyMMddHHmmss` 14자다. 스키마 제약은 없지만 생성 문서가 잘못된 예시를 노출한다 | 다음 스펙 개정 시 14자로 정정 |
| 33 | **자산 후보 조회 fan-out과 벤더 rate limit** — network 생략 시 채택 네트워크별로 자산을 끝까지 페이징해 워크스페이스 공용 quota를 쓴다. 지원 네트워크가 늘기 전에 조회 상한·캐시/격리·Admin 호출 제어를 운영 규약으로 확정해야 한다. 실패를 조용히 건너뛰는 방식은 금지 | 지원 네트워크 확대 전 |
| 34 | **거래 목록 커서의 벤더 조합 동작 미실측** — ① 정렬 지정 시 next 커서가 오는가 ② next 가 `sourceType`/`sourceId` 필터를 보존하는가 ③ next 와 필터를 함께 보내도 되는가. 공식 API 에 파라미터는 있으나 **조합은 실측 없음**. **외부 계약은 벤더와 분리 완료** — 매니저 커서에 최초 필터·정렬과 `(createdAt, txId)` 위치를 담고, 벤더 커서는 한 HTTP 요청 안의 내부 페이징에만 쓴다. 마지막에도 nextCursor를 발급하며 동일 시각 거래·asc 증분 회귀 테스트가 있다. 내부 페이징은 커서마다 발신 vault 필터를 재전송하고 응답 vault가 다르면 전체 거절한다 | sandbox 실측 — 내부 페이징 조합 확인 |
| 35 | **제출 직후 조회·알림의 빈 필드** — 벤더 문서상 `sourceAddress`·`destinationAddress` 는 체인 등장 전 비어 있을 수 있다. 우리 PoC 는 입금 `CONFIRMING` 부터라 그 구간 미관측. **스펙은 nullable 로 열었다**(v0.6.0 — `Transfer.from`/`to`, `ChainEvent.to`). 근거: 열지 않으면 제출 응답을 못 받았을 때 쓰는 `transactionByExternalTxId` 가 바로 그 시점에 깨진다. ★ **`amountInfo.amount` 가 제출 직후에도 항상 있는지는 미확인** — 없으면 현재 파서가 필수로 읽어 그 알림이 격리된다 | Phase 5 E2E 실측 — 결과에 따라 파서·스펙 조정 |
| 36 | **내부이체(delta)의 대납 적용 여부** — 출금·sweep 은 대납 근거가 설계에 있으나(02 출금 시퀀스 · 06 수수료 표) INTERNAL 은 없다. **확인 전까지 켜지 않는다**(근거 없는 설정을 넣지 않는다 — 안 켜도 된다고 확인한 것은 아니다). 대납 없이 가면 출발 vault 에 native 가 있어야 하고, 없으면 `INSUFFICIENT_FUNDS_FOR_FEE` 로 실패한다 | Phase 5 내부이체 E2E 전 — 벤더·운영 확인 |
| 37 | **출금 요청 본문 크기 상한** — `note`와 구조가 아직 불투명한 `travelRule`에 스키마 상한이 없어 큰 JSON이 벤더 호출·claim 점유를 늘릴 수 있다. 구현이 임의로 필드 상한을 만들면 OpenAPI보다 좁아지므로, 전체 HTTP 본문 상한과 필드별 상한·초과 응답(400/413)을 스펙에서 먼저 확정해야 한다 | 실트래픽 연동 전 — waas-wiki/OpenAPI 결정 |
| 38 | **막힘 상태·RBF hash 보관 불일치** | ✅ 해결 (2026-08-12) — DB 상태는 후보 선별만 하고 조치 직전 벤더 단건 조회로 `CONFIRMING`·txHash·0 confirmation을 확인한다. `bcm_tx_l`은 root 한 행에 active tx id/hash를 보관하고, stuck 웹훅은 선택적 가속 신호일 뿐 correctness 기준으로 삼지 않는다. waas-wiki 02·03·99와 사본 동기화 완료 |
| 39 | **sweep CONTRACT_CALL canonical `cc-v1` 재계산 불가** | ✅ 해결 (2026-08-13) — `bcm_sbmt_l.call_data TEXT`에 정규화된 calldata를 저장하고 SWEEP_APPROVE/SWEEP_BATCH 존재·소문자 짝수바이트 hex를 DB와 코드에서 강제한다. 원장 왕복 후 cc-v1 hash 재계산 테스트로 고정. waas-wiki 03 `fe92927`, 사본 byte-동일·design-sync 통과 |
| 40 | **Webhooks V2 재전송 기간 문구 불일치** — 02·90은 `resend_failed`를 원 이벤트 30일 내로 서술하지만 2026-08-17 공식 endpoint reference는 이 API를 최근 24시간 실패 알림 대상으로 제한하고, migration guide의 최대 30일은 resource/query 재전송까지 포함한다 | T9.4 수동 러너는 `resend_failed` 기본 24시간 범위만 사용하고 오래된 공백은 tx 대사로 복구. 다음 waas-wiki 동기화에서 API별 기간을 분리 정정 |
| 41 | **CVE-2026-53914 Kotlin 안전 GA 대기** — 취약점은 build cache metadata 역직렬화에 있고 runtime `kotlin-stdlib`·`kotlin-reflect`에는 해당 코드가 없지만 NVD의 광범위한 Kotlin CPE가 둘을 매칭한다. 수정 기준 2.4.20은 2026-08-17 현재 RC만 실재한다 | T9.6에서 Gradle build cache를 전역·CI 모두 비활성화하고 runtime purl+CVE만 2026-09-30까지 suppression. Kotlin 2.4.20 GA 실재·Boot 4.1 호환·전체 테스트 확인 후 업그레이드, suppression 제거, build cache 재활성화 |
| 42 | **CVE-2026-41115 Kafka ACL 문서 불일치** — Dependency-Check가 `kafka-clients` 4.2.1에 Medium 4.3으로 보고한다. Apache는 `CONSUMER_GROUP_DESCRIBE` 구현의 `DESCRIBE GROUP` 검사가 정확하고 4.0.0~4.3.0을 affected이자 fixed로 표기하며 기존 ACL 검토를 권고한다 | 게이트 기준 미만이라 숨기지 않고 보고서에 유지한다. 운영 broker 도입 전 consumer group ACL이 최소 권한인지 확인하고, NVD/Apache 메타데이터 정정 또는 실제 수정 버전이 나오면 재평가 |
| 43 | **Webhooks V2 구독 관리 API 실측·설계 근거** — 공식 reference에는 `GET/PATCH /v1/webhooks/{id}`, `enabled=true`, `DISABLED/ENABLED/SUSPENDED`가 있으나 저장소 규칙의 근거인 97·90에는 아직 없다 | JMX 복구 endpoint는 기본 비활성. sandbox 실측 또는 담당자 확답을 waas-wiki 97/90에 반영하고 사본을 동기화한 뒤 환경별로 활성화한다 |
| 44 | **tx 대사 제외 ID 파라미터 팽창** — 창 안 벤더 종결 관찰 ID 전체를 `NOT IN (:ids)`로 펼쳐 대량 창에서 PostgreSQL 파라미터 한계·계획 저하 가능 | Phase 10 전 배열 1파라미터 또는 `VALUES` 조인으로 바꾸고 대량 ID PostgreSQL 회귀 테스트 추가 |
| 45 | **미보관 COMPLETED 메트릭 스캔 비용** — 60초마다 API·BAT가 처리 완료 인박스의 JSON status와 보관 파티션을 대조해 보존량 증가 시 비용 상승 가능 | 실트래픽 규모 전 실행계획 측정. 필요하면 BAT 단일 수집·5분 주기 또는 명시 상태/보관 표식 설계로 이동 |
| 46 | **밴드S cold→hot 정족수 정본 모순** — 06·08은 옴니버스 입금 확인 뒤 출금 풀 보충에 재개와 같은 강화 정족수를 요구하지만, 03은 모든 BAND_S를 `risk_dvcd='FUND'`·독립 승인자 1명으로 고정하고 V3 DB trigger도 이를 강제한다 | cold→hot 승인 실행 전 waas-wiki 03에서 위험코드·DB 제약·정족수 파생을 확정. 현재 구현은 정본을 추측해 바꾸지 않음 |
| 47 | **밴드S sweep 선행·풀별 최소잔액 증적 자리 미정** — hot→cold에서 고객 vault sweep FINALIZED 선행과 출금 풀 최소 운영잔액 보호가 필요하지만 현재 proposal은 기존 sweep 실행 ID·풀별 관찰/최소 잔액을 보관하지 않는다 | DAW-CORE 입력 payload 계약만으로 충분한지, BCM 원장 FK/증적 컬럼이 필요한지 03·06에서 확정 후 구현 |
| 48 | **밴드S 외부 cold 일반 전송의 Gasless 근거 없음** | ✅ 보수적으로 해결 (2026-08-18) — `EXTERNAL_COLD`는 `useGasless=false`로 제출한다. 향후 켜려면 02·06 또는 벤더 QnA에서 지원 여부·수수료 부담을 먼저 확정한다. |
| 49 | **고정 cold 목적지 변경의 보안 정족수 원장 부재** — 06·08은 목적지 변경에 서로 다른 승인자 2명+보안 승인자 1명과 TAP 재검증을 요구하지만 현재 `fixedColdAddresses`는 배포 설정이고 version/change request 대상이 아니다 | 실자금 실행 전 목적지 registry의 정책 version·변경 요청·TAP evidence DB/API 자리를 03·08에서 확정하고 배포 설정 직접 변경을 차단 |
| 50 | **cold→hot 입금 FINALIZED 증적 구조 미정** — `COLD_DEPOSIT` proposal item에는 외부 cold 발신 주소/tx hash가 없고 현재 event 기록은 구조화되지 않은 observation payload를 신뢰해 `FINALIZED`를 추가할 수 있다 | cold→hot 실행 전 고정 외부 cold 발신 주소·tx hash·독립 체인 재조회·FINALIZED 증적과 다음 item 개방 조건을 03·06·08에서 확정 |
| 14 | **03 스키마 미확정 3건** — 약어 · 감사 센티넬 · subStatus 보관 | ✅ 해결 (2026-08-05, 사용자 위임으로 프로젝트 자체 확정) — ① 약어는 03 표기 그대로(`bcm`·`vndr`·`vlt`·`noti`·`swp`) = 프로젝트 약어집. DAW-CORE 약어집 등장 시 대조·조정 ② 센티넬 `empno='SYSTEM'` · `brcd='9999'` — 코드에선 단일 상수로 관리 ③ **subStatus·networkStatus 를 `bcm_tx_l` 에 보관**(사용자 결정 — 이벤트 미탑재는 유지). **반영 필요: waas-wiki 03 개정(컬럼 추가·미확정 절 정리) + 사본 동기화 — Phase 1 착수의 첫 선행 작업 (미실행)** |

## 범위 밖 (이 저장소가 아님) · 시점 미배정

- 컴플라이언스 게이트(cmpl_) · 정책 관리 · API Co-signer — 별도 구성 요소
- DAW-CORE 컨슈머 — 코어 쪽 구현
- **밴드S(핫↔콜드 균형, 06 ②)** — 판정(산식·환산 입력)은 코어/Admin 소관. 매니저 몫은 **실행부(지시 수신·전송 제출)뿐이며 이것도 시점 미배정** — 정책 자료·콜드월렛 결정(06 미확정) 대기. 침묵이 아니라 명시적 보류다
- 발신 IP allowlist(02 서명 검증 행) — 인프라 소관으로 추정, 소유 확인 필요
