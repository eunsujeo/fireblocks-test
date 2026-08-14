package com.whatto.bcm.domain.archive

data class RawTransactionArchiveBatch(
    val candidateCount: Int,
    val archivedCount: Int,
)

interface RawTransactionArchiveRepository {
    fun archiveCompletedBatch(
        baseDate: String,
        receivedAtOrBefore: String,
        limit: Int,
    ): RawTransactionArchiveBatch

    fun deleteProcessedAtOrBefore(processedAtOrBefore: String): Int
}
