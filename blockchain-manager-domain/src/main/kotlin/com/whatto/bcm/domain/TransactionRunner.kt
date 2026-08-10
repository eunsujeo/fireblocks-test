package com.whatto.bcm.domain

/** 애플리케이션 유스케이스를 기술 프레임워크 노출 없이 DB 트랜잭션으로 묶는 포트. */
interface TransactionRunner {
    fun <T> run(block: () -> T): T
}
