# blockchain-manager

Fireblocks 기반 수탁형 지갑의 **온체인 자산 이동 단일 창구** 서비스입니다.
벤더 원어(tx 상태·웹훅)를 공통 상태(TxStatus)로 번역해 DAW-CORE 에 Kafka 이벤트로 공급합니다.

> 구현 진행 상태와 로드맵은 [PLAN.md](PLAN.md) — Phase 13 완료, 현재 Phase 14 진행 중.

## 모듈 구조

```
blockchain-manager/
├── blockchain-manager-app/        # 실행 가능한 애플리케이션 (BootJar)
│   ├── bcm-api/                   #   업무 REST API + Admin API
│   ├── bcm-webhook/               #   Webhook 수신 + 판단 워커 + outbox relay
│   ├── bcm-admin/                 #   운영 조회 + 로컬 기능 테스트 Admin Frontend + BFF
│   └── bcm-bat/                   #   Spring Batch — sweep 트리거 · tx 대사
├── blockchain-manager-application/ # 공용 application/use-case 경계
├── blockchain-manager-domain/     # 도메인 모델 · 전이 표 (순수 Kotlin, 프레임워크 의존 없음)
├── blockchain-manager-infra/      # 인프라스트럭처
│   ├── client/                    #   Fireblocks API 클라이언트 (JWT 서명)
│   ├── messaging/                 #   Kafka producer (deposit·withdrawal·internal)
│   └── persistence/               #   DB 영속성 (Spring Data JDBC · bcm_ 테이블 매핑)
├── blockchain-manager-support/    # 공통 유틸 · 모니터링
└── blockchain-manager-test-support/ # 독립 로컬 Fireblocks Stub · 체인 통합 테스트 실행기
```

### 의존성 방향

```
app(bcm-api·bcm-webhook·bcm-bat) ──→ application·infra/* ──→ domain
                  │                         │                    ↑
                  └──→ support ─────────────┴────────────────────┘

bcm-admin ──HTTP──→ bcm-api
    ├── health──→ bcm-webhook management
    └── FUNCTION_TEST에서만 고정 로컬 시나리오 실행

test-support ──HTTP──→ 기존 FireblocksClient 테스트
      (기존 BCM 모듈은 test-support에 의존하지 않음)
```

- `domain` 은 어떤 모듈에도 의존하지 않는 순수 Kotlin — ArchUnit 테스트로 강제.
- `infra` 가 `domain` 의 Repository 인터페이스를 구현 (DIP). 물리 컬럼명은 infra 한정.

## 기술 스택

| 항목 | 버전 |
|------|------|
| Kotlin | 2.3.x |
| JDK | 25 (toolchain 자동 프로비저닝 — 로컬 설치 불필요) |
| Spring Boot | 4.1.x (Spring Framework 7) |
| 영속성 | Spring Data JDBC + PostgreSQL · Git 관리 SQL |
| DB / MQ | PostgreSQL / Kafka |
| 빌드 | Gradle 9.6 · Kotlin DSL · version catalog · dependency locking |
| 테스트 | JUnit 6 + AssertJ + MockK + Testcontainers 2.0 |
| 로컬 EVM | Foundry/Anvil 1.7.1 + Solidity 0.8.35 · Prague |

버전 정본은 [gradle/libs.versions.toml](gradle/libs.versions.toml), 선정 근거는 [docs/tooling.md](docs/tooling.md).

## 빌드 / 실행

```sh
# 빌드 (Maven Central)
./gradlew build

# CI 와 동일 검증 (빌드 + 테스트 + ktlint + 의존성 취약점 + docs/api 생성물 drift 체크)
./scripts/ci.sh
```

bcm-api·bcm-webhook·bcm-admin과 개발자 전용 PostgreSQL·Kafka를 한 번에 실행할 수 있습니다. Docker가 실행 중인 상태에서
실 Fireblocks 테스트는 기본 `fireblocks`, 결정적 로컬 체인은 `stub` 모드를 사용합니다. 모드를 바꿀 때는 먼저 `down`합니다.

```sh
./scripts/local.sh up fireblocks  # 또는 인자 없이 up
./scripts/local.sh up stub        # Fireblocks 자격증명 없이 Anvil+Stub까지 기동
```

