# blockchain-manager — AI 작업 진입점

> 이 저장소에서 작업하는 LLM(Claude Code)이 가장 먼저 읽는 파일.
> 프로젝트 이름은 **blockchain-manager**, 폴더는 blockchain-manager-svc.

## 0. 금지 — 어기면 안 되는 것

- **설계 계약 임의 변경 금지** — `docs/design/`가 이 저장소의 설계 정본이다. 사용자 요청·확정 결정에 따라 여기서 수정하고 관련 코드·테스트·API 계약을 함께 대조한다. 외부 저장소와의 동기화는 요구하지 않는다.
- **테스트를 통과시키려 테스트를 건드리지 않는다** — skip·`@Disabled`·assertion 완화·프로덕션 코드의 테스트 전용 분기 전부 금지. 테스트가 실패하면 원인을 고치거나 실패 그대로 보고한다.
- **에러 억제 금지** — 빈 catch·로그만 남기고 삼키기·경고 끄기로 "통과처럼 보이게" 만들지 않는다.
- **신규 의존성 임의 추가 금지** — 좌표를 Maven Central 에서 검증하고, 별도 커밋으로 분리하고, 사용자 승인 후에만. (존재하지 않는 패키지를 지어내는 사고 방지)
- **시크릿 커밋 금지** — pre-commit gitleaks 가 막지만, 애초에 코드·픽스처에 넣지 않는다. 테스트도 env 참조만.
- **벤더 실호출 금지** — 테스트는 mock. sandbox 실호출은 사용자 지시가 있을 때만.
- **확정 결정 재제안 금지** — 3절 목록.

## 1. 정체성

- **무엇**: Fireblocks 기반 수탁형 지갑의 **온체인 자산 이동 단일 창구** 서비스.
  벤더 원어(tx 상태·웹훅)를 공통 상태(TxStatus)로 번역해 DAW-CORE 에 공급한다.
- **스택**: Kotlin + Spring Boot (Gradle 멀티모듈 단일 저장소 · API/Webhook/Admin/BAT 독립 프로세스) · PostgreSQL · Kafka · Spring Batch.
- **설계 문서**: [docs/design/](docs/design/)가 설계 정본이다 — 코드는 이 설계를 구현한다.
  설계 수정·구현·리뷰는 이 저장소 안에서 수행한다. 관리 규칙은 [docs/design/README.md](docs/design/README.md).
  설계와 코드가 어긋나면 **코드를 설계에 맞추는 게 기본**이고, 설계를 바꿔야 하면 사용자에게 먼저 묻는다.
- **새 머신에서 시작할 때**: [SETUP.md](SETUP.md) — 저장소 밖(플러그인·JDK·시크릿) 체크리스트.

## 2. 설계 문서 맵 ([docs/design/](docs/design/) 정본)

| 문서 | 코드에서의 정본 범위 |
|---|---|
| [01-infra.md](docs/design/01-infra.md) | 구성 요소 배치 · 보안 경계 · 큐 4토픽 |
| [02-bcm-flow.md](docs/design/02-bcm-flow.md) | **이벤트 계약** — 허용 전이 표 · evnt_id dedup · relay 순차 발송 · 감지 합성 발행 · boost txId 접기 |
| [03-bcm-db.md](docs/design/03-bcm-db.md) | **bcm_ 코어 스키마** — 컬럼명·타입 그대로 구현 |
| [06-sweep.md](docs/design/06-sweep.md) | sweep 정책 (트리거·밴드S) · approve + transferFrom 배치 실행 계약 |
| [07-asset-master.md](docs/design/07-asset-master.md) | **블록체인·자산 카탈로그 캐시 + 벤더 자산 현재/변경 매핑** · 등록 재검증 · Admin API · 벤더 경계 변환 |
| [08-bcm-admin.md](docs/design/08-bcm-admin.md) | **Blockchain Manager Admin** — 운영 조사 · 컨트랙트/실행 정책 · 밴드S · 승인 · 비상 운영 · UI/UX 경계 |
| [09-asset-map.md](docs/design/09-asset-map.md) | 고객 vault·옴니버스·출금 풀·회사자산·외부 콜드 간 시나리오별 자산 이동 지도 |
| [10-local-fireblocks-integration.md](docs/design/10-local-fireblocks-integration.md) | **로컬 통합 테스트 계약** — Fireblocks API 지원표 · Stub/Anvil 경계 · 실행 모드 · 키 · reset · 실벤더 승인선 |
| [93-batch-partial-fail-sample.md](docs/design/93-batch-partial-fail-sample.md) | batch sweep 부분 실패 실측 payload · 항목 결과 판정 근거 |
| [94-batch-payload-sample.md](docs/design/94-batch-payload-sample.md) | batch sweep network records 실측 payload · 원천 vault 귀속 근거 |
| [95-approve-pull-poc-result.md](docs/design/95-approve-pull-poc-result.md) | approve + transferFrom PoC 결과 · 제출 operation · 부분 성공 관찰 |
| [96-payload-sample.md](docs/design/96-payload-sample.md) | 웹훅 payload 실물 — 필드명·타입의 근거 |
| [97-webhook-poc-result.md](docs/design/97-webhook-poc-result.md) | 실측된 벤더 동작 — 재시도 간격 · resend_failed 의미 |
| [98-batch-sweep.md](docs/design/98-batch-sweep.md) | **채택 근거** — approve + transferFrom 메커니즘 · 수탁 위험 · 출시 게이트 |
| [99-detection-detail.md](docs/design/99-detection-detail.md) | 감지 경로 상세 — 인박스 → 워커 → outbox → relay |
| [90-fireblocks-qna.md](docs/design/90-fireblocks-qna.md) | 벤더 확답 모음 — rate limit · 확정 임계 · 쿼리 패턴 |

