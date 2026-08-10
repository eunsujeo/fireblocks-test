# Architecture

## Gradle 모듈 구조

```
{project}/
├── {project}-app/                  # 실행 가능한 애플리케이션 (BootJar)
│   ├── {short}-api/                #   REST API 서버
│   └── {short}-bat/                #   Spring Batch 서버
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
• **app/***: 모든 모듈에 의존 (조립 지점).

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