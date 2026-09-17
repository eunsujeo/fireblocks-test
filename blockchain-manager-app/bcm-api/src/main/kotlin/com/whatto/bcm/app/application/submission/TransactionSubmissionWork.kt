package com.whatto.bcm.app.application.submission

/**
 * 공개 `POST /transactions`의 제출 경계 — 제공자마다 **하나만** 조립한다(계약13 "출금 제출 계약").
 *
 * 02의 선기록·소유권·멱등·`FAILED` 판정은 제공자와 무관하게 같고 **회수 수단만 다르다**:
 * Fireblocks는 `externalTxId` 단건 조회, Dfns는 같은 본문 멱등 재제출이다.
 * 그래서 공통 계약을 이 경계로 두고 회수를 구현이 정한다 — 컨트롤러는 어느 제공자인지 모른다.
 */
interface TransactionSubmissionWork {
    fun submit(command: TransactionSubmissionCommand): TransactionSubmissionResult
}