**HTTP API 계약은 이 저장소 안에 있다** — [docs/api/openapi.yaml](docs/api/openapi.yaml) 이 정본(그대로 구현 대상). `api.md`·`api.html`·`spec.js` 는 `build.py` 생성물이므로 직접 고치지 않는다. 스펙 수정 → `python3 build.py` 재생성. 공통 규약(응답 envelope·에러 코드·커서 페이지네이션·멱등 키)도 이 파일의 `info.description` 에 있다.

## 3. 확정된 결정 (재제안 금지)

아래는 검토를 거쳐 확정된 결정이다. AI 가 "더 단순한 방법"으로 재제안하지 않는다.

- **설계 정본은 이 저장소에서 관리한다** (2026-09-08 확정) — `docs/design/`를 직접 수정·리뷰한다. 별도 wiki clone·경로·동기화·byte 비교를 개발과 검증의 선행 조건으로 두지 않는다. 전환 기준은 이 저장소에 커밋된 설계이며 외부 작업본을 자동으로 가져오지 않는다.
- **transactional outbox** — 워커 한 트랜잭션 = `bcm_tx_l` 갱신 + `bcm_outbox_l`(P) 적재 + `prcs_stcd=S`, relay 가 발행(P→S). outbox 제거·publish-first 전환 재제안 금지.
- **수신 인박스(`bcm_whk_l`) 는 테이블** — 큐로 대체하지 않는다. `noti_id` PK dedup · 원문 감사 · SKIP LOCKED.
- **dedup 키 = `evnt_id`** — txId 로 dedup 금지 (감지·확정이 같은 키가 되어 확정이 버려진다).
- **이벤트 순서는 매니저가 보장** — 앞 단계 미발행이면 감지 이벤트를 합성 발행. DAW-CORE 는 항상 감지→확정 순서만 받는다.
- **sweep 은 `approve + transferFrom` 배치 · 목적지는 옴니버스 vault** (2026-08-12 확정) — 고객 vault별·sweep 컨트랙트별 제한 allowance와 `SweepExecution 1:N SweepItem`으로 관리한다. approve·batch 호출은 TAP → Co-signer Callback → 목적지 불변 sweep 컨트랙트의 3중 통제를 통과한다. 최상위 거래 종결만으로 항목을 성공 처리하지 않고 network records + `SweepLeg` 이벤트로 대사한다. EIP-3009·2612·7702 직접 pull과 건별 일반 전송은 구현안에서 제외한다.
- **batch sweep은 출시 게이트 기본 비활성** — approve와 batch CONTRACT_CALL의 Universal Gasless, TAP 세부 매칭, Callback fail-closed, gas/처리량, 컨트랙트 감사와 전체 `approve(0)` 회수 훈련을 실측·검증한 네트워크만 활성화한다.
- **로컬 Fireblocks 통합환경은 운영 벤더 대체가 아닌 상시 테스트 장치** (2026-08-18 확정) — 이 저장소의 기존
  `FireblocksClient`가 상태형 Stub을 호출하고 Anvil에서 실제 EVM 결과를 만든다. 원격 폐쇄망 일반 서버에는 Docker 없이
  Anvil 실행 파일·Stub fat JAR·컨트랙트·systemd·초기화 도구를 파일 묶음으로 배포하며, 서버에 이미 설치된 PostgreSQL·Kafka를
  사용하고 패키지에 포함하거나 초기화하지 않는다. PostgreSQL·Kafka 컨테이너는 개발자 로컬·CI 전체 E2E에서만 기동한다.