`fireblocks`의 첫 실행은 `.env` 설정 질문을 자동으로 시작합니다. `stub`은 Foundry/Anvil 1.7.1과 Forge를 확인하고
런타임 전용 RSA·EVM 키를 `build/local/stub/`에 생성하므로 `.env`나 실 Fireblocks API key가 필요하지 않습니다.
`up fireblocks`는 API·Webhook·Admin을 시작하기 전에 실제 BCM Fireblocks 클라이언트로 블록체인 목록을 한 번 읽어
API 인증을 확인하고 블록체인 카탈로그를 동기화합니다. 실패하면 애플리케이션 기동을 중단하며 안전한 상세 로그는
`build/local/fireblocks-preflight.log`에 남깁니다. BCM API가 준비되면 Ethereum Sepolia와 Base Sepolia를 지원 Network로
멱등하게 연결하고 두 Network의 자산 카탈로그까지 동기화한 뒤 Admin을 시작합니다. 이 단계는 자산 매핑을 자동 등록하지
않습니다. 이 확인은 거래 생성 권한·TAP·Webhook JWKS 검사가 아닙니다.
두 모드는 PostgreSQL·Kafka Docker volume도 각각 사용하므로 Stub에서 만든 테스트 매핑·계정·거래가 Fireblocks 화면에
섞이지 않습니다. `status`의 현재 실행 모드와 Admin 상단의 `벤더 + 체인 · 데이터셋` 표시로 연결 대상을 확인할 수 있습니다.
`fireblocks` 모드는 Anvil과 Stub을 기동하지 않고 `.env`의 Fireblocks workspace만 사용합니다. 분리 기능 적용 전 생성된
`bcm-local_postgres-data`·`bcm-local_kafka-data` 볼륨은 데이터 보호를 위해 자동 삭제하지 않으며, 새 기동부터
Compose 프로젝트 아래의 `fireblocks-*` 또는 `stub-*` 볼륨을 사용합니다.

Windows PowerShell에서는 Git Bash 또는 WSL2가 설치된 상태에서 같은 실행기를 사용합니다.

```powershell
.\scripts\local.ps1 up
```

Fireblocks에서 발급받은 PKCS#8 Private Key 파일은 확장자가 `.key` 또는 `.pem`이어도 되며 로컬 `.keys/` 디렉터리에 둘 수 있습니다. `.keys` 전체는 Git에서
제외되므로 키 파일을 커밋하지 않습니다.

```sh
mkdir -p .keys
cp /발급받은/경로/fireblocks-private-key.pem .keys/
./scripts/local.sh up
# Fireblocks API Key: 발급받은 API Key 문자열
# Fireblocks Private Key 파일 경로: .keys/fireblocks-private-key.pem
```

스크립트는 API Key 문자열을 `.env`에 저장하고, Private Key 본문은 복사하지 않은 채 파일의 절대 경로만 저장합니다.
`.env`와 `.keys`는 모두 Git 제외 대상입니다. 기본 주소는 `https://api.fireblocks.io`, 기본 JWKS는
`https://keys.fireblocks.io/.well-known/jwks.json`이며 프롬프트에서 Enter를 누르면 기본값을 사용합니다. 설정을 바꾸려면
`./scripts/local.sh configure`를 다시 실행합니다.

```sh
./scripts/local.sh status          # 프로세스·컨테이너 상태
./scripts/local.sh restart         # 현재 모드를 종료 후 다시 기동
./scripts/local.sh restart stub    # 모드를 지정해 종료 후 다시 기동
./scripts/local.sh sync assets     # 모든 Fireblocks 네트워크의 읽기 전용 자산 검색 캐시 갱신
./scripts/local.sh stop webhook    # api|webhook|admin 중 하나만 종료
./scripts/local.sh test deposit    # up stub 환경의 입금→Webhook→FINALIZED→Kafka→Admin 점검
./scripts/local.sh logs api        # chain|stub|api|webhook|admin|infra 로그
./scripts/local.sh down            # 종료, 로컬 DB·Kafka 데이터 보존
./scripts/local.sh reset           # Stub+Anvil만 기준 snapshot으로 복원
./scripts/local.sh purge           # 현재 선택 모드의 DB·Kafka 볼륨만 삭제(확인 필요)
```

`restart`는 인자를 생략하면 현재 `fireblocks` 또는 `stub` 모드를 유지합니다. 모드를 바꾸려면
`restart fireblocks` 또는 `restart stub`으로 명시하면 되며, 잘못된 모드는 기존 환경을 종료하기 전에 거부합니다.
두 모드는 같은 로컬 프로세스를 동시에 점유할 수 없고 한 번에 하나만 활성화됩니다. `status`와 `logs`는 첫 줄에
현재 모드·실행 상태(`RUNNING`·`PARTIAL`·`STOPPED`·`CONFLICT`)와 데이터셋을 표시합니다.

