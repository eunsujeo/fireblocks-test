# blockchain-manager

Fireblocks 기반 수탁형 지갑의 **온체인 자산 이동 단일 창구** 서비스입니다.
벤더 원어(tx 상태·웹훅)를 공통 상태(TxStatus)로 번역해 DAW-CORE 에 Kafka 이벤트로 공급합니다.

> 구현 진행 상태와 로드맵은 [PLAN.md](PLAN.md) — 현재 Phase 10 T10.6.1 완료.

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
└── blockchain-manager-support/    # 공통 유틸 · 모니터링
```

### 의존성 방향

```
app(bcm-api·bcm-bat) ──→ infra/* ──→ domain
        │                   │           ↑
        └──→ support ───────┴───────────┘

bcm-admin ──HTTP(read only)──→ bcm-api
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

버전 정본은 [gradle/libs.versions.toml](gradle/libs.versions.toml), 선정 근거는 [docs/tooling.md](docs/tooling.md).

## 빌드 / 실행

```sh
# 빌드 (Maven Central)
./gradlew build

# CI 와 동일 검증 (빌드 + 테스트 + ktlint + 의존성 취약점 + docs/api 생성물 drift 체크)
./scripts/ci.sh
```

bcm-api 는 PostgreSQL 이 필요합니다. 로컬 실행:

```sh
docker run -d --name bcm-pg -e POSTGRES_PASSWORD=bcm -e POSTGRES_DB=bcm \
  -p 15432:5432 postgres:17-alpine

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/bcm \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=bcm \
./gradlew :blockchain-manager-app:bcm-api:bootRun
```

독립 Blockchain Manager Admin은 기본적으로 `127.0.0.1:9080`에서 실행되며 로컬 `bcm-api`의 읽기 API만 호출합니다.
기능 테스트 모드는 외부 주소나 외부 BCM 대상으로 시작할 수 없고 상태 변경 BFF 경로도 제공하지 않습니다.

```sh
./gradlew :blockchain-manager-app:bcm-admin:bootRun
# http://127.0.0.1:9080/admin/dashboard
```

거래 조사에서는 원거래·활성 거래·외부 거래·부스트·스윕 실행 ID 중 하나를 통합 검색에 입력해 제출, 웹훅,
outbox, reconciliation, boost, sweep 1:N, allowance와 당시 수수료 견적을 연결해서 확인할 수 있습니다.
원문 payload·서명·callData·벤더 자산 ID는 Admin 응답과 화면에 노출하지 않습니다.

프로덕션 배포 전까지 V1은 설계의 최종 기준선에 맞춰 직접 갱신한다. 이전 V1을 적용한 로컬 또는 공용 개발 DB는
Flyway 체크섬과 실제 스키마가 모두 다르므로 `repair`로 넘기지 말고 DB를 재생성한다. 아래 명령은 로컬 컨테이너용이다.

```sh
docker rm -fv bcm-pg
# 위 docker run 명령으로 다시 생성
```

## 테스트

```sh
./gradlew test                                # 전체 — Testcontainers 사용, Docker 실행 필수
./gradlew :blockchain-manager-domain:test     # 도메인만 (컨테이너 없음, 빠른 루프)
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