- **tx 대사는 종결 건만** — 벤더 원어 기준 COMPLETED · FAILED · 출금 REJECTED · BLOCKED (진행 중은 웹훅 몫).
- **DB 는 코어(daw_) 규약** — 일시 `VARCHAR(16)` (값 포맷 `yyyyMMddHHmmss` 14자 — 2026-08-05 확정, 변환은 support 유틸 단일 관리) · 일자 `VARCHAR(8)` · 불리언 `_yn VARCHAR(1)` · 금액 `NUMERIC` · 감사 4컬럼(`frst_reg_empno` 계열, 센티넬 `SYSTEM`/`9999`) · payload `JSONB`. 벤더 id 는 `VARCHAR(64)`.
- **DB 일시·일자의 시간대 = UTC** (2026-08-14 확정 — 03) — 모든 `_dttm`은 UTC `yyyyMMddHHmmss`, `_dt`·`base_dt`는 UTC `yyyyMMdd`로 저장한다. DB 시각을 만드는 `Clock` 빈은 `blockchain-manager-application`(이를 `bcm-api`·`bcm-webhook`이 공유)과 `bcm-bat`의 `Clock.systemUTC()`뿐이며, 벤더 epoch ms도 저장 직전 공통 유틸에서 UTC로 변환한다. API는 ISO 8601 UTC(`Z`)를 쓰고 화면·정산·보고서에서 필요한 시간대로 변환한다. 2026-08-06 KST 결정은 이 결정으로 대체됐다.
- **수신 원문은 바이트 그대로** (2026-08-06 확정 — 03) — `bcm_whk_l.payload` 는 TEXT, `payload_hash`(SHA-256 소문자 hex)·`sign_vl`(서명 헤더 원문)을 수신 시점에 함께 남긴다. **본문 바이트를 한 번 읽어 서명 검증·저장·해시에 같은 `byte[]` 를 쓴다.** `bcm_raw_tx_l` 로는 복사만 하고 해시를 재계산하지 않는다. payload `JSONB` 규약의 예외는 이 두 테이블뿐(`bcm_outbox_l` 은 JSONB 유지).
- **가상 스레드 채택 · `StructuredTaskScope` 불채택** (2026-08-06 확정) — `spring.threads.virtual.enabled=true` + `ScopedValue`(JDK 25 정식)는 쓴다. `--enable-preview` 가 필요한 preview API 는 수탁 프로덕션 빌드에 넣지 않는다. 조건 2가지 — ① 수신 동시성에 명시 상한(커넥션 풀이 실질 상한이라 무제한이면 폭주 시 커넥션 대기로 쌓인다) ② relay 순차 발행·워커 폴링은 병렬화 대상이 아니다(계정 내 `evnt_id` 순서 보장). 규칙은 [.claude/rules/virtual-thread.md](.claude/rules/virtual-thread.md).
- **금액은 문자열 필드(`amountInfo`)에서 읽는다** — payload 의 숫자 `amount` 는 정밀도 손실 위험.

## 4. 아키텍처 표준

사내 표준 구조를 따른다 — 사본: [docs/standards/architecture.md](docs/standards/architecture.md).

```
blockchain-manager-svc/            (rootProject.name = "blockchain-manager")
├── blockchain-manager-app/
│   ├── bcm-api/                   REST API + Admin 조회·실행 경계
│   ├── bcm-webhook/               웹훅 수신 + 판단 워커 + outbox relay
│   ├── bcm-admin/                 독립 Admin Frontend + BFF
│   └── bcm-bat/                   Spring Batch — sweep 트리거 · tx 대사
├── blockchain-manager-application/     API·Webhook·BAT 공유 유스케이스·피처 접근 서비스·설정
├── blockchain-manager-domain/     도메인 모델 · 전이 표 · Repository 인터페이스 (순수 Kotlin)
├── blockchain-manager-infra/
│   ├── persistence/               Spring Data JDBC · bcm_ 테이블 매핑
│   ├── client/                    Fireblocks API 클라이언트 (JWT 서명)
│   └── messaging/                 Kafka producer (deposit·withdrawal·internal)
├── blockchain-manager-support/    공통 유틸 · 모니터링
└── blockchain-manager-test-support/ 독립 로컬 Fireblocks Stub · 체인 통합 테스트 실행기
```