Admin은 `http://127.0.0.1:9080/admin/dashboard`, BCM API는 `http://127.0.0.1:38080`, Webhook listener는
`http://127.0.0.1:38081/webhook`입니다. 일반적인 개발 서버 포트와 겹치지 않도록 로컬 실행기에서만 높은 기본 포트를 사용합니다.
필요하면 `BCM_LOCAL_API_PORT`·`BCM_LOCAL_WEBHOOK_PORT`로 바꿀 수 있습니다. 이 저장소의 Blockchain Manager Admin은
DAW-CORE에 의존하지 않는 **로컬 개발·진단 콘솔**이며, 로컬 `bcm-api`와 Webhook management health만 읽습니다. 공유 환경의
운영 화면과 Network·Asset·정책·컨트랙트 변경 workflow는 BCM과 DAW-CORE를 함께 바라보는 DAW-ADMIN이 소유합니다.
FUNCTION_TEST에서는 검증된 고정 로컬 시나리오만 저장소 실행기로 시작합니다. 다른 BCM 대상으로 시작할 수 없습니다. 상태형 Fireblocks Stub과
결정적 Ethereum·Base Anvil 체인은 `up stub`에서 chain→Stub→BCM API→Webhook→Admin 순서로 기동합니다. Ethereum RPC는
`127.0.0.1:38545`, Base RPC는 `127.0.0.1:38546`을 기본으로 하며 각각 `BCM_LOCAL_ETHEREUM_ANVIL_PORT`와
`BCM_LOCAL_BASE_ANVIL_PORT`로 바꿀 수 있습니다. Stub callback은 API가 아니라
Webhook listener로 전달됩니다. `reset`은 Stub 상태와 Anvil 기준
snapshot만 복원하며 기본 URL은 `http://127.0.0.1:18080`입니다. 다른 loopback 포트는 `BCM_LOCAL_STUB_BASE_URL`로 지정합니다.
`bcm-bat`는 실행할 작업과 안전 설정을 명시해야 하는 비웹 프로세스이므로 기본 `up`에는 포함하지 않습니다.
Fireblocks 또는 Stub 카탈로그 변경을 Admin 검색에 즉시 반영하려면 `./scripts/local.sh sync assets`를 실행합니다. 이 명령은 모든
Fireblocks Network의 자산을 읽기 전용 캐시에 동기화하며 Network를 자동 채택하거나 자산을 등록하지 않습니다. 검색은 이 캐시의
심볼·표시명·contract address 인덱스를 사용하고 결과에 Fireblocks Asset ID를 함께 표시합니다. 미지원 Network 후보도
비교할 수 있지만 선택할 수 없고, 실제 등록 시에는 지원 Network 여부와 Fireblocks Asset ID·주소를 다시 확인합니다. 정기 배포 환경에서는
BAT의 일 1회 `VENDOR_ASSET_CATALOG_SYNC` 작업이 같은 캐시를 갱신합니다.

`up stub`의 최초 실행은 ETHEREUM(chain id 31337)과 BASE(chain id 31338)에 테스트 USDC·KRWK 컨트랙트를 배포하고,
두 토큰 모두 decimals 6으로 Fireblocks Stub 카탈로그와 BCM 매핑까지 자동 준비합니다. 이후 실행은 같은 seed·manifest와
이미 등록된 매핑을 멱등하게 재사용합니다. 고객 vault·입금 주소·잔액 이동은 자동으로 만들지 않고 아래 시나리오가 소유합니다.

Admin 첫 화면은 Fireblocks에서 읽은 전체 네트워크 후보, BCM 지원 네트워크, 등록 가능한 후보, 활성 자산과 마지막 카탈로그
동기화 시각을 구분해 보여 줍니다. 처음에는 **Assets**의 `+ 자산 찾아 등록`에서 `USDC`처럼 아는 이름을 검색합니다. 결과의
Network 표시명·testnet·chainId, Fireblocks Asset ID, decimals와 contract address를 비교해 하나를 선택하면 됩니다. BCM 내부
Network code는 시작 스크립트가 지원 목록으로 연결하므로 사용자가 입력하지 않습니다. **Networks** 화면은 이 연결 상태를 진단합니다. `up stub`은
로컬 카탈로그를, `up fireblocks`는 실제 테스트 workspace 카탈로그를 조회하며 두 모드의 DB·Kafka 데이터는 섞이지 않습니다.
이 채택·등록 경로는 `FUNCTION_TEST+loopback`에서만 열리고 공유 Admin mutation을 대신하지 않습니다. 해제·교체는 제공하지 않습니다.

