package com.whatto.bcm.app.application.sweep

import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.sweep.SweepAllowanceObservation
import com.whatto.bcm.domain.sweep.SweepAllowancePolicy
import com.whatto.bcm.domain.sweep.SweepAuthorization
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationRepository
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class SweepAuthorizationService(
    private val repository: SweepAuthorizationRepository,
) {
    fun findByNetworkAndContract(
        network: String,
        contractAddress: String,
    ): List<SweepAuthorization> = repository.findByNetworkAndContract(network, contractAddress)

    /** 요청 snapshot 기록과 같은 트랜잭션 안에서 잠금·현재 cap 기준 관찰·저장을 수행한다. */
    fun recordObservation(
        key: SweepAuthorizationKey,
        observation: SweepAllowanceObservation,
        now: Instant,
    ) {
        val current = repository.findByKeyForUpdate(key) ?: throw ResourceNotFoundException("sweepAuthorization", key.toString())
        val updated =
            SweepAllowancePolicy.observe(
                current,
                current.key,
                current.allowanceCap,
                observation,
                CoreDateTimes.format(LocalDateTime.ofInstant(now, ZoneOffset.UTC)),
            )
        repository.update(updated)
    }
}