레이어 규칙 요약 — api 는 검증·변환만, application 은 오케스트레이션만, **비즈니스 판단(전이 표 등)은 domain**, 물리 컬럼명은 infra 에서만. domain 은 Spring/JDBC 의존 금지.
test-support는 기존 BCM 모듈이 의존하지 않는 별도 실행 경계이며, 기존 `FireblocksClient`의 HTTP 설정으로만 연결한다.
Webhook 전용 판단·relay·스케줄러는 `bcm-webhook`에 둔다. 각 실행 모듈은 소유 패키지와 필요한 공용 빈을 명시적으로 조립한다.
같은 FQCN의 클래스는 한 모듈에서만 소유한다. 세부 경계와 자동 검사는 `docs/standards/architecture.md`를 따른다.

## 5. 프레임워크·보안 정책

- **최신 안정판 유지** — Spring Boot 4.1.x (2026-06 기준 최신) 라인. 마이너·패치는 릴리스되면 따라간다.
- 의존성 추가·업그레이드 시 **알려진 CVE 확인**을 습관화한다 (Gradle `dependencyUpdates` + OWASP/Sonatype 스캔은 CI 에 둔다).
- 버전을 코드 여러 곳에 흩뿌리지 않는다 — Gradle version catalog(`gradle/libs.versions.toml`) 단일 관리.
- 시크릿(벤더 API key·DB 비밀번호)은 git 에 절대 커밋하지 않는다. 로컬은 `.env`/환경변수, 배포는 시크릿 매니저.

## 6. AI 작업 규율

- **세션 시작 리추얼** — [PLAN.md](PLAN.md) 의 현재 Phase 와 [PROGRESS.md](PROGRESS.md) 를 먼저 읽는다.
- **세션 크기** — 1 세션 = task 1~2개 = 사람이 리뷰 가능한 diff 1개. 같은 문제를 두 번 넘게 정정받으면 세션을 끊고 새로 시작하는 게 낫다.
- **완료의 정의** — "됐다"는 주장이 아니라 **테스트 실행 출력이 증거**다. 관련 테스트를 실제로 돌린 출력을 보여주기 전에는 완료를 선언하지 않는다.
- **검증은 좁게 돌린다** — 전체 스위트 말고 파일·모듈 단위:
  `./gradlew :blockchain-manager-domain:test --tests "..."` / `./gradlew :모듈:build`
- **추측 금지** — 확인 안 된 벤더 필드·동작을 코드·주석·문서에 쓰지 않는다. 근거는 96·97·QnA 실측/확답에서만.
- **구현 전 설계 대조** — 이벤트·DB·상태를 만지는 작업은 해당 설계 문서(2절 맵)를 먼저 읽는다.
- **테스트 없는 완료 없음** — 규칙은 [docs/testing.md](docs/testing.md). 계약 로직(전이 표·dedup·outbox)은 반드시 테스트로 고정한다. 테스트 수정은 구현과 별도 커밋으로.
- **커밋은 마일스톤 단위** — 매 편집마다 커밋하지 않는다. 커밋 메시지 끝: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. PROGRESS.md 는 세션 종료 시 갱신.
- **강제 장치** — 이 파일의 규칙 중 일부는 hook 으로 이중화돼 있다: ktlint(`.claude/settings.json` · `.claude/hooks/`), 시크릿 스캔(`.githooks/pre-commit`). hook 이 막으면 우회하지 말고 원인을 고친다.
- **converge 리뷰는 순차·독립적으로 실행** — 구현 세션과 분리된 읽기 전용 세션에서 design-sync 성공 후 code-reviewer를
  실행한다. Claude Code는 `./scripts/converge-review.sh`로 재개할 수 있고, Codex는 같은 `.claude/agents/` 체크리스트를 읽은
  별도 reviewer agent/session으로 대체할 수 있다. 세부 기준은 [converge-review.md](docs/ai/converge-review.md)다.
  두 리뷰를 병렬 실행하거나 구현 세션이 스스로 최종 승인하지 않는다.
- **Admin 작업 절차** — 일반 Admin 기능은 `.claude/skills/admin-feature`, 정책·컨트랙트·allowance cap·밴드S·pause/resume 변경은
  `.claude/skills/admin-policy-change`를 적용한다. `docs/design/08-bcm-admin.md`에 필요한 계약이 없거나 미확정이면 이 저장소의 설계에서 먼저 확정한다.
- 프롬프트 작성 요령·작업 요청 템플릿: [docs/ai/prompt-guide.md](docs/ai/prompt-guide.md).

## 7. 확정·미확정

- base 패키지 = **`com.whatto.bcm`** (확정)
- Kafka 토픽 네이밍 사내 규약 없음 — openapi.yaml 의 토픽명(`deposit-events` 등) 그대로 사용
- 배포 환경·파이프라인 — **미정**. 확정 전까지 특정 CI/CD 제품이나 배포 플랫폼을 전제하지 않는다.
