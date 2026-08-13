package com.whatto.bcm.support.submission

import java.math.BigDecimal

object SubmissionAmounts {
    private const val MAX_INTEGER_DIGITS = 18
    private const val MAX_FRACTION_DIGITS = 18

    fun isValid(amount: String): Boolean {
        val value = amount.toBigDecimalOrNull() ?: return false
        if (value.signum() <= 0) return false

        return fitsNumeric(value)
    }

    fun isNonNegativeAndFits(amount: String): Boolean {
        val value = amount.toBigDecimalOrNull() ?: return false
        if (value.signum() < 0) return false

        return fitsNumeric(value)
    }

    private fun fitsNumeric(value: BigDecimal): Boolean {
        val normalized = value.stripTrailingZeros()
        val fractionDigits = normalized.scale().coerceAtLeast(0)
        val integerDigits = (normalized.precision() - normalized.scale()).coerceAtLeast(0)
        return integerDigits <= MAX_INTEGER_DIGITS && fractionDigits <= MAX_FRACTION_DIGITS
    }
}
