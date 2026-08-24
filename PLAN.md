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
- [x] Phase 10 — Blockchain Manager Admin (2026-08-17)
- [x] Phase 11 — 로컬 블록체인 + Fireblocks 통합 테스트 환경 (2026-08-20)
- [x] Phase 12 — 전체 시스템 통합 테스트·진단 (2026-08-20)
- [x] Phase 13 — Webhook 독립 경계 + 로컬 기능 점검 UX (2026-08-21)
- [x] Phase 14 — 실행 가능한 API 개발자 포털 + 로컬 시나리오 콘솔 (2026-08-21)
- [ ] Phase 15 — Production 배포 준비 계획 (보류 — 운영 논의 재개 시 착수)

## 작업 규칙 (모든 Phase 공통)

- **task 층** — Phase 착수 시 첫 작업은 그 Phase 를 체크박스 task 로 분해하는 것이다. task 마다
  완료 기준(어떤 테스트가 통과하면 done)과 근거 설계 절을 붙인다. 분해 결과는 이 문서의 해당 Phase 아래에 둔다.
- **세션 단위** — 1 세션 = task 1~2개 = 리뷰 가능한 diff 1개. 세션이 끝나면 [PROGRESS.md](PROGRESS.md) 갱신.
- **Phase 마무리(converge)** — 완료 기준 통과 후 ① 독립 읽기 전용 design-sync ② 지적 반영 후 독립 code-reviewer를
  **순차 실행** ③ 체크박스와 PROGRESS 갱신. Claude Code는 `./scripts/converge-review.sh <agent> <base>`로 재개하고,
  Codex는 동일한 `.claude/agents/` 체크리스트를 읽은 별도 reviewer agent/session으로 대체할 수 있다. 구현 세션의 자기 승인은
  금지하며 마지막 성공 리뷰의 도구·범위·commit을 PROGRESS에 기록한다. 셋이 끝나야 Phase 종료다.

## 완료 Phase 상세 이력

Phase 0~14의 task·완료 기준·검증 증적은 [완료 Phase 0~14 상세 이력](docs/history/phase-0-14-plan.md)으로 분리했다.
현재 계획 문서에는 다음 작업과 아직 열린 설계 항목만 유지한다.

## Phase 15 — Production 배포 준비 계획

Phase 14까지 통과한 기능·프로세스 경계를 실제 운영 환경에 옮기기 위한 **계획만** 수립한다. 사용자가 운영 논의를
재개할 때까지 Phase 전체를 보류하며 서버 설치·배포·DNS/방화벽 변경·실 Fireblocks 변경 호출을 실행하지 않는다.
현재 확정된 전제는 Linux 일반 서버, `systemd`, API/Webhook/BAT 각 1대의 초기 검증 구성뿐이다. Admin 배포 여부와
PostgreSQL·Kafka, ingress/TLS, Secret, 운영 일정은 미정이다.

### 계획 task

- [ ] **T15.0 배포 요구사항 결정표** — 운영 OS·프로세스 관리자·서비스별 인스턴스 수·네트워크 인/아웃바운드·DNS·TLS,
  외부 PostgreSQL·Kafka·secret 전달·배포 승인권자·RTO/RPO를 질문과 결정 로그로 확정한다.
  [운영 배포 결정표](docs/runbooks/production-deployment-plan.md)에 현재 답변을 기록했으며 나머지는 Phase 재개 시 확정한다.
- [ ] **T15.1 산출물·버전·공급망 계획** — API/Webhook/Admin/BAT 독립 BootJar, 설정 템플릿, checksum·SBOM,
  서명·보관·불변 release ID·rollback 단위를 정하고 local/test-support 모듈 미포함을 검증한다.
- [ ] **T15.2 DB·Kafka 배포 순서** — Flyway 실행 소유권·사전 백업·하위 호환성·rollback 한계, 4개 토픽의 생성·파티션·보관·ACL과
  서비스 기동 순서를 문서화한다.
