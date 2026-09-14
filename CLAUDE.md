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

## 2. 설계 문서 찾기

[업무별 설계 안내](docs/design/README.md)에서 해당 업무와 근거 문서를 찾는다. 설계는 이 저장소에서 관리한다.
이벤트·상태는 02, BCM 스키마는 03, Sweep은 06, 자산은 07, Admin은 08이 정본이며 코드 변경 전에 해당 절을 대조한다.
벤더 필드·동작의 근거는 안내의 **실측과 채택 근거**에서 찾는다. 외부 시스템 자료는 BCM 구현 범위와 구분한다.

**HTTP API 계약은 이 저장소 안에 있다** — [docs/api/openapi.yaml](docs/api/openapi.yaml) 이 정본(그대로 구현 대상). `api.md`·`api.html`·`spec.js` 는 `build.py` 생성물이므로 직접 고치지 않는다. 스펙 수정 → `python3 build.py` 재생성. 공통 규약(응답 envelope·에러 코드·커서 페이지네이션·멱등 키)도 이 파일의 `info.description` 에 있다.

## 3. 확정된 결정 (재제안 금지)

아래는 검토를 거쳐 확정된 결정이다. AI 가 "더 단순한 방법"으로 재제안하지 않는다.

- **설계 정본은 이 저장소에서 관리한다** (2026-09-08 확정) — `docs/design/`를 직접 수정·리뷰한다. 별도 wiki clone·경로·동기화·byte 비교를 개발과 검증의 선행 조건으로 두지 않는다. 전환 기준은 이 저장소에 커밋된 설계이며 외부 작업본을 자동으로 가져오지 않는다.
- **첫 번째 호환 목표** (2026-09-14 사용자 정정) — Fireblocks(파블)·Dfns·로컬 블록체인을 같은 BCM API·업무·이벤트·멱등·복구 계약으로 지원한다. Fireblocks도 유지할 지원 대상이며 종료·자산 이전은 첫 완료 조건이 아니다. 로컬은 기존 FireblocksClient→상태형 Stub→Anvil 경로를 재사용하고 Dfns 로컬 검증을 확장한다. 로컬의 실제 EVM 결과와 벤더 보안 모사/실환경 수용은 구분한다. 제공자와 체인 환경을 별도로 모델링하고 거래별 실행 경로는 고정한다. 멀티체인 실구현과 #51 신규 기능 완성은 후속 마일스톤으로 추적한다. [호환 계획](docs/dfns-compatibility-plan.md)을 따른다.
- **제공자는 애플리케이션 시작 시 환경변수로 선택** (2026-09-14 사용자 확정) — `BCM_PROVIDER=fireblocks|dfns|local` 설정으로 공통 포트의 실행 구현 하나를 조립한다. 같은 배포의 API/Webhook/BAT가 동일 값을 사용하며 비선택 벤더 설정·시크릿·클라이언트는 요구/호출하지 않는다. 요청별 다중 벤더 routing은 초기 범위가 아니다. 기존 지갑/거래의 원천은 보존·검증하고 환경변수 변경으로 자산을 이전하지 않는다. 상세 기동/오류·로컬 설정은 [호환 계획](docs/dfns-compatibility-plan.md)을 따른다. 현재 Fireblocks/로컬 조건부 조립·선택 자격/로컬 주소 검증을 구현했으며, 미구현 Dfns는 기동을 거절한다. V21과 세 앱 공통 guard로 DB 원천 대조를 구현했으며 실제 원천 등록·권한·백필 절차는 03과 원천 runbook을 따른다. 웹훅 헤더/envelope와 지갑 생성 재시도는 WebhookProtocol·WalletCreationPolicy 및 선택된 구현으로 분리했다. Dfns 지갑·원천·수신/복구 연결은 [계약13](docs/design/13-dfns-contracts.md)을 따른다.
- **Dfns 경로의 목표 구성** (2026-09-14 사용자 지정) — 노드를 직접 운영하는 인프라 업체 + 고객 환경의 Dfns Baseline 전체 플랫폼 + DAWBC(BCM) + DAW-CORE. 모델 목표는 Ethereum·Base·Solana, 대상 종목은 USDC·KRWK이며 체인별 발행/contract/mint는 검증 전이다. 사용자 후속 지시에 따라 멀티체인 실구현은 후속으로 미루고 초기에는 자산 식별·확정·가스 대납 인터페이스와 확장 지점을 먼저 마련한다. 초기 Dfns 실행 범위는 DF0에서 고정하며 후속 체인 구현·감사는 초기 출시의 선행 조건이 아니다. 자산 식별·확정·가스 대납을 체인별로 분리한다. 사용자 지정 waas-wiki의 Baseline은 HashiCorp Vault 기반 배포 프로필을 뜻한다. 일반 전송은 내부 Dfns API→위탁 RPC, DAWBC는 공통 계약·독립 관찰/대사를 담당하는 목표안이다. 사내 배포 지원·릴리스별 기능·RPC 호환·Solana 집금 통제는 검증 전이며 현행 Fireblocks 구현·EVM Sweep 계약을 자동 변경하지 않는다. [목표 경계](docs/design/01-infra.md#dfns-baseline-전환-목표-2026-09-14)·[전환 계획](docs/dfns-compatibility-plan.md)을 따른다.
- **설계자 원장 v0.1.2의 DAWBC 범위는 BCM이 담당한다** (2026-09-08 사용자 확정) — 회사·고객 관리 주소별 잔고도 BCM에서 온체인 데이터 수준으로 기록·관리한다. 고객별 소유권 원장과 업무상 잔액 인정은 DAW-CORE 책임이다. PDF의 all sync는 동시성 미고려로 설계자가 변경 예정이므로 구현 요구로 적용하지 않는다. 소유·주소 용도를 식별하는 구조가 필요하다. 회사 주소는 고객거래용·외부입출금용·콜드를 구분한다. 사용자 후속 설명에 따른 설계자 의도는 고객 받는주소에서 핫 출금 풀로 직접 Sweep하는 구조다. 앞선 AI의 물리 분리 유지 확정 기록은 정정한다. 현행 분리 구현에서 직접 집금으로 전환할 구체 계약은 PLAN #51에서 검토한다. 주소/vault 매핑은 `docs/design/09-asset-map.md`, 온체인 잔고 저장 경계는 03을 따른다. 신규 물리 DB/API는 후속 구현 계약이다.
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
- **구현 전 설계 대조** — 이벤트·DB·상태를 만지는 작업은 해당 설계 문서(2절 안내)를 먼저 읽는다.
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