`up stub` 후 Admin의 **로컬 테스트 실행**에서 다음을 화면으로 실행할 수 있습니다.

- `로컬 자산 준비` — ETHEREUM·BASE의 USDC/KRWK 매핑을 확인하고 누락된 카탈로그만 준비
- `고객 vault·주소 생성` — 입력한 ref와 자산으로 공개 account API를 실행
- `입금 성공` — Anvil→독립 Webhook→FINALIZED→Kafka→Admin 조사까지 8단계 검증
- `smoke` / `full` — 전용 환경에서 전체 통합·장애 복구·sweep/BAT 회귀 검증

실행하면 runId 상세로 이동해 단계 진행률, component 상태, accountId·주소·거래 ID, 실패 원인과 다음 조치를
같이 볼 수 있습니다. 이 실행 API는 `FUNCTION_TEST+STUB+LOCAL+loopback`에서만 열리며 production 모듈의 Admin·Stub·로컬 체인 의존성은 0으로 유지됩니다.

Admin 첫 화면의 `처음 설정하는 순서`는 자동으로 읽은 연결·카탈로그 상태와 개발자가 직접 확인할 네트워크·자산·계정 준비를
분리해 보여 줍니다. `Webhook 처리 상태`가 `NEVER_RECEIVED`이면 장애가 아니라 아직 수신 이력이 없다는 뜻입니다.
`up stub` 뒤 `./scripts/local.sh test deposit`을 실행하면 7단계 진행률과 함께 이번 입금의 Admin 거래 조사 URL을 출력합니다.
`BACKLOG` 또는 `POISONED`이면 대시보드의 `비상 운영 확인`과 `./scripts/local.sh logs webhook`을 사용합니다.

거래 조사에서는 원거래·활성 거래·외부 거래·부스트·스윕 실행 ID 중 하나를 통합 검색에 입력해 제출, 웹훅,
outbox, reconciliation, boost, sweep 1:N, allowance와 당시 수수료 견적을 연결해서 확인할 수 있습니다.
원문 payload·서명·callData는 Admin 응답과 화면에 노출하지 않습니다. 자산 등록 화면에 한해 선택 검증에 필요한 Fireblocks Asset ID를 표시합니다.

로컬 PostgreSQL 볼륨을 처음 만들 때는
`blockchain-manager-infra/persistence/src/main/resources/db/migration/manifest.txt`의 순서대로 V1~V14 SQL을 직접 실행합니다.
애플리케이션은 Flyway를 포함하지 않으며 기동 중 DDL을 실행하지 않습니다. 볼륨 생성 뒤 SQL이 추가·변경된 개발 DB는
자동 갱신하지 않으므로 보존할 데이터가 없는지 확인한 다음 재생성합니다. 아래 명령은 개발자 로컬 컨테이너 전용입니다.

개발자 전용 로컬 DB를 재생성할 때만 `./scripts/local.sh purge` 후 다시 `up` 합니다. `./scripts/local.sh reset`은
Stub의 vault·wallet·transaction·fault·Webhook 상태와 Anvil snapshot만 복원하며 BCM PostgreSQL·Kafka는 변경하지 않습니다.

## 테스트

```sh
./gradlew test                                # 전체 — Testcontainers 사용, Docker 실행 필수
./gradlew :blockchain-manager-domain:test     # 도메인만 (컨테이너 없음, 빠른 루프)
```

Admin→BCM API/BAT→PostgreSQL·Kafka→Fireblocks Stub→Anvil을 실제 프로세스로 관통하는 시스템 테스트는 다음 한 명령으로
실행합니다. `local.sh up`을 먼저 실행할 필요가 없고, 기존 로컬 개발 환경과 다른 전용 포트·Compose 프로젝트·볼륨을 사용합니다.
Docker와 Foundry/Anvil 1.7.1이 준비돼 있어야 하며 실 Fireblocks 자격증명은 사용하지 않습니다.

