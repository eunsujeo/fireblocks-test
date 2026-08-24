package com.whatto.bcm.app.api.web

import com.whatto.bcm.domain.exception.AccountNotFoundException
import com.whatto.bcm.domain.exception.AssetNotSupportedException
import com.whatto.bcm.domain.exception.BcmException
import com.whatto.bcm.domain.exception.BulkAssetMappingException
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.exception.VendorAssetMappingRegistrationConflictException

/**
 * 도메인 예외(sealed) → ErrorCode 매핑 전담 — exhaustive when 이라 새 예외 추가 시 컴파일 에러로 누락을 잡는다.
 */
object DomainExceptionResolver {
    fun resolve(exception: BcmException): ErrorCode =
        when (exception) {
            is AccountNotFoundException -> ErrorCode.ACCOUNT_NOT_FOUND
            is AssetNotSupportedException -> ErrorCode.ASSET_NOT_SUPPORTED
            is BulkAssetMappingException -> resolve(exception.failure)
            is ResourceNotFoundException -> ErrorCode.NOT_FOUND
            is ConflictException -> ErrorCode.CONFLICT
            is SubmissionInProgressException -> ErrorCode.SUBMIT_IN_PROGRESS
            is InvalidAssetMappingException -> ErrorCode.VALIDATION_FAILED
            is VendorAssetMappingRegistrationConflictException -> ErrorCode.CONFLICT
            is InvalidRequestException -> ErrorCode.VALIDATION_FAILED
            is RelayRejectedException -> ErrorCode.RELAY_REJECTED
            is VendorApiException -> ErrorCode.INTERNAL // 벤더 실패용 별도 코드가 스펙에 없다 (RELAY_REJECTED 는 대납 relay 전용)
        }
}
