package com.whatto.bcm.infra.persistence.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountCreationIntent
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.CreationStatus
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressCreationIntent
import com.whatto.bcm.domain.account.WalletProvisioningRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.CreationRetryLaterException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class WalletProvisioningJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : WalletProvisioningRepository {
    private val accountIntentMapper =
        RowMapper { rs, _ ->
            AccountCreationIntent(
                accountId = rs.getString("acnt_id"),
                accountType = AccountTypeCodes.toDomain(rs.getString("acnt_typ_dvcd")),
                ref = rs.getString("ref"),
                vendorVaultName = rs.getString("vndr_vlt_nm"),
                idempotencyKey = rs.getString("idmp_key"),
                idempotencyKeyRegisteredAt = rs.getString("idmp_key_reg_dttm"),
                lastVendorCallPreparedAt = rs.getString("last_vndr_call_dttm"),
                status = CreationStatus.valueOf(rs.getString("crtn_stcd")),
                attemptCount = rs.getInt("try_cnt"),
                vendorVaultId = rs.getString("vndr_vlt_id"),
                registeredAt = rs.getString("reg_dttm"),
                lastChangedAt = rs.getString("last_chng_dttm"),
            )
        }
    private val addressIntentMapper =
        RowMapper { rs, _ ->
            DepositAddressCreationIntent(
                accountId = rs.getString("acnt_id"),
                network = rs.getString("ntwk_cd"),
                symbol = rs.getString("tkn_smbl"),
                vendorAssetId = rs.getString("vndr_ast_id"),
                idempotencyKey = rs.getString("idmp_key"),
                idempotencyKeyRegisteredAt = rs.getString("idmp_key_reg_dttm"),
                lastVendorCallPreparedAt = rs.getString("last_vndr_call_dttm"),
                status = CreationStatus.valueOf(rs.getString("crtn_stcd")),
                attemptCount = rs.getInt("try_cnt"),
                address = rs.getString("dpst_addr"),
                registeredAt = rs.getString("reg_dttm"),
                lastChangedAt = rs.getString("last_chng_dttm"),
            )
        }
    private val accountMapper =
        RowMapper { rs, _ ->
            Account(
                accountId = rs.getString("acnt_id"),
                accountType = AccountTypeCodes.toDomain(rs.getString("acnt_typ_dvcd")),
                ref = rs.getString("ref"),
                vendorVaultId = rs.getString("vndr_vlt_id"),
                registeredAt = rs.getString("reg_dttm"),
            )
        }
    private val addressMapper =
        RowMapper { rs, _ ->
            DepositAddress(
                accountId = rs.getString("acnt_id"),
                network = rs.getString("ntwk_cd"),
                symbol = rs.getString("tkn_smbl"),
                address = rs.getString("dpst_addr"),
                registeredAt = rs.getString("reg_dttm"),
            )
        }

    @Transactional
    override fun reserveAccount(intent: AccountCreationIntent): AccountCreationIntent {
        jdbc.update(
            """
            INSERT INTO bcm_acnt_crtn_l
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_nm, idmp_key, idmp_key_reg_dttm,
               last_vndr_call_dttm, crtn_stcd, try_cnt,
               vndr_vlt_id, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:accountId, :accountType, :ref, :vendorVaultName, :idempotencyKey, :idempotencyKeyRegisteredAt,
               :lastVendorCallPreparedAt, :status, :attemptCount,
               NULL, :registeredAt, :lastChangedAt, :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT (acnt_typ_dvcd, ref) DO NOTHING
            """.trimIndent(),
            accountIntentParameters(intent),
        )
        return checkNotNull(findAccount(intent.accountType, intent.ref))
    }

    override fun findAccount(
        accountType: AccountType,
        ref: String,
    ): AccountCreationIntent? =
        jdbc
            .query(
                "$ACCOUNT_INTENT_SELECT WHERE acnt_typ_dvcd = :accountType AND ref = :ref",
                mapOf("accountType" to AccountTypeCodes.toCode(accountType), "ref" to ref),
                accountIntentMapper,
            ).firstOrNull()

    @Transactional
    override fun beginAccountAttempt(
        accountId: String,
        changedAt: String,
    ): AccountCreationIntent =
        jdbc
            .query(
                """
                UPDATE bcm_acnt_crtn_l
                   SET crtn_stcd = 'SUBMITTING', try_cnt = try_cnt + 1,
                       last_chng_dttm = :changedAt, last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                 WHERE acnt_id = :accountId AND crtn_stcd <> 'COMPLETED'
                RETURNING acnt_id, acnt_typ_dvcd, ref, vndr_vlt_nm, idmp_key, idmp_key_reg_dttm,
                          last_vndr_call_dttm, crtn_stcd, try_cnt, vndr_vlt_id, reg_dttm, last_chng_dttm
                """.trimIndent(),
                systemParameters() + mapOf("accountId" to accountId, "changedAt" to changedAt),
                accountIntentMapper,
            ).firstOrNull()
            ?: findAccountById(accountId)

    @Transactional
    override fun prepareAccountVendorCall(
        intent: AccountCreationIntent,
        idempotencyKey: String,
        idempotencyKeyRegisteredAt: String,
        calledAt: String,
    ): AccountCreationIntent? =
        jdbc
            .query(
                """
                UPDATE bcm_acnt_crtn_l
                   SET idmp_key = :idempotencyKey, idmp_key_reg_dttm = :idempotencyKeyRegisteredAt,
                       last_vndr_call_dttm = :calledAt, last_chng_dttm = :calledAt,
                       last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                 WHERE acnt_id = :accountId
                   AND crtn_stcd = 'SUBMITTING'
                   AND try_cnt = :attemptCount
                   AND idmp_key = :expectedIdempotencyKey
                   AND idmp_key_reg_dttm = :expectedIdempotencyKeyRegisteredAt
                   AND last_vndr_call_dttm IS NOT DISTINCT FROM :expectedLastVendorCallPreparedAt
                RETURNING acnt_id, acnt_typ_dvcd, ref, vndr_vlt_nm, idmp_key, idmp_key_reg_dttm,
                          last_vndr_call_dttm, crtn_stcd, try_cnt, vndr_vlt_id, reg_dttm, last_chng_dttm
                """.trimIndent(),
                systemParameters() +
                    mapOf(
                        "accountId" to intent.accountId,
                        "attemptCount" to intent.attemptCount,
                        "expectedIdempotencyKey" to intent.idempotencyKey,
                        "expectedIdempotencyKeyRegisteredAt" to intent.idempotencyKeyRegisteredAt,
                        "expectedLastVendorCallPreparedAt" to intent.lastVendorCallPreparedAt,
                        "idempotencyKey" to idempotencyKey,
                        "idempotencyKeyRegisteredAt" to idempotencyKeyRegisteredAt,
                        "calledAt" to calledAt,
                    ),
                accountIntentMapper,
            ).firstOrNull()

    @Transactional
    override fun completeAccount(
        expectedGeneration: AccountCreationIntent,
        vendorVaultId: String,
        completedAt: String,
    ): Account {
        val intent = findAccountByIdForUpdate(expectedGeneration.accountId)
        if (intent.status == CreationStatus.COMPLETED) return requiredAccountMapping(intent)
        if (!sameGeneration(intent, expectedGeneration)) {
            throw CreationRetryLaterException(intent.accountId, 1)
        }
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:accountId, :accountType, :ref, :vendorVaultId, :registeredAt,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            systemParameters() +
                mapOf(
                    "accountId" to intent.accountId,
                    "accountType" to AccountTypeCodes.toCode(intent.accountType),
                    "ref" to intent.ref,
                    "vendorVaultId" to vendorVaultId,
                    "registeredAt" to completedAt,
                ),
        )
        val account = requiredAccountMapping(intent)
        if (account.accountId != intent.accountId || account.vendorVaultId != vendorVaultId) {
            throw ConflictException("accountCreation", "${intent.accountType}:${intent.ref}")
        }
        jdbc
            .update(
                """
                UPDATE bcm_acnt_crtn_l
                   SET crtn_stcd = 'COMPLETED', vndr_vlt_id = :vendorVaultId,
                       last_chng_dttm = :completedAt, last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                 WHERE acnt_id = :accountId
                   AND idmp_key = :expectedIdempotencyKey
                   AND idmp_key_reg_dttm = :expectedIdempotencyKeyRegisteredAt
                   AND (vndr_vlt_id IS NULL OR vndr_vlt_id = :vendorVaultId)
                """.trimIndent(),
                systemParameters() +
                    mapOf(
                        "accountId" to intent.accountId,
                        "expectedIdempotencyKey" to expectedGeneration.idempotencyKey,
                        "expectedIdempotencyKeyRegisteredAt" to expectedGeneration.idempotencyKeyRegisteredAt,
                        "vendorVaultId" to vendorVaultId,
                        "completedAt" to completedAt,
                    ),
            ).also { updated ->
                if (updated != 1) throw ConflictException("accountCreation", intent.accountId)
            }
        return account
    }

    @Transactional
    override fun reserveAddress(intent: DepositAddressCreationIntent): DepositAddressCreationIntent {
        jdbc.update(
            """
            INSERT INTO bcm_addr_crtn_l
              (acnt_id, ntwk_cd, tkn_smbl, vndr_ast_id, idmp_key, idmp_key_reg_dttm,
               last_vndr_call_dttm, crtn_stcd, try_cnt,
               dpst_addr, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:accountId, :network, :symbol, :vendorAssetId, :idempotencyKey, :idempotencyKeyRegisteredAt,
               :lastVendorCallPreparedAt, :status, :attemptCount,
               NULL, :registeredAt, :lastChangedAt, :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT (acnt_id, ntwk_cd, tkn_smbl) DO NOTHING
            """.trimIndent(),
            addressIntentParameters(intent),
        )
        return checkNotNull(findAddress(intent.accountId, intent.network, intent.symbol))
    }

    override fun findAddress(
        accountId: String,
        network: String,
        symbol: String,
    ): DepositAddressCreationIntent? =
        jdbc
            .query(
                "$ADDRESS_INTENT_SELECT WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol",
                mapOf("accountId" to accountId, "network" to network, "symbol" to symbol),
                addressIntentMapper,
            ).firstOrNull()

    @Transactional
    override fun beginAddressAttempt(
        accountId: String,
        network: String,
        symbol: String,
        changedAt: String,
    ): DepositAddressCreationIntent =
        jdbc
            .query(
                """
                UPDATE bcm_addr_crtn_l
                   SET crtn_stcd = 'SUBMITTING', try_cnt = try_cnt + 1,
                       last_chng_dttm = :changedAt, last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                 WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
                   AND crtn_stcd <> 'COMPLETED'
                RETURNING acnt_id, ntwk_cd, tkn_smbl, vndr_ast_id, idmp_key, idmp_key_reg_dttm,
                          last_vndr_call_dttm, crtn_stcd, try_cnt, dpst_addr, reg_dttm, last_chng_dttm
                """.trimIndent(),
                systemParameters() +
                    mapOf(
                        "accountId" to accountId,
                        "network" to network,
                        "symbol" to symbol,
                        "changedAt" to changedAt,
                    ),
                addressIntentMapper,
            ).firstOrNull()
            ?: findAddress(accountId, network, symbol)
            ?: throw ResourceNotFoundException("depositAddressCreationIntent", "$accountId:$network:$symbol")

    @Transactional
    override fun prepareAddressVendorCall(
        intent: DepositAddressCreationIntent,
        idempotencyKey: String,
        idempotencyKeyRegisteredAt: String,
        calledAt: String,
    ): DepositAddressCreationIntent? =
        jdbc
            .query(
                """
                UPDATE bcm_addr_crtn_l
                   SET idmp_key = :idempotencyKey, idmp_key_reg_dttm = :idempotencyKeyRegisteredAt,
                       last_vndr_call_dttm = :calledAt, last_chng_dttm = :calledAt,
                       last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                 WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
                   AND crtn_stcd = 'SUBMITTING'
                   AND try_cnt = :attemptCount
                   AND idmp_key = :expectedIdempotencyKey
                   AND idmp_key_reg_dttm = :expectedIdempotencyKeyRegisteredAt
                   AND last_vndr_call_dttm IS NOT DISTINCT FROM :expectedLastVendorCallPreparedAt
                RETURNING acnt_id, ntwk_cd, tkn_smbl, vndr_ast_id, idmp_key, idmp_key_reg_dttm,
                          last_vndr_call_dttm, crtn_stcd, try_cnt, dpst_addr, reg_dttm, last_chng_dttm
                """.trimIndent(),
                systemParameters() +
                    mapOf(
                        "accountId" to intent.accountId,
                        "network" to intent.network,
                        "symbol" to intent.symbol,
                        "attemptCount" to intent.attemptCount,
                        "expectedIdempotencyKey" to intent.idempotencyKey,
                        "expectedIdempotencyKeyRegisteredAt" to intent.idempotencyKeyRegisteredAt,
                        "expectedLastVendorCallPreparedAt" to intent.lastVendorCallPreparedAt,
                        "idempotencyKey" to idempotencyKey,
                        "idempotencyKeyRegisteredAt" to idempotencyKeyRegisteredAt,
                        "calledAt" to calledAt,
                    ),
                addressIntentMapper,
            ).firstOrNull()

    @Transactional
    override fun completeAddress(
        expectedGeneration: DepositAddressCreationIntent,
        address: String,
        completedAt: String,
    ): DepositAddress {
        val intent = findAddressForUpdate(expectedGeneration)
        val resourceKey = "${intent.accountId}:${intent.network}:${intent.symbol}"
        if (intent.status == CreationStatus.COMPLETED) return requiredAddressMapping(intent)
        if (!sameGeneration(intent, expectedGeneration)) {
            throw CreationRetryLaterException(resourceKey, 1)
        }
        jdbc.update(
            """
            INSERT INTO bcm_addr_m
              (acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:accountId, :network, :symbol, :address, :registeredAt,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            systemParameters() +
                mapOf(
                    "accountId" to intent.accountId,
                    "network" to intent.network,
                    "symbol" to intent.symbol,
                    "address" to address,
                    "registeredAt" to completedAt,
                ),
        )
        val depositAddress = requiredAddressMapping(intent)
        if (depositAddress.address != address) {
            throw ConflictException("depositAddressCreation", resourceKey)
        }
        jdbc
            .update(
                """
                UPDATE bcm_addr_crtn_l
                   SET crtn_stcd = 'COMPLETED', dpst_addr = :address,
                       last_chng_dttm = :completedAt, last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                 WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
                   AND idmp_key = :expectedIdempotencyKey
                   AND idmp_key_reg_dttm = :expectedIdempotencyKeyRegisteredAt
                   AND (dpst_addr IS NULL OR dpst_addr = :address)
                """.trimIndent(),
                systemParameters() +
                    mapOf(
                        "accountId" to intent.accountId,
                        "network" to intent.network,
                        "symbol" to intent.symbol,
                        "expectedIdempotencyKey" to expectedGeneration.idempotencyKey,
                        "expectedIdempotencyKeyRegisteredAt" to expectedGeneration.idempotencyKeyRegisteredAt,
                        "address" to address,
                        "completedAt" to completedAt,
                    ),
            ).also { updated ->
                if (updated != 1) throw ConflictException("depositAddressCreation", resourceKey)
            }
        return depositAddress
    }

    private fun findAccountById(accountId: String): AccountCreationIntent =
        jdbc
            .query(
                "$ACCOUNT_INTENT_SELECT WHERE acnt_id = :accountId",
                mapOf("accountId" to accountId),
                accountIntentMapper,
            ).firstOrNull()
            ?: throw ResourceNotFoundException("accountCreationIntent", accountId)

    private fun findAccountByIdForUpdate(accountId: String): AccountCreationIntent =
        jdbc
            .query(
                "$ACCOUNT_INTENT_SELECT WHERE acnt_id = :accountId FOR UPDATE",
                mapOf("accountId" to accountId),
                accountIntentMapper,
            ).firstOrNull()
            ?: throw ResourceNotFoundException("accountCreationIntent", accountId)

    private fun findAddressForUpdate(expected: DepositAddressCreationIntent): DepositAddressCreationIntent =
        jdbc
            .query(
                "$ADDRESS_INTENT_SELECT WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol FOR UPDATE",
                mapOf("accountId" to expected.accountId, "network" to expected.network, "symbol" to expected.symbol),
                addressIntentMapper,
            ).firstOrNull()
            ?: throw ResourceNotFoundException(
                "depositAddressCreationIntent",
                "${expected.accountId}:${expected.network}:${expected.symbol}",
            )

    private fun requiredAccountMapping(intent: AccountCreationIntent): Account =
        jdbc
            .query(
                "$ACCOUNT_SELECT WHERE acnt_typ_dvcd = :accountType AND ref = :ref",
                mapOf("accountType" to AccountTypeCodes.toCode(intent.accountType), "ref" to intent.ref),
                accountMapper,
            ).firstOrNull()
            ?: throw ResourceNotFoundException("account", "${intent.accountType}:${intent.ref}")

    private fun requiredAddressMapping(intent: DepositAddressCreationIntent): DepositAddress =
        jdbc
            .query(
                "$ADDRESS_SELECT WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol",
                mapOf("accountId" to intent.accountId, "network" to intent.network, "symbol" to intent.symbol),
                addressMapper,
            ).firstOrNull()
            ?: throw ResourceNotFoundException(
                "depositAddress",
                "${intent.accountId}:${intent.network}:${intent.symbol}",
            )

    private fun sameGeneration(
        current: AccountCreationIntent,
        expected: AccountCreationIntent,
    ): Boolean =
        current.idempotencyKey == expected.idempotencyKey &&
            current.idempotencyKeyRegisteredAt == expected.idempotencyKeyRegisteredAt

    private fun sameGeneration(
        current: DepositAddressCreationIntent,
        expected: DepositAddressCreationIntent,
    ): Boolean =
        current.idempotencyKey == expected.idempotencyKey &&
            current.idempotencyKeyRegisteredAt == expected.idempotencyKeyRegisteredAt

    private fun accountIntentParameters(intent: AccountCreationIntent): Map<String, Any?> =
        systemParameters() +
            mapOf(
                "accountId" to intent.accountId,
                "accountType" to AccountTypeCodes.toCode(intent.accountType),
                "ref" to intent.ref,
                "vendorVaultName" to intent.vendorVaultName,
                "idempotencyKey" to intent.idempotencyKey,
                "idempotencyKeyRegisteredAt" to intent.idempotencyKeyRegisteredAt,
                "lastVendorCallPreparedAt" to intent.lastVendorCallPreparedAt,
                "status" to intent.status.name,
                "attemptCount" to intent.attemptCount,
                "registeredAt" to intent.registeredAt,
                "lastChangedAt" to intent.lastChangedAt,
            )

    private fun addressIntentParameters(intent: DepositAddressCreationIntent): Map<String, Any?> =
        systemParameters() +
            mapOf(
                "accountId" to intent.accountId,
                "network" to intent.network,
                "symbol" to intent.symbol,
                "vendorAssetId" to intent.vendorAssetId,
                "idempotencyKey" to intent.idempotencyKey,
                "idempotencyKeyRegisteredAt" to intent.idempotencyKeyRegisteredAt,
                "lastVendorCallPreparedAt" to intent.lastVendorCallPreparedAt,
                "status" to intent.status.name,
                "attemptCount" to intent.attemptCount,
                "registeredAt" to intent.registeredAt,
                "lastChangedAt" to intent.lastChangedAt,
            )

    private fun systemParameters(): Map<String, Any> = mapOf("employeeNo" to SystemAudit.EMPNO, "branchCode" to SystemAudit.BRCD)

    private companion object {
        const val ACCOUNT_INTENT_SELECT =
            "SELECT acnt_id, acnt_typ_dvcd, ref, vndr_vlt_nm, idmp_key, idmp_key_reg_dttm, " +
                "last_vndr_call_dttm, crtn_stcd, try_cnt, vndr_vlt_id, reg_dttm, last_chng_dttm " +
                "FROM bcm_acnt_crtn_l"
        const val ADDRESS_INTENT_SELECT =
            "SELECT acnt_id, ntwk_cd, tkn_smbl, vndr_ast_id, idmp_key, idmp_key_reg_dttm, " +
                "last_vndr_call_dttm, crtn_stcd, try_cnt, dpst_addr, reg_dttm, last_chng_dttm " +
                "FROM bcm_addr_crtn_l"
        const val ACCOUNT_SELECT =
            "SELECT acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm FROM bcm_acnt_m"
        const val ADDRESS_SELECT =
            "SELECT acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm FROM bcm_addr_m"
    }
}
