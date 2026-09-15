package com.whatto.bcm.infra.persistence.asset

import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.VendorAssetMappingRegistrationConflictException
import com.whatto.bcm.infra.persistence.account.DepositAddressJdbcAdapter
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@DataJdbcTest
@Import(VendorAssetMappingJdbcAdapter::class, VendorBlockchainCatalogJdbcAdapter::class, DepositAddressJdbcAdapter::class)
class VendorAssetMappingPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var mappings: VendorAssetMappingJdbcAdapter

    @Autowired
    lateinit var blockchains: VendorBlockchainCatalogJdbcAdapter

    @Autowired
    lateinit var addresses: DepositAddressJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun mapping(
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        vendorAssetId: String = "USDC_ERC20",
    ) = VendorAssetMapping(
        network,
        symbol,
        vendorAssetId,
        "0xA0B8$network",
        "20260806120000",
        "123456",
        "0001",
    )

    @BeforeEach
    fun seedAdoptedNetworks() {
        blockchains.insert(VendorBlockchainCatalog("ethereum-id", "ETHEREUM", 1, "Ethereum", false, false, "20260806110000"))
        blockchains.insert(VendorBlockchainCatalog("base-id", "BASE", 8453, "Base", false, false, "20260806110000"))
    }

    @Test
    fun `매핑 왕복 — 복합 키·네트워크 목록·등록 순 목록이 같은 값을 돌려준다`() {
        val ethereum = mappings.insert(mapping())
        val base = mappings.insert(mapping("BASE", vendorAssetId = "USDC_BASE"))

        assertThat(mappings.find("ETHEREUM", "USDC")).isEqualTo(ethereum)
        assertThat(mappings.findAll()).containsExactly(base, ethereum)
        assertThat(mappings.findAll(network = "BASE")).containsExactly(base)
        assertThat(mappings.findAll(symbol = "USDC")).containsExactly(base, ethereum)
        assertThat(mappings.existsByNetwork("BASE")).isTrue()
    }

    @Test
    fun `벤더 assetId UNIQUE — 다른 우리 자산에 같은 id를 넣으면 ConflictException이다`() {
        mappings.insert(mapping())

        assertThatThrownBy {
            mappings.insert(mapping(network = "BASE", vendorAssetId = "USDC_ERC20"))
        }.isInstanceOf(VendorAssetMappingRegistrationConflictException::class.java)
            .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `일괄 저장은 뒤 항목이 실패하면 현재 매핑과 변경 snapshot을 모두 롤백한다`() {
        try {
            assertThatThrownBy {
                mappings.saveAll(
                    listOf(
                        mapping(vendorAssetId = "DUPLICATE_ASSET"),
                        mapping(network = "BASE", vendorAssetId = "DUPLICATE_ASSET"),
                    ),
                    "bulk-request",
                )
            }.isInstanceOf(VendorAssetMappingRegistrationConflictException::class.java)

            assertThat(mappings.findAll()).isEmpty()
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bcm_vndr_ast_chng_l", Int::class.java)).isZero()
        } finally {
            jdbc.update("DELETE FROM bcm_vndr_ast_chng_l")
            jdbc.update("DELETE FROM bcm_vndr_ast_m")
            jdbc.update("DELETE FROM bcm_blkc_m WHERE vndr_blkc_id IN ('ethereum-id', 'base-id')")
        }
    }

    @Test
    fun `우리 복합 키 중복은 ConflictException이다`() {
        mappings.insert(mapping())

        assertThatThrownBy {
            mappings.insert(mapping(vendorAssetId = "OTHER_ASSET"))
        }.isInstanceOf(VendorAssetMappingRegistrationConflictException::class.java)
    }

    @Test
    fun `미채택 network FK 위반은 ConflictException이다`() {
        blockchains.insert(
            VendorBlockchainCatalog("polygon-id", null, 137, "Polygon", false, false, "20260806110000"),
        )
        assertThatThrownBy {
            mappings.insert(mapping(network = "POLYGON", vendorAssetId = "USDC_POLYGON"))
        }.isInstanceOf(VendorAssetMappingRegistrationConflictException::class.java)
    }

    @Test
    fun `길이 초과 데이터 결함은 ConflictException으로 오분류하지 않는다`() {
        assertThatThrownBy {
            mappings.insert(mapping(vendorAssetId = "A".repeat(129)))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .isNotInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `Admin 감사 4컬럼은 시스템 센티넬이 아니라 요청 직원·부점으로 모두 저장된다`() {
        mappings.insert(mapping())

        val audit =
            jdbc.queryForMap(
                """
                SELECT frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd
                  FROM bcm_vndr_ast_m WHERE ntwk_cd = 'ETHEREUM' AND tkn_smbl = 'USDC'
                """.trimIndent(),
            )
        assertThat(audit.values).containsOnly("123456", "0001")
    }

    @Test
    fun `주소 사용 여부와 매핑 삭제 — 자산 전체 주소를 계정과 무관하게 확인한다`() {
        mappings.insert(mapping())
        assertThat(addresses.existsByAsset("ETHEREUM", "USDC")).isFalse()

        addresses.insert(DepositAddress("acct_1", "ETHEREUM", "USDC", "0xABC", "20260806120000"))
        assertThat(addresses.existsByAsset("ETHEREUM", "USDC")).isTrue()

        mappings.deactivate("ETHEREUM", "USDC", "123456", "0001", "request-1", "20260806130000")
        assertThat(mappings.find("ETHEREUM", "USDC")).isNull()
        assertThat(mappings.findCurrent("ETHEREUM", "USDC")?.active).isFalse()
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM bcm_vndr_ast_chng_l WHERE ntwk_cd = 'ETHEREUM' AND tkn_smbl = 'USDC'",
                Int::class.java,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `논리 해제 뒤 다른 자산을 등록하면 현재 행을 교체하고 전후 snapshot을 남긴다`() {
        mappings.insert(mapping())
        mappings.deactivate("ETHEREUM", "USDC", "123456", "0001", "request-off", "20260806130000")

        val replaced = mappings.save(mapping(vendorAssetId = "USDC_V2"), "request-replace")

        assertThat(replaced.active).isTrue()
        assertThat(mappings.find("ETHEREUM", "USDC")?.vendorAssetId).isEqualTo("USDC_V2")
        val actions =
            jdbc.queryForList(
                "SELECT actn_dvcd FROM bcm_vndr_ast_chng_l WHERE ntwk_cd = 'ETHEREUM' AND tkn_smbl = 'USDC'",
                String::class.java,
            )
        assertThat(actions).containsExactlyInAnyOrder("REGISTER", "DEACTIVATE", "REPLACE")
        val replace =
            jdbc.queryForMap(
                """
                SELECT before_snps ->> 'vendorAssetId' AS before_id,
                       after_snps ->> 'vendorAssetId' AS after_id
                  FROM bcm_vndr_ast_chng_l
                 WHERE ntwk_cd = 'ETHEREUM' AND tkn_smbl = 'USDC' AND actn_dvcd = 'REPLACE'
                """.trimIndent(),
            )
        assertThat(replace).containsEntry("before_id", "USDC_ERC20").containsEntry("after_id", "USDC_V2")
    }
}
