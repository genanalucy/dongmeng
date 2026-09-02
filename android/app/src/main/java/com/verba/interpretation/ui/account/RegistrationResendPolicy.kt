package com.verba.interpretation.ui.account

object RegistrationResendPolicy {
    fun remainingSeconds(deadlineMillis: Long, nowMillis: Long): Int =
        ((deadlineMillis - nowMillis).coerceAtLeast(0) + 999L).div(1_000L).toInt()

    fun submitWhenReady(deadlineMillis: Long, nowMillis: Long, request: () -> Unit): Boolean {
        if (remainingSeconds(deadlineMillis, nowMillis) != 0) return false
        request()
        return true
    }
}
