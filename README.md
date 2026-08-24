# blockchain-manager

Fireblocks 기반 수탁형 지갑의 **온체인 자산 이동 단일 창구** 서비스입니다.
벤더 원어(tx 상태·웹훅)를 공통 상태(TxStatus)로 번역해 DAW-CORE 에 Kafka 이벤트로 공급합니다.

> 구현 진행 상태와 로드맵은 [PLAN.md](PLAN.md) — 현재 Phase 11 T11.2 완료.

## 모듈 구조

```
blockchain-manager/
├── blockchain-manager-app/        # 실행 가능한 애플리케이션 (BootJar)
│   ├── bcm-api/                   #   REST API + 웹훅 수신 + 판단 워커 + relay
│   ├── bcm-admin/                 #   독립 읽기 전용 기능 테스트 Admin Frontend + BFF
│   └── bcm-bat/                   #   Spring Batch — sweep 트리거 · tx 대사
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
app(bcm-api·bcm-bat) ──→ infra/* ──→ domain
        │                   │           ↑
        └──→ support ───────┴───────────┘

bcm-admin ──HTTP(read only)──→ bcm-api

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
| 영속성 | Spring Data JDBC + Flyway |
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

bcm-api·bcm-admin과 개발자 전용 PostgreSQL·Kafka를 한 번에 실행할 수 있습니다. Docker가 실행 중인 상태에서
실 Fireblocks 테스트는 기본 `fireblocks`, 결정적 로컬 체인은 `stub` 모드를 사용합니다. 모드를 바꿀 때는 먼저 `down`합니다.

```sh
./scripts/local.sh up fireblocks  # 또는 인자 없이 up
./scripts/local.sh up stub        # Fireblocks 자격증명 없이 Anvil+Stub까지 기동
```

`fireblocks`의 첫 실행은 `.env` 설정 질문을 자동으로 시작합니다. `stub`은 Foundry/Anvil 1.7.1과 Forge를 확인하고
런타임 전용 RSA·EVM 키를 `build/local/stub/`에 생성하므로 `.env`나 실 Fireblocks API key가 필요하지 않습니다.

Windows PowerShell에서는 Git Bash 또는 WSL2가 설치된 상태에서 같은 실행기를 사용합니다.

```powershell
.\scripts\local.ps1 up
```

Fireblocks에서 발급받은 PKCS#8 Private Key 파일은 로컬 `.keys/` 디렉터리에 둘 수 있습니다. `.keys` 전체는 Git에서
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
./scripts/local.sh logs api        # chain|stub|api|admin|infra 로그
./scripts/local.sh down            # 종료, 로컬 DB·Kafka 데이터 보존
./scripts/local.sh reset           # Stub+Anvil만 기준 snapshot으로 복원
./scripts/local.sh purge           # 이 실행기 전용 DB·Kafka 볼륨 삭제(확인 필요)
```

Admin은 `http://127.0.0.1:9080/admin/dashboard`, BCM API는 `http://127.0.0.1:8080`입니다. 독립
Blockchain Manager Admin은 로컬 `bcm-api`만 호출하며 다른 BCM 대상으로 시작할 수 없습니다. 상태형 Fireblocks Stub과
결정적 Anvil 체인은 `up stub`에서 chain→Stub→BCM API→Admin 순서로 기동합니다. `reset`은 Stub 상태와 Anvil 기준
snapshot만 복원하며 기본 URL은 `http://127.0.0.1:18080`입니다. 다른 loopback 포트는 `BCM_LOCAL_STUB_BASE_URL`로 지정합니다.
`bcm-bat`는 실행할 작업과 안전 설정을 명시해야 하는 비웹 프로세스이므로 기본 `up`에는 포함하지 않습니다.

거래 조사에서는 원거래·활성 거래·외부 거래·부스트·스윕 실행 ID 중 하나를 통합 검색에 입력해 제출, 웹훅,
outbox, reconciliation, boost, sweep 1:N, allowance와 당시 수수료 견적을 연결해서 확인할 수 있습니다.
원문 payload·서명·callData·벤더 자산 ID는 Admin 응답과 화면에 노출하지 않습니다.

프로덕션 배포 전까지 V1은 설계의 최종 기준선에 맞춰 직접 갱신한다. 이전 V1을 적용한 로컬 또는 공용 개발 DB는
Flyway 체크섬과 실제 스키마가 모두 다르므로 `repair`로 넘기지 말고 DB를 재생성한다. 아래 명령은 로컬 컨테이너용이다.

개발자 전용 로컬 DB를 재생성할 때만 `./scripts/local.sh purge` 후 다시 `up` 합니다. `./scripts/local.sh reset`은
Stub의 vault·wallet·transaction·fault·Webhook 상태와 Anvil snapshot만 복원하며 BCM PostgreSQL·Kafka는 변경하지 않습니다.

## 테스트

```sh
./gradlew test                                # 전체 — Testcontainers 사용, Docker 실행 필수
./gradlew :blockchain-manager-domain:test     # 도메인만 (컨테이너 없음, 빠른 루프)
```

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
`docs/api/index.html` 을 브라우저로 열면 자체 뷰어로 볼 수 있고, 스펙 수정 후에는
`python3 docs/api/build.py` 로 생성물(spec.js·api.md·api.html)을 재생성합니다.

## 참고 문서

- [CLAUDE.md](CLAUDE.md) — AI 작업 진입점 · 확정 결정(재제안 금지) 목록
- [PLAN.md](PLAN.md) — 구현 로드맵(Phase 0~10) · 스펙-설계 미해결 표
- [docs/design/](docs/design/) — 설계 문서 사본 (정본은 waas-wiki, byte-동일 유지 · 수정 금지)
- [docs/testing.md](docs/testing.md) — 테스트 전략 · 계약 케이스 표
- [SETUP.md](SETUP.md) — 새 머신에서 시작하기 (저장소 밖 체크리스트)
