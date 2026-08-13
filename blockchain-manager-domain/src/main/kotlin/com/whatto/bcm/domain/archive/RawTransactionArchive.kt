package com.whatto.bcm.domain.archive

data class RawTransactionArchiveBatch(
    val candidateCount: Int,
    val archivedCount: Int,
)

interface RawTransactionArchiveRepository {
    fun archiveCompletedWindow(
        baseDate: String,
        receivedAtOrAfter: String,
        receivedAtOrBefore: String,
        limit: Int,
    ): RawTransactionArchiveBatch

    fun deleteProcessedAtOrBefore(processedAtOrBefore: String): Int
}