- [ ] **T15.3 런타임 토폴로지·독립성** — 구성 요소별 port·health/readiness·graceful shutdown·수평 확장·singleton 작업·장애 영향을
  정하고 Admin·로컬 체인·Stub 제거 조합에서 production 모듈의 런타임 의존 0을 재검증한다.
- [ ] **T15.4 보안·시크릿·통신 경계** — Fireblocks API private key·JWKS·PUBLIC Webhook ingress, 아웃바운드 allowlist, Admin private
  listener·mTLS·5분 이하 JWT, 키 교체·폐기·마스킹·접근 감사 계획을 확정한다.
- [ ] **T15.5 관측·운영·복구 runbook** — component별 로그·메트릭·trace/request ID, Webhook/outbox·Kafka·DB·Fireblocks 경보, 재시작·재처리·
  중지·rollback·DB/Kafka 장애 훈련과 Admin 진단 동선을 하나의 runbook으로 묶는다.
- [ ] **T15.6 스테이징·실벤더 수용 게이트** — 자동 Stub/LOCAL regression, 실행별 승인 후 Fireblocks read-only·최소 변경 계약,
  웹훅 서명·중복·역순·재전송, 소액 포함 기능 점검과 수동 승인 증거를 분리한다.
- [ ] **T15.7 rollout·rollback 계획 converge** — 사전 조건·배포 순서·중단 기준·canary/점진 전환·롤백 발동·승인 증거를
  검토하고, design-sync→code-reviewer·운영/보안 사람 리뷰 후에만 배포 실행 Phase를 연다.

**계획 예상 공수**: 1명 4~7인일 + 인프라·보안·DB/Kafka 담당자 각 0.5~1인일 리뷰. 배포 자동화·실제 반영 공수는
T15.0 결정 후 별도 산정한다.

## 스펙-설계 불일치 · 미해결 (구현 전/중 해결)

해결된 항목은 [해결된 스펙·설계 항목](docs/history/resolved-design-items.md)에 보존한다.

