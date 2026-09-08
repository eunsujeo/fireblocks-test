# Architecture

## Gradle 모듈 구조

```
{project}/
├── {project}-app/                  # 실행 가능한 애플리케이션 (BootJar)
│   ├── {short}-api/                #   REST API 서버
│   ├── {short}-webhook/            #   Webhook 수신·판단·outbox relay
│   ├── {short}-bat/                #   배치 실행 서버
│   └── {short}-admin/              #   독립 Frontend·BFF
├── {project}-application/          # 공유 유스케이스·피처 접근 서비스
├── {project}-domain/               # 도메인 모델, Repository 인터페이스
├── {project}-infra/                # 인프라스트럭처 (외부 기술 세부사항)
│   ├── persistence/                #   DB: Spring Data JDBC, DataSource 설정
│   ├── client/                     #   외부 API 클라이언트
│   └── messaging/                  #   메시징 (Kafka 등)
└── {project}-support/              # 공통 유틸, 모니터링 (Sentry 등)
```

### 모듈 간 의존성

```
app/{short}-api  ──→  domain
     │                  ↑
     ├──→  support ─────┘
     │                  ↑
     └──→  infra/* ─────┘
```


• **domain**: 어떤 모듈도 의존하지 않음 (순수 Kotlin).
• **support**: domain에만 의존.
• **infra/***: domain, support에 의존.
• **application**: domain, support에 의존. 실행 프로세스·infra 구현체·스케줄러에 의존하지 않는다.
• **app/api·webhook·bat**: 필요한 application, domain, support, infra를 조립한다. 다른 실행 모듈에 런타임 의존하지 않는다.
• **app/admin**: BCM 내부 모듈에 런타임 의존하지 않고 HTTP를 통해 통신한다.

### 헥사고날 경계와 실행 조립

- Controller·스케줄러는 입력 어댑터다. 해당 실행 모듈에 두고 유스케이스를 호출한다.
- 유스케이스는 도메인의 출력 포트(Repository·벤더·이벤트 인터페이스)를 사용한다. 물리 구현은 infra에 둔다.
- 여러 실행 모듈에서 사용하는 유스케이스는 application에 둔다. 특정 프로세스에서만 쓰는 유스케이스는 해당 실행 모듈에 둘 수 있다.
- Webhook 판단·relay와 스케줄러는 `bcm-webhook`의 `com.whatto.bcm.app.webhook.application`에서 관리한다.
- 각 조립 지점은 자신이 소유하는 패키지를 스캔하고 공용 서비스는 스캔 범위 또는 명시적인 `@Import`로 등록한다.
  전체 base 패키지를 스캔한 뒤 다른 실행 모듈을 제외하는 정규식으로 경계를 만들지 않는다.
- 같은 fully qualified class name을 여러 모듈에 선언하지 않는다. 공용 클래스로 옮겼다면 기존 선언을 제거한다.
- 피처 서비스 간 호출은 기존 외부 트랜잭션에 참여한다. 캡슐화를 이유로 트랜잭션을 분리하거나 잠금 순서를 바꾸지 않는다.

## 패키지 구조 (Feature 기반 Layered)

### app 모듈 (api)
```
com.whatto.{base}.app.api/
├── {feature}/
│   ├── {Feature}Controller.kt         # REST Controller
│   └── {Feature}Response.kt           # 응답 DTO
├── config/                             # CodeEnumConfig 등
└── web/                                # ExceptionHandler, CodeEnum
com.whatto.{base}.app.application/
└── {feature}/
    └── {Feature}Service.kt            # 유스케이스 오케스트레이션
```

### domain 모듈
```
com.whatto.{base}.domain/
└── {feature}/
    ├── {Feature}.kt                   # 도메인 모델 (순수 Kotlin data class)
    ├── {Feature}Policy.kt             # 도메인 규칙/정책 (필요 시)
    └── {Feature}Repository.kt         # Repository 인터페이스 (port)
```

### infra/persistence 모듈
```
com.whatto.{base}.infra.persistence/
├── config/
│   └── DatasourceConfig.kt            # DataSource 설정
└── {feature}/
    ├── {Feature}Entity.kt             # DB Entity (@Table, @Column)
    ├── {Feature}JdbcRepository.kt     # Repository 구현체 (adapter)
    └── {Feature}Mapper.kt             # Entity :양방향_화살표: Domain 매핑 (필요 시)
```

## 레이어 규칙

| 레이어 | 역할 | 금지 사항 |
|--------|------|-----------|
| **api** | 요청 검증 + 응답 변환 | 비즈니스 로직 금지 |
| **application** | 트랜잭션 경계, 유스케이스 오케스트레이션 | 도메인 로직 직접 구현 금지 |
| **domain** | 비즈니스 로직, Repository 인터페이스 정의 | Spring/JDBC 등 외부 프레임워크 의존 금지 |
| **infra** | Repository 구현체, 외부 API 클라이언트 | 비즈니스 판단 금지 |

## 핵심 원칙

### 1. 의존성 역전 (DIP)

• domain에 Repository 인터페이스를 정의하고, infra/persistence가 구현.
• application은 domain의 인터페이스에만 의존.

### 2. 도메인 순수성
domain 패키지에 Spring/JDBC 어노테이션 사용 금지. 순수 Kotlin만 사용.

```kotlin
// domain/ — 순수 Kotlin
data class Ledger(val id: Long, val balance: BigDecimal) {
    fun withdraw(amount: BigDecimal): Ledger {
        require(balance >= amount) { "잔액 부족" }
        return copy(balance = balance - amount)
    }
}

interface LedgerRepository {
    fun findById(id: Long): Ledger?
    fun save(ledger: Ledger): Ledger
}
```

### 3. 인프라 분리
물리 컬럼명(메타 표준)은 infra에서만 다룬다.

```kotlin
// infra/persistence/ — DB 매핑
@Table("tb_ldgr_m")
data class LedgerEntity(
    @Id @Column("ldgr_id") val id: Long? = null,
    @Column("ldgr_blnc_amt") val balance: BigDecimal,
)
```

### 4. API 응답
도메인 객체를 API 응답으로 직접 반환하지 않는다. 반드시 Response DTO로 변환.

### 5. 비즈니스 로직 위치
Service는 오케스트레이션만. 비즈니스 판단은 Domain에 위치.

```kotlin
// Service는 오케스트레이션만
class LedgerService(private val ledgerRepository: LedgerRepository) {
    fun withdraw(id: Long, amount: BigDecimal): Ledger {
        val ledger = ledgerRepository.findById(id) ?: throw ...
        val updated = ledger.withdraw(amount)  // 도메인이 판단
        return ledgerRepository.save(updated)
    }
}
```

### 6. 피처 간 참조
같은 프로젝트 내 피처 간에는 Service를 통해서만 접근. 다른 피처의 Repository 직접 접근 금지.

이 규칙은 이 저장소의 피처 소유권 규칙이다. 유스케이스가 여러 출력 포트를 조합할 수 있다는 헥사고날 원칙에
Service 인터페이스를 일률적으로 추가하라는 뜻은 아니다. 피처 서비스는 필요한 조회·관찰·기록만 공개하고 Repository를 노출하지 않는다.

## 자동 검증

`bcm-api`의 `ArchitectureTest`가 API·Webhook·BAT·Admin과 공용 모듈의 컴파일 결과를 직접 읽는다.
다른 실행 모듈을 테스트 런타임 classpath에 추가하지 않고 Gradle이 검사 대상 `classes`를 먼저 생성한다.

- 도메인 순수성(Spring·JDBC·Jackson 2/3), 레이어 의존성, 실행 모듈 간 참조, 공용 모듈의 입력 어댑터 참조를 검사한다.
- 검사 대상 실행 클래스의 존재와 모듈 간 중복 클래스도 검사해 빈 검사와 classpath 가림을 차단한다.
- 피처 간 Repository 직접 참조 검사는 우선 Webhook 판단·allowance 회수·Sweep 배치 실행 유스케이스에 적용한다.
  다른 유스케이스의 기존 직접 참조까지 검사하는 규칙은 아직 없으며, 해당 경계를 정리할 때 검사 범위를 확장한다.
- API/Webhook 경계 테스트와 실제 BAT 기동 테스트로 스캔 범위·공용 빈 등록도 검증한다.
