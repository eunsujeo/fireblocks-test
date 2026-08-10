package com.whatto.bcm.infra.persistence.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories

/**
 * persistence 배선 — Data JDBC 리포지토리 스캔 루트를 이 모듈로 고정한다.
 * 앱(조립 지점)은 scanBasePackages 로 이 설정을 집어 가고, DB 관련 컴파일 의존은 이 모듈에 갇힌다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJdbcRepositories(basePackages = ["com.whatto.bcm.infra.persistence"])
class PersistenceConfig