| # | 내용 | 상태 |
|---|---|---|
| 3 | **relay 의 stuck 자동 처리 여부** — 자동이면 막힘 점검의 boost 트리거를 뺀다 | 🟡 공개 문서 기준 임시 해결 (2026-08-12) — 자동 boost는 Gas Station auto-fueling에만 명시되고, 일반 EVM은 stuck 알림+RBF API를 안내한다. 일반 gasless relay 자동 처리를 보장하지 않는 것으로 보고 트리거를 유지하되 담당자 확답 전까지 미해결 유지 |
| 4 | **귀속 불명 해소 절차** — 매핑 갱신 트리거·해소 후 이벤트 재흘림 | DAW-CORE 정합 후 확정 (02 미확정) — Phase 4 는 통지까지만 |
| 15 | **REJECTED 이벤트의 `evt_typ_dvcd` 미정** | 🟡 임시 해결 (2026-08-07) — 코어 회신 전까지 `TXRJ`를 `OutboxEventType` 한 곳에서 관리·발행. 회신 후 그 상수만 확정 또는 교체 |
| 21 | **입금 주소 memoTag 비영속** — 스펙 Address.memoTag(Tag/Memo 체인용)가 있으나 03 `bcm_addr_m` 에 태그 컬럼이 없어 발급 후 재조회에서 돌려줄 수 없다. EVM 한정이면 무해(항상 null) — Tag/Memo 자산 지원 시 03 개정 필요 | Tag/Memo 자산 채택 시 — 설계(waas-wiki 03) 질의 |
| 22 | **GET 오퍼레이션의 400 이 스펙 응답 표면에 없음** (T2.5 design-sync) — 코드는 경로변수 maxLength 초과를 400 처리(파라미터 스키마는 스펙에 있음), `depositAddressOf`·`balanceOf` 응답 표면은 200·404 만 — 스펙 내부 비일관. 스펙에 400 추가 또는 GET 검증 제거 | 차기 스펙 개정 시 — 사용자 결정 |
| 23 | **balanceOf — "계정 있음·자산 지갑 미발급" 케이스 계약 미정의** (T2.5 design-sync, 중) — 벤더 4xx → VendorApiException → 500 으로 떨어짐. `depositAddressOf` 는 같은 구분을 `data: null` 로 명시하는데 balanceOf 는 침묵. DAW-CORE 가 주소 발급 전 잔액 조회 시 500 | 차기 스펙 개정 시 — DAW-CORE 정합 포함 사용자 결정 |
| 24 | **생성 오퍼레이션의 409 가 스펙 표면에 없음** (T2.5 design-sync) — UNIQUE 경합 후 재조회마저 실패하는 극단 경로에서 409 전파, 스펙 CONFLICT 는 submitTransaction 에만 표기. 정상 운영 도달 불가한 방어 경로 | 차기 스펙 개정 시 |
| 25 | **벤더 생성 성공 + 로컬 insert 실패(비-충돌) 복구 경로** (T2.5 code-reviewer M3) — 멱등 창(24h) 이후 재시도가 벤더 "이미 존재" 4xx → 500 영구 반복(고아 vault·지갑). "already exists" 식별 fallback 또는 벤더-로컬 대사 항목 필요 | Phase 8 대사 설계 시 함께 — 또는 조기 fallback 구현. **참고: 제출 경로는 같은 문제를 "원장 먼저, 벤더 나중 + 벤더 조회로 회수"로 풀었다(02 출금 절·#6)** — 계정·주소 생성에도 같은 형태를 쓸지 검토 |
| 30 | **카탈로그 동기화 다중 인스턴스 실행 제어** — 현재 각 bcm-bat 인스턴스의 `@Scheduled`가 동시에 실행될 수 있다. 중복 실행을 허용할지, `bcm_job_m`/DB lock으로 단일 실행할지 배치 운영 규약 확정 필요 | bcm-bat 다중 인스턴스 배포 전 |
| 31 | **07의 Fireblocks 응답 필드 위치 정정** — 공식 OpenAPI 실물은 체인 폐기 여부가 `metadata.deprecated`, 자산 소수 자릿수가 `onchain.decimals`인데 07 하단 표는 평면 필드처럼 적혀 있다. 구현은 중첩·평면 decimals를 모두 읽어 호환하고, 설계 사본 정정은 waas-wiki 담당 | 다음 설계 동기화 시 |
| 33 | **자산 후보 조회 fan-out과 벤더 rate limit** — network 생략 시 채택 네트워크별로 자산을 끝까지 페이징해 워크스페이스 공용 quota를 쓴다. 지원 네트워크가 늘기 전에 조회 상한·캐시/격리·Admin 호출 제어를 운영 규약으로 확정해야 한다. 실패를 조용히 건너뛰는 방식은 금지 | 지원 네트워크 확대 전 |
| 34 | **거래 목록 커서의 벤더 조합 동작 미실측** — ① 정렬 지정 시 next 커서가 오는가 ② next 가 `sourceType`/`sourceId` 필터를 보존하는가 ③ next 와 필터를 함께 보내도 되는가. 공식 API 에 파라미터는 있으나 **조합은 실측 없음**. **외부 계약은 벤더와 분리 완료** — 매니저 커서에 최초 필터·정렬과 `(createdAt, txId)` 위치를 담고, 벤더 커서는 한 HTTP 요청 안의 내부 페이징에만 쓴다. 마지막에도 nextCursor를 발급하며 동일 시각 거래·asc 증분 회귀 테스트가 있다. 내부 페이징은 커서마다 발신 vault 필터를 재전송하고 응답 vault가 다르면 전체 거절한다 | sandbox 실측 — 내부 페이징 조합 확인 |
| 35 | **제출 직후 조회·알림의 빈 필드** — 벤더 문서상 `sourceAddress`·`destinationAddress` 는 체인 등장 전 비어 있을 수 있다. 우리 PoC 는 입금 `CONFIRMING` 부터라 그 구간 미관측. **스펙은 nullable 로 열었다**(v0.6.0 — `Transfer.from`/`to`, `ChainEvent.to`). 근거: 열지 않으면 제출 응답을 못 받았을 때 쓰는 `transactionByExternalTxId` 가 바로 그 시점에 깨진다. ★ **`amountInfo.amount` 가 제출 직후에도 항상 있는지는 미확인** — 없으면 현재 파서가 필수로 읽어 그 알림이 격리된다 | Phase 5 E2E 실측 — 결과에 따라 파서·스펙 조정 |
| 36 | **내부이체(delta)의 대납 적용 여부** — 출금·sweep 은 대납 근거가 설계에 있으나(02 출금 시퀀스 · 06 수수료 표) INTERNAL 은 없다. **확인 전까지 켜지 않는다**(근거 없는 설정을 넣지 않는다 — 안 켜도 된다고 확인한 것은 아니다). 대납 없이 가면 출발 vault 에 native 가 있어야 하고, 없으면 `INSUFFICIENT_FUNDS_FOR_FEE` 로 실패한다 | Phase 5 내부이체 E2E 전 — 벤더·운영 확인 |
| 37 | **출금 요청 본문 크기 상한** — `note`와 구조가 아직 불투명한 `travelRule`에 스키마 상한이 없어 큰 JSON이 벤더 호출·claim 점유를 늘릴 수 있다. 구현이 임의로 필드 상한을 만들면 OpenAPI보다 좁아지므로, 전체 HTTP 본문 상한과 필드별 상한·초과 응답(400/413)을 스펙에서 먼저 확정해야 한다 | 실트래픽 연동 전 — waas-wiki/OpenAPI 결정 |
| 40 | **Webhooks V2 재전송 기간 문구 불일치** — 02·90은 `resend_failed`를 원 이벤트 30일 내로 서술하지만 2026-08-17 공식 endpoint reference는 이 API를 최근 24시간 실패 알림 대상으로 제한하고, migration guide의 최대 30일은 resource/query 재전송까지 포함한다 | T9.4 수동 러너는 `resend_failed` 기본 24시간 범위만 사용하고 오래된 공백은 tx 대사로 복구. 다음 waas-wiki 동기화에서 API별 기간을 분리 정정 |
| 41 | **CVE-2026-53914 Kotlin 안전 GA 대기** — 취약점은 build cache metadata 역직렬화에 있고 runtime `kotlin-stdlib`·`kotlin-reflect`에는 해당 코드가 없지만 NVD의 광범위한 Kotlin CPE가 둘을 매칭한다. 수정 기준 2.4.20은 2026-08-17 현재 RC만 실재한다 | T9.6에서 Gradle build cache를 전역·CI 모두 비활성화하고 runtime purl+CVE만 2026-09-30까지 suppression. Kotlin 2.4.20 GA 실재·Boot 4.1 호환·전체 테스트 확인 후 업그레이드, suppression 제거, build cache 재활성화 |
| 42 | **CVE-2026-41115 Kafka ACL 문서 불일치** — Dependency-Check가 `kafka-clients` 4.2.1에 Medium 4.3으로 보고한다. Apache는 `CONSUMER_GROUP_DESCRIBE` 구현의 `DESCRIBE GROUP` 검사가 정확하고 4.0.0~4.3.0을 affected이자 fixed로 표기하며 기존 ACL 검토를 권고한다 | 게이트 기준 미만이라 숨기지 않고 보고서에 유지한다. 운영 broker 도입 전 consumer group ACL이 최소 권한인지 확인하고, NVD/Apache 메타데이터 정정 또는 실제 수정 버전이 나오면 재평가 |
| 43 | **Webhooks V2 구독 관리 API 실측·설계 근거** — 공식 reference에는 `GET/PATCH /v1/webhooks/{id}`, `enabled=true`, `DISABLED/ENABLED/SUSPENDED`가 있으나 저장소 규칙의 근거인 97·90에는 아직 없다 | JMX 복구 endpoint는 기본 비활성. sandbox 실측 또는 담당자 확답을 waas-wiki 97/90에 반영하고 사본을 동기화한 뒤 환경별로 활성화한다 |
| 44 | **tx 대사 제외 ID 파라미터 팽창** — 창 안 벤더 종결 관찰 ID 전체를 `NOT IN (:ids)`로 펼쳐 대량 창에서 PostgreSQL 파라미터 한계·계획 저하 가능 | Phase 10 전 배열 1파라미터 또는 `VALUES` 조인으로 바꾸고 대량 ID PostgreSQL 회귀 테스트 추가 |
| 45 | **미보관 COMPLETED 메트릭 스캔 비용** — 60초마다 API·BAT가 처리 완료 인박스의 JSON status와 보관 파티션을 대조해 보존량 증가 시 비용 상승 가능 | 실트래픽 규모 전 실행계획 측정. 필요하면 BAT 단일 수집·5분 주기 또는 명시 상태/보관 표식 설계로 이동 |
| 46 | **밴드S cold→hot 정족수 정본 모순** — 06·08은 옴니버스 입금 확인 뒤 출금 풀 보충에 재개와 같은 강화 정족수를 요구하지만, 03은 모든 BAND_S를 `risk_dvcd='FUND'`·독립 승인자 1명으로 고정하고 V3 DB trigger도 이를 강제한다 | cold→hot 승인 실행 전 waas-wiki 03에서 위험코드·DB 제약·정족수 파생을 확정. 현재 구현은 정본을 추측해 바꾸지 않음 |
| 47 | **밴드S sweep 선행·풀별 최소잔액 증적 자리 미정** — hot→cold에서 고객 vault sweep FINALIZED 선행과 출금 풀 최소 운영잔액 보호가 필요하지만 현재 proposal은 기존 sweep 실행 ID·풀별 관찰/최소 잔액을 보관하지 않는다 | DAW-CORE 입력 payload 계약만으로 충분한지, BCM 원장 FK/증적 컬럼이 필요한지 03·06에서 확정 후 구현 |
| 49 | **고정 cold 목적지 변경의 보안 정족수 원장 부재** — 06·08은 목적지 변경에 서로 다른 승인자 2명+보안 승인자 1명과 TAP 재검증을 요구하지만 현재 `fixedColdAddresses`는 배포 설정이고 version/change request 대상이 아니다 | 실자금 실행 전 목적지 registry의 정책 version·변경 요청·TAP evidence DB/API 자리를 03·08에서 확정하고 배포 설정 직접 변경을 차단 |
| 50 | **cold→hot 입금 FINALIZED 증적 구조 미정** — `COLD_DEPOSIT` proposal item에는 외부 cold 발신 주소/tx hash가 없고 현재 event 기록은 구조화되지 않은 observation payload를 신뢰해 `FINALIZED`를 추가할 수 있다 | cold→hot 실행 전 고정 외부 cold 발신 주소·tx hash·독립 체인 재조회·FINALIZED 증적과 다음 item 개방 조건을 03·06·08에서 확정 |


## 범위 밖 (이 저장소가 아님) · 시점 미배정

- 컴플라이언스 게이트(cmpl_) · 정책 관리 · API Co-signer — 별도 구성 요소
- DAW-CORE 컨슈머 — 코어 쪽 구현
- **밴드S(핫↔콜드 균형, 06 ②)** — 판정(산식·환산 입력)은 코어/Admin 소관. 매니저 몫은 **실행부(지시 수신·전송 제출)뿐이며 이것도 시점 미배정** — 정책 자료·콜드월렛 결정(06 미확정) 대기. 침묵이 아니라 명시적 보류다
- 발신 IP allowlist(02 서명 검증 행) — 인프라 소관으로 추정, 소유 확인 필요
