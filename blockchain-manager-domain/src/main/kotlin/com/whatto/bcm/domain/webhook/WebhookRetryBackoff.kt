package com.whatto.bcm.domain.webhook

/**
 * 인박스 재시도의 대기 구간(03 V29).
 *
 * 워커는 가장 오래된 `P` 행을 주기마다 집으므로, 대기 시각이 없으면 실패가 반복될 때 상한을 **몇 초 만에** 소진한다.
 * 그러면 일시적 사정(벤더 지연, 아직 도착하지 않은 짝 알림)으로 실패하는 건은 해소될 시간을 얻지 못하고 격리된다.
 *
 * 시도마다 대기를 2배로 늘리되 상한을 둔다 — 무한히 늘리면 해소된 뒤에도 오래 잠자고, 상한이 없으면 운영이 예측할 수 없다.
 * 이 객체는 **식의 정본**이다 — 실제 대기 시각은 저장된 `rtry_cnt`와 원자적으로 계산해야 하므로 영속 어댑터의 SQL이 같은 식으로 채우고,
 * 두 구현이 어긋나지 않는지는 영속 테스트가 고정한다. 여기서는 시간 형식(support의 `yyyyMMddHHmmss`)을 알지 않고 **초만** 돌려준다(domain 무의존).
 */
object WebhookRetryBackoff {
    /** 첫 재시도 대기(초). 운영 설정으로 바꿀 수 있고 `0`이면 대기 없이 즉시 재시도한다(테스트·특수 운영). */
    const val BASE_SECONDS: Long = 30

    /** 대기 상한(초) — 10분. 이보다 길면 해소된 건이 너무 오래 잠든다. */
    const val MAX_SECONDS: Long = 600

    /**
     * [attempt]번째 실패 뒤 다음 시도까지의 대기(초). [attempt]는 1부터다.
     * 30s → 60s → 120s … 로 늘어나며 [MAX_SECONDS]에서 멈춘다.
     */
    fun delaySeconds(
        attempt: Int,
        baseSeconds: Long = BASE_SECONDS,
    ): Long {
        require(attempt >= 1) { "attempt must be at least 1" }
        require(baseSeconds >= 0) { "baseSeconds must not be negative" }
        if (baseSeconds == 0L) return 0
        // shift 대신 곱으로 올리며 상한에서 끊는다 — 큰 attempt에서 오버플로가 나지 않게 한다.
        var delay = baseSeconds
        repeat(attempt - 1) {
            if (delay >= MAX_SECONDS) return MAX_SECONDS
            delay *= 2
        }
        return minOf(delay, MAX_SECONDS)
    }
}
