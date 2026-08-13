package com.whatto.bcm.domain.job

data class JobState(
    val jobName: String,
    val lastRunAt: String,
    val lastSucceededAt: String?,
)

interface JobStateRepository {
    fun markStarted(
        jobName: String,
        at: String,
    )

    fun markSucceeded(
        jobName: String,
        at: String,
    )

    fun find(jobName: String): JobState?
}
