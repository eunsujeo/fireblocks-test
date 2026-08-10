package com.whatto.bcm.infra.persistence

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

/**
 * 슬라이스 테스트 앵커 — persistence 는 라이브러리 모듈이라 @SpringBootApplication 이 없다.
 * @DataJdbcTest 가 패키지를 거슬러 올라와 이 클래스를 설정 루트로 쓴다 (모듈 단독 실행 — docs/testing.md).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class PersistenceTestConfiguration
