package com.whatto.bcm.infra.persistence.asset

import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException

/** UNIQUE/FK 경합만 비즈니스 충돌로 번역하고 다른 데이터 결함은 시스템 오류로 보존한다. */
internal fun DataIntegrityViolationException.isConstraintViolation(vararg expectedStates: String): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is SQLException && current.sqlState in expectedStates) return true
        current = current.cause
    }
    return false
}
