package com.whatto.bcm.testsupport.integration

import com.whatto.bcm.testsupport.database.PostgreSqlSchemaInitializer
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * 모듈 공용 PostgreSQL 컨테이너 — 싱글턴 재사용 (docs/testing.md: 테스트마다 새로 띄우지 않는다).
 * @Container 생명주기 대신 수동 start — JVM 종료까지 재사용, Testcontainers 가 정리한다.
 * PostgreSQL·Kafka 컨테이너를 JVM 동안 하나씩 재사용한다.
 */
abstract class IntegrationTestSupport {
    protected fun insertActiveSweepSnapshot(jdbc: JdbcTemplate): SweepSnapshotFixture {
        val fixture = SweepSnapshotFixture()
        val runtimeHash = sha256("runtime")
        val immutableHash = sha256("immutable")
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_vrsn_l
              (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr, release_cmit,
               artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash, deploy_blck_no,
               immut_payload, immut_hash, ceiling_payload, ceiling_hash, release_uri, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'ETHEREUM:SWEEP', 'ETHEREUM', 'SWEEP', 'fixture-v1', '0xSweeper', 'fixture-commit',
                    ?, ?, ?, '0xfixturedeploy', 1, '{}'::jsonb, ?, '{}'::jsonb, ?, 'fixture://release',
                    '20260819000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ctrt_vrsn_id) DO NOTHING
            """.trimIndent(),
            fixture.contractVersionId,
            sha256("artifact"),
            sha256("abi"),
            runtimeHash,
            immutableHash,
            sha256("contract-ceiling"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_evdc_l
              (evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash, exp_immut_hash, pin_blck_no,
               rpc1_id, rpc1_chain_id, rpc1_code_hash, rpc1_immut_hash, rpc1_obs_dttm,
               rpc2_id, rpc2_chain_id, rpc2_code_hash, rpc2_immut_hash, rpc2_obs_dttm,
               tap_mtch_yn, clbk_mtch_yn, gasless_pass_yn, audit_pass_yn, revoke_drill_yn, launch_gate_yn,
               evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc, doc_evdc_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, 1, ?, ?, 1, 'RPC_A', 1, ?, ?, '20260819000000',
                    'RPC_B', 1, ?, ?, '20260819000000', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y',
                    'VALID', '20260819000000', '20991231235959', '{}'::jsonb, ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (evdc_id) DO NOTHING
            """.trimIndent(),
            fixture.contractEvidenceId,
            fixture.contractVersionId,
            sha256("evidence"),
            runtimeHash,
            immutableHash,
            runtimeHash,
            immutableHash,
            runtimeHash,
            immutableHash,
            sha256("document"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_bind_m
              (ctrt_scope_id, ntwk_cd, use_dvcd, actv_ctrt_vrsn_id, bind_rvsn, last_evdc_id,
               bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('ETHEREUM:SWEEP', 'ETHEREUM', 'SWEEP', ?, 1, ?, ?,
                    '20260819000000', '20260819000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ctrt_scope_id) DO NOTHING
            """.trimIndent(),
            fixture.contractVersionId,
            fixture.contractEvidenceId,
            sha256("contract-binding"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_plcy_vrsn_l
              (plcy_vrsn_id, plcy_scope_id, vrsn_no, plcy_schm_vrsn, ctrt_vrsn_id,
               plcy_payload, plcy_hash, ceiling_snps, ceiling_hash, ceiling_pass_yn, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'POLICY:ETHEREUM:USDC', 1, 'fixture-v1', ?,
                    '{"enabled":true,"minimumAmount":1,"batchSize":25,"allowanceCap":1000,"itemAmountCap":1000,"batchAmountCap":10000,"boostAttempts":1}'::jsonb,
                    ?, '{}'::jsonb, ?, 'Y', '20260819000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (plcy_vrsn_id) DO NOTHING
            """.trimIndent(),
            fixture.policyVersionId,
            fixture.contractVersionId,
            sha256("policy"),
            sha256("policy-ceiling"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_plcy_bind_m
              (plcy_scope_id, actv_plcy_vrsn_id, bind_rvsn, bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('POLICY:ETHEREUM:USDC', ?, 1, ?, '20260819000000', '20260819000000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (plcy_scope_id) DO NOTHING
            """.trimIndent(),
            fixture.policyVersionId,
            fixture.policySnapshotHash,
        )
        return fixture
    }

    private fun sha256(seed: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:17-alpine")
                .apply {
                    start()
                    PostgreSqlSchemaInitializer.initialize(jdbcUrl, username, password)
                }

        @JvmStatic
        val kafka: KafkaContainer =
            KafkaContainer("apache/kafka-native:3.9.1")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun disableBackgroundWorker(registry: DynamicPropertyRegistry) {
            registry.add("bcm.webhook-worker.enabled") { "false" }
            registry.add("bcm.outbox-relay.enabled") { "false" }
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
            registry.add("spring.datasource.hikari.maximum-pool-size") { "2" }
        }
    }
}

data class SweepSnapshotFixture(
    val policyVersionId: String = "policy-ETHEREUM-USDC",
    val policySnapshotHash: String = "a".repeat(64),
    val contractVersionId: String = "contract-ETHEREUM",
    val contractEvidenceId: String = "evidence-ETHEREUM",
)
