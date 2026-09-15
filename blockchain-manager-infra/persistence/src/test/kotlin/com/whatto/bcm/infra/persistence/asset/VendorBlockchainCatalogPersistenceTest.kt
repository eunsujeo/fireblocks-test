package com.whatto.bcm.infra.persistence.asset

import com.whatto.bcm.domain.asset.ChainModel
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
@Import(VendorBlockchainCatalogJdbcAdapter::class, VendorAssetMappingJdbcAdapter::class)
class VendorBlockchainCatalogPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var catalogs: VendorBlockchainCatalogJdbcAdapter

    @Autowired
    lateinit var mappings: VendorAssetMappingJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `동기화 행 왕복과 adopted·testnet 필터가 우리 카탈로그만 돌려준다`() {
        val ethereum = catalogs.insert(catalog("ethereum-id", "ETHEREUM", testnet = false))
        val sepolia = catalogs.insert(catalog("sepolia-id", null, testnet = true))

        assertThat(catalogs.findByCandidateId("ethereum-id")).isEqualTo(ethereum)
        assertThat(catalogs.findByNetwork("ETHEREUM")).isEqualTo(ethereum)
        assertThat(catalogs.findAll(adopted = true)).containsExactly(ethereum)
        assertThat(catalogs.findAll(adopted = false, testnet = true)).containsExactly(sepolia)
        assertThat(catalogs.findAll(query = "HERE", chainId = 1)).containsExactly(ethereum)
    }

    @Test
    fun `계정·자산 모델은 seed 행이 채우고 동기화 갱신이 덮지 않으며 허용 값 밖은 CHECK가 막는다`() {
        val solana = catalogs.insert(catalog("SolanaDevnet", "SOLANA_DEVNET").copy(chainId = null, chainModel = ChainModel.SOLANA))
        assertThat(catalogs.findByNetwork("SOLANA_DEVNET")).isEqualTo(solana)
        assertThat(catalogs.findByCandidateId("SolanaDevnet")?.chainModel).isEqualTo(ChainModel.SOLANA)

        val synced = catalogs.updateSnapshot(solana.copy(displayName = "Solana Devnet (synced)", chainModel = null))
        assertThat(synced.chainModel).isEqualTo(ChainModel.SOLANA)
        assertThat(synced.displayName).isEqualTo("Solana Devnet (synced)")
        assertThat(catalogs.insert(catalog("ethereum-id", "ETHEREUM")).chainModel).isNull()

        assertThatThrownBy {
            jdbc.update("UPDATE bcm_blkc_m SET chain_mdl_dvcd = 'TRON' WHERE vndr_blkc_id = 'SolanaDevnet'")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `네트워크 이름 검색은 Fireblocks 표시명뿐 아니라 BCM 코드도 찾는다`() {
        val adopted =
            catalogs.insert(
                catalog("custom-chain-id", "CUSTOM_MAIN").copy(displayName = "Custom Chain"),
            )

        assertThat(catalogs.findAll(query = "main")).containsExactly(adopted)
        assertThat(catalogs.findAll(query = "chain")).containsExactly(adopted)
    }

    @Test
    fun `채택은 실제 감사값을 남기고 같은 network를 다른 후보에 붙이면 충돌한다`() {
        catalogs.insert(catalog("ethereum-id"))
        catalogs.insert(catalog("base-id"))

        val adopted = catalogs.adopt("ethereum-id", "ETHEREUM", "123456", "0001")
        assertThat(adopted.network).isEqualTo("ETHEREUM")
        val audit =
            jdbc.queryForMap(
                """
                SELECT last_chng_empno, last_chng_brcd FROM bcm_blkc_m
                 WHERE vndr_blkc_id = 'ethereum-id'
                """.trimIndent(),
            )
        assertThat(audit.values).containsOnly("123456", "0001")

        assertThat(catalogs.adopt("ethereum-id", "ETHEREUM", "654321", "0002")).isEqualTo(adopted)
        assertThatThrownBy {
            catalogs.adopt("base-id", "ETHEREUM", "654321", "0002")
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `같은 후보를 다른 network로 재채택하면 충돌한다`() {
        catalogs.insert(catalog("ethereum-id", "ETHEREUM"))

        assertThatThrownBy {
            catalogs.adopt("ethereum-id", "BASE", "654321", "0002")
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `채택 해제는 행을 보존하고 network만 비운다`() {
        catalogs.insert(catalog("ethereum-id", "ETHEREUM"))

        assertThat(catalogs.release("ETHEREUM", "123456", "0001")).isTrue()

        assertThat(catalogs.findByCandidateId("ethereum-id")?.network).isNull()
        assertThat(catalogs.release("ETHEREUM", "123456", "0001")).isFalse()
    }

    @Test
    fun `동기화 갱신은 시스템 감사를 남기고 비어 있던 chainId만 채운다`() {
        catalogs.insert(catalog("ethereum-id").copy(chainId = null))
        catalogs.adopt("ethereum-id", "ETHEREUM", "123456", "0001")

        val updated =
            catalogs.updateSnapshot(
                catalog("ethereum-id", "ETHEREUM").copy(displayName = "Ethereum", chainId = 1),
            )

        assertThat(updated.chainId).isEqualTo(1)
        val audit =
            jdbc.queryForMap(
                """
                SELECT last_chng_empno, last_chng_brcd FROM bcm_blkc_m
                 WHERE vndr_blkc_id = 'ethereum-id'
                """.trimIndent(),
            )
        assertThat(audit.values).containsOnly("SYSTEM", "9999")
    }

    @Test
    fun `매핑이 남은 network는 FK가 채택 해제를 원자적으로 막는다`() {
        catalogs.insert(catalog("ethereum-id", "ETHEREUM"))
        mappings.insert(
            VendorAssetMapping(
                "ETHEREUM",
                "USDC",
                "asset-id",
                "0xA0B8",
                "20260806120000",
                "123456",
                "0001",
            ),
        )

        assertThatThrownBy {
            catalogs.release("ETHEREUM", "123456", "0001")
        }.isInstanceOf(ConflictException::class.java)
    }

    private fun catalog(
        candidateId: String,
        network: String? = null,
        testnet: Boolean = false,
    ) = VendorBlockchainCatalog(candidateId, network, 1, candidateId, testnet, false, "20260806120000")
}
