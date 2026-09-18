package com.whatto.bcm.domain.submission

import com.whatto.bcm.domain.exception.InvalidRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 제출 요청 자체의 공통 규칙(02 "신규 키 선행 검사"). **제공자와 무관하게 같다.**
 */
class SubmissionRequestPolicyTest {
    @Test
    fun `자기 계정으로 보내는 요청은 거절한다`() {
        // 같은 주소로 가는 온체인 전송이라 잔액은 그대로고 가스만 태운다.
        assertThatThrownBy {
            SubmissionRequestPolicy.requireDistinctAccounts("acct-1", SubmissionRecipientType.ACCOUNT, "acct-1")
        }.isInstanceOfSatisfying(InvalidRequestException::class.java) {
            assertThat(it.field).isEqualTo("recipient")
        }
    }

    @Test
    fun `다른 계정이면 통과한다`() {
        SubmissionRequestPolicy.requireDistinctAccounts("acct-1", SubmissionRecipientType.ACCOUNT, "acct-2")
    }

    @Test
    fun `계정 목적지가 아니면 값이 같아도 보지 않는다`() {
        // 주소·화이트리스트 값은 계정 ID와 같은 이름공간이 아니다 — 우연히 문자열이 같다고 자기 전송이 아니다.
        SubmissionRequestPolicy.requireDistinctAccounts("acct-1", SubmissionRecipientType.ADDRESS, "acct-1")
        SubmissionRequestPolicy.requireDistinctAccounts("acct-1", SubmissionRecipientType.WHITELISTED, "acct-1")
    }

    @Test
    fun `판정만 하는 입구는 원장을 읽지 않고 가릴 수 있게 한다`() {
        // 호출자가 이 값을 먼저 보면 자기 전송이 아닌 요청은 원장 조회 없이 끝난다 — 거의 모든 요청이 그렇다.
        assertThat(SubmissionRequestPolicy.isSelfTransfer("acct-1", SubmissionRecipientType.ACCOUNT, "acct-1")).isTrue()
        assertThat(SubmissionRequestPolicy.isSelfTransfer("acct-1", SubmissionRecipientType.ACCOUNT, "acct-2")).isFalse()
        assertThat(SubmissionRequestPolicy.isSelfTransfer("acct-1", SubmissionRecipientType.ADDRESS, "acct-1")).isFalse()
    }
}
