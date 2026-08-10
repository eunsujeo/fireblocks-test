package com.whatto.bcm.infra.persistence.config

import com.whatto.bcm.domain.TransactionRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionRunner(
    transactionManager: PlatformTransactionManager,
) : TransactionRunner {
    private val transaction = TransactionTemplate(transactionManager)

    override fun <T> run(block: () -> T): T = checkNotNull(transaction.execute { block() })
}