```sh
./scripts/system-test.sh smoke              # 입금→FINALIZED→Kafka→Admin, 약 1분
./scripts/system-test.sh full               # component 재기동·출금·gasless·장애 복구·sweep/BAT·reset
./scripts/system-test.sh status [runId]     # 단계 진행률·component 상태
./scripts/system-test.sh logs [runId] [component]
./scripts/system-test.sh stop [runId]       # --keep-on-failure로 보존한 환경 정리
```

실행 상태는 `build/system-test/<runId>/`의 `run.json`, `events.jsonl`, component log에 남습니다. 실패 시 출력되는 runId를
Admin의 `로컬 테스트 실행` 화면에서 열면 실패 단계, 안전한 원인·다음 행동과 requestId→externalTxId→vendorTxId→txHash→
eventId→executionId/jobRunId 연결을 볼 수 있습니다. 원문 로그는 위 `logs` 명령으로만 확인합니다.

CI 제품은 아직 정하지 않았으므로 lane은 플랫폼 중립 스크립트로 제공합니다. PR은 smoke, nightly는 full이며 자동 lane은
항상 `STUB+LOCAL`만 사용합니다. 수동 Fireblocks lane은 기존 실행별 승인·공식 origin·Secret 검증을 우회하지 않습니다.

```sh
./scripts/system-test-ci.sh pr
./scripts/system-test-ci.sh nightly
./scripts/system-test-ci.sh manual-fireblocks
./scripts/system-test-ci.sh plan pr          # CI 설정에 넣을 실제 명령만 출력
```

Admin·로컬 Fireblocks·로컬 체인 없이 production API/Webhook/BAT만 조립되는지는 `./scripts/ci.sh`의 production boundary
검사가 강제합니다. 이 검사는 `bcm-admin`과 `blockchain-manager-test-support` 프로젝트 자체를 Gradle 구성에서 제외한 뒤
세 BootJar와 runtimeClasspath를 검증합니다. 스크립트 역할과 내부 구조는 [scripts 안내](scripts/README.md)를 참고합니다.

실 Fireblocks golden contract test는 일반 테스트와 CI에서 실행되지 않습니다. 사용자에게 이번 실행의 읽기 범위 승인을 받은
뒤에만 API key·private key 파일·대상 blockchain과 실행별 승인 ID를 환경변수로 주입하고 아래 전용 명령을 사용합니다.
현재 범위는 blockchain/asset 조회뿐이며 자원 생성·거래·Webhook 변경은 호출하지 않습니다.

```sh
BCM_FIREBLOCKS_CONTRACT_TEST_APPROVAL_ID=<승인 식별자> \
BCM_FIREBLOCKS_API_KEY=<API key> \
BCM_FIREBLOCKS_PRIVATE_KEY_FILE=</절대/경로/private-key.key> \
BCM_FIREBLOCKS_CONTRACT_BLOCKCHAIN_ID=<testnet blockchain id> \
./scripts/fireblocks-contract-test.sh
```

## API 문서

HTTP API 계약의 정본은 [docs/api/openapi.yaml](docs/api/openapi.yaml) 입니다.
로컬 BCM 실행 뒤 `http://127.0.0.1:38080/api-docs/`를 열면 실전 요청·응답 예시를 수정해 바로 실행할 수 있습니다.
파일 전달용 [docs/api/api.html](docs/api/api.html)도 있으며, 스펙 수정 후에는 `python3 docs/api/build.py`로
생성물(spec.js·api.md·api.html)을 재생성합니다. 포털은 `계정·주소`, `잔액`, `거래`, `관리자`, `데이터 타입` 중
선택한 카테고리의 OpenAPI operation과 schema만 표시합니다. 로컬 기동과 통합 테스트 절차는 이 README에서 안내하고,
API 포털에는 실제 요청·응답 계약과 실행 패널만 유지합니다.

## 참고 문서

- [CLAUDE.md](CLAUDE.md) — AI 작업 진입점 · 확정 결정(재제안 금지) 목록
- [PLAN.md](PLAN.md) — 구현 로드맵(Phase 0~15) · 스펙-설계 미해결 표
- [docs/design/](docs/design/) — 이 저장소에서 직접 수정·리뷰하는 설계 정본
- [docs/testing.md](docs/testing.md) — 테스트 전략 · 계약 케이스 표
- [SETUP.md](SETUP.md) — 새 머신에서 시작하기 (저장소 밖 체크리스트)
