package com.whatto.bcm.domain.webhook

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 인박스 재시도 대기(03 V29). backoff가 없으면 워커 주기(기본 500ms)마다 다시 집혀
 * 상한을 몇 초 만에 소진하고, 일시적 사정으로 실패한 건이 해소될 시간을 얻지 못한다.
 */
class WebhookRetryBackoffTest {
    @Test
    fun `시도마다 대기를 두 배로 늘리되 상한에서 멈춘다`() {
        assertThat(WebhookRetryBackoff.delaySeconds(1)).isEqualTo(30)
        assertThat(WebhookRetryBackoff.delaySeconds(2)).isEqualTo(60)
        assertThat(WebhookRetryBackoff.delaySeconds(3)).isEqualTo(120)
        // 무한히 늘리면 해소된 뒤에도 오래 잠든다 — 10분에서 멈춘다.
        assertThat(WebhookRetryBackoff.delaySeconds(10)).isEqualTo(WebhookRetryBackoff.MAX_SECONDS)
        // 큰 시도 횟수에서도 오버플로 없이 상한을 돌려준다.
        assertThat(WebhookRetryBackoff.delaySeconds(1_000)).isEqualTo(WebhookRetryBackoff.MAX_SECONDS)
    }

    @Test
    fun `운영 설정으로 기준 대기를 바꿀 수 있고 0이면 즉시 재시도한다`() {
        assertThat(WebhookRetryBackoff.delaySeconds(1, baseSeconds = 5)).isEqualTo(5)
        assertThat(WebhookRetryBackoff.delaySeconds(3, baseSeconds = 5)).isEqualTo(20)
        assertThat(WebhookRetryBackoff.delaySeconds(1, baseSeconds = 0)).isEqualTo(0)
        assertThat(WebhookRetryBackoff.delaySeconds(9, baseSeconds = 0)).isEqualTo(0)
    }

    @Test
    fun `시도 횟수와 기준 대기의 범위를 검사한다`() {
        assertThatThrownBy { WebhookRetryBackoff.delaySeconds(0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WebhookRetryBackoff.delaySeconds(1, baseSeconds = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
