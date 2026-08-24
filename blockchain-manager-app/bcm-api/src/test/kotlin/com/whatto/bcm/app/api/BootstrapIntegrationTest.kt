package com.whatto.bcm.app.api

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.whatto.bcm.testsupport.integration.IntegrationTestSupport
import org.apache.coyote.AbstractProtocol
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.tomcat.TomcatWebServer
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.core.env.Environment
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 0 T0.2 — 신품 메이저 조합(Boot 4.1 · JUnit 6 · Testcontainers 2.0)의 배선 검증.
 * 계약 테스트가 아니라 배선 확인이다 — 계약 테스트는 Phase 1 부터 (docs/testing.md).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["BCM_HTTP_MAX_CONNECTIONS=100"],
)
class BootstrapIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var flyway: Flyway

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var environment: Environment

    @Autowired
    @Qualifier("applicationTaskExecutor")
    lateinit var taskExecutor: AsyncTaskExecutor

    @Autowired
    lateinit var webServerApplicationContext: ServletWebServerApplicationContext

    @Autowired
    lateinit var requestMappingHandlerMapping: RequestMappingHandlerMapping

    @Test
    fun `컨텍스트가 뜨고 DataSource 가 컨테이너 PostgreSQL 에 연결된다`() {
        val one = jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
        assertThat(one).isEqualTo(1)
    }

    @Test
    fun `BCM controller 경로와 메서드는 실행 문서 OpenAPI에 빠짐없이 대응한다`() {
        val implementation =
            requestMappingHandlerMapping.handlerMethods
                .filterValues { it.beanType.packageName.startsWith("com.whatto.bcm.app.api") }
                .flatMap { (mapping, _) ->
                    mapping.patternValues.filterNot { it.startsWith("/api-docs") || it.startsWith("/test-") }.flatMap { path ->
                        mapping.methodsCondition.methods.map { method -> method.name to path }
                    }
                }.toSet()
        val openApi = YAMLMapper().readTree(File("../../docs/api/openapi.yaml"))
        val contract =
            openApi
                .required("paths")
                .properties()
                .asSequence()
                .flatMap { path ->
                    path.value
                        .properties()
                        .asSequence()
                        .filter { it.key.uppercase() in HTTP_METHODS }
                        .map { operation -> operation.key.uppercase() to path.key }
                }.toSet()

        assertThat(implementation).isEqualTo(contract)
    }

    @Test
    fun `Flyway 가 컨테이너 DB 에 실행되어 schema history 테이블을 남겼다`() {
        val count =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'flyway_schema_history'",
                Int::class.java,
            )
        assertThat(count).isEqualTo(1)
        assertThat(flyway.configuration.locations).isNotEmpty()
    }

    @Test
    fun `V1 코어와 V2부터 V12까지의 Admin 원장 41개를 전부 만든다`() {
        val tables =
            jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'bcm_%'",
                String::class.java,
            )
        assertThat(tables).containsExactlyInAnyOrder(
            "bcm_acnt_m",
            "bcm_addr_m",
            "bcm_blkc_m",
            "bcm_vndr_ast_ctlg_m",
            "bcm_vndr_ast_m",
            "bcm_vndr_ast_chng_l",
            "bcm_whk_l",
            "bcm_tx_l",
            "bcm_sbmt_l",
            "bcm_outbox_l",
            "bcm_swp_trgt",
            "bcm_swp_auth_m",
            "bcm_swp_exec_l",
            "bcm_swp_item_l",
            "bcm_boost_l",
            "bcm_job_m",
            "bcm_fee_qt_l",
            "bcm_raw_tx_l",
            "bcm_ctrt_vrsn_l",
            "bcm_ctrt_evdc_l",
            "bcm_plcy_vrsn_l",
            "bcm_chng_req_l",
            "bcm_chng_dcsn_l",
            "bcm_adm_actn_l",
            "bcm_ctrt_bind_m",
            "bcm_plcy_bind_m",
            "bcm_bnds_snps_l",
            "bcm_bnds_prop_l",
            "bcm_bnds_prop_item_l",
            "bcm_bnds_exec_l",
            "bcm_bnds_exec_item_key",
            "bcm_bnds_exec_evt_l",
            "bcm_exec_gate_evt_l",
            "bcm_ext_ctrl_evdc_l",
            "bcm_alwnc_rvok_exec_l",
            "bcm_alwnc_rvok_item_l",
            "bcm_alwnc_rvok_evt_l",
            "bcm_whk_rcvr_req_l",
            "bcm_whk_rcvr_evt_l",
            "bcm_exec_gate_rsm_l",
            "bcm_exec_gate_rsm_chk_l",
        )
    }

    @Test
    fun `멱등의 물리 근거 — (유형,ref) · (acnt_id, ntwk_cd, tkn_smbl) · ext_tx_id 유니크 제약이 존재한다`() {
        val uniqueColumns =
            jdbcTemplate.queryForList(
                """
                SELECT tc.table_name || ':' || string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position)
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                WHERE tc.constraint_type IN ('UNIQUE', 'PRIMARY KEY') AND tc.table_name LIKE 'bcm_%'
                GROUP BY tc.table_name, tc.constraint_name
                """.trimIndent(),
                String::class.java,
            )
        assertThat(uniqueColumns).contains(
            // 계정 생성 멱등 — 접두사가 없어 고객·시스템 ref 가 겹칠 수 있으므로 유형과 복합이어야 한다
            "bcm_acnt_m:acnt_typ_dvcd,ref",
            // 주소 발급 멱등 — 네트워크가 키에 들어가야 같은 자산의 여러 네트워크가 공존한다
            "bcm_addr_m:acnt_id,ntwk_cd,tkn_smbl",
            "bcm_blkc_m:ntwk_cd",
            "bcm_vndr_ast_m:ntwk_cd,tkn_smbl",
            "bcm_tx_l:ext_tx_id", // 출금 재제출 중복 차단
            "bcm_sbmt_l:ext_tx_id", // 벤더 호출 전 제출 멱등 판정
            "bcm_whk_l:noti_id", // 웹훅 중복 수신 방어
            "bcm_outbox_l:evnt_id", // 컨슈머 dedup 키
        )
        val activeVendorIndex =
            jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_bcm_vndr_ast_active_vendor'",
                String::class.java,
            )
        assertThat(activeVendorIndex).contains("(vndr_ast_id)").contains("WHERE").contains("actv_yn")
    }

    @Test
    fun `제출 원장은 vendorTxId 부분 UNIQUE와 미결 상태 시각 인덱스를 가진다`() {
        val indexNames =
            jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'bcm_sbmt_l'",
                String::class.java,
            )

        assertThat(indexNames).contains("ux_bcm_sbmt_vndr_tx", "idx_bcm_sbmt_open")
    }

    @Autowired
    lateinit var accountRepository: com.whatto.bcm.domain.account.AccountRepository

    @Test
    fun `조립 검증 — persistence 어댑터가 앱 컨텍스트에 domain 포트로 배선된다`() {
        val account =
            com.whatto.bcm.domain.account.Account(
                accountId = "acct_boot",
                accountType = com.whatto.bcm.domain.account.AccountType.CUSTOMER,
                ref = "000BOOT",
                vendorVaultId = "vault-1",
                registeredAt = "20260805120000",
            )
        accountRepository.insert(account)
        assertThat(accountRepository.findByTypeAndRef(com.whatto.bcm.domain.account.AccountType.CUSTOMER, "000BOOT")).isEqualTo(account)
    }

    @Test
    fun `bcm_tx_l 에 벤더 원어 보관 컬럼이 있다 — 03 확정 이력(2026-08-05)`() {
        val columns =
            jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'bcm_tx_l'",
                String::class.java,
            )
        assertThat(columns).contains("vndr_sub_stcd", "vndr_ntwk_stcd")
    }

    @Test
    fun `V1 웹훅 원본 컬럼은 와이어 바이트와 서명을 보존한다 — 03 확정 이력(2026-08-06)`() {
        val webhookColumns =
            jdbcTemplate
                .queryForList(
                    "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = 'bcm_whk_l'",
                ).associateBy { it["column_name"] as String }
        val rawTransactionColumns =
            jdbcTemplate
                .queryForList(
                    "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = 'bcm_raw_tx_l'",
                ).associateBy { it["column_name"] as String }

        assertThat(webhookColumns.getValue("payload")["data_type"]).isEqualTo("text")
        assertThat(webhookColumns).containsKeys("payload_hash", "sign_vl")
        assertThat(webhookColumns.getValue("payload_hash")["is_nullable"]).isEqualTo("NO")
        assertThat(webhookColumns.getValue("sign_vl")["is_nullable"]).isEqualTo("NO")
        assertThat(rawTransactionColumns).containsKey("sign_vl")
        assertThat(rawTransactionColumns.getValue("sign_vl")["is_nullable"]).isEqualTo("NO")
    }

    @Test
    fun `V1 웹훅 처리 상태는 P S F와 재시도 오류를 표현하고 상태 수신시각 인덱스로 집는다`() {
        val columns =
            jdbcTemplate
                .queryForList(
                    "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = 'bcm_whk_l'",
                ).associateBy { it["column_name"] as String }
        val pickIndexColumns =
            jdbcTemplate.queryForList(
                """
                SELECT a.attname
                FROM pg_class i
                JOIN pg_index ix ON i.oid = ix.indexrelid
                JOIN pg_class t ON t.oid = ix.indrelid
                JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                WHERE t.relname = 'bcm_whk_l' AND i.relname = 'idx_bcm_whk_pick'
                ORDER BY k.ord
                """.trimIndent(),
                String::class.java,
            )

        assertThat(columns).containsKeys("prcs_stcd", "rtry_cnt", "err_msg", "prcs_dttm")
        assertThat(columns).doesNotContainKey("prcs_yn")
        assertThat(columns.getValue("prcs_stcd")["is_nullable"]).isEqualTo("NO")
        assertThat(columns.getValue("rtry_cnt")["is_nullable"]).isEqualTo("NO")
        assertThat(columns.getValue("err_msg")["data_type"]).isEqualTo("character varying")
        assertThat(pickIndexColumns).containsExactly("prcs_stcd", "rcv_dttm")
    }

    @Test
    fun `앱 절대시각 원천은 UTC이고 HTTP 수신은 가상 스레드와 동시 연결 상한을 쓴다`() {
        val taskRanOnVirtualThread = AtomicBoolean(false)
        val taskCompleted = CountDownLatch(1)
        val tomcatWebServer = webServerApplicationContext.webServer as TomcatWebServer
        val protocolHandler = tomcatWebServer.tomcat.connector.protocolHandler as AbstractProtocol<*>

        taskExecutor.execute {
            taskRanOnVirtualThread.set(Thread.currentThread().isVirtual)
            taskCompleted.countDown()
        }

        assertThat(clock.zone).isEqualTo(ZoneOffset.UTC)
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean::class.java)).isTrue()
        assertThat(protocolHandler.maxConnections).isEqualTo(100)
        assertThat(protocolHandler.executor.javaClass.simpleName).contains("VirtualThreadExecutor")
        assertThat(taskCompleted.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(taskRanOnVirtualThread).isTrue()
    }

    companion object {
        private val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
    }
}
