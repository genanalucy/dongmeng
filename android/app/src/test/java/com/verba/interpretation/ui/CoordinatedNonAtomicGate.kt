package com.verba.interpretation.ui

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

internal class CoordinatedNonAtomicGate : RegistrationRequestGate {
    private var acquired = false
    private var coordinate = false
    private val race = CyclicBarrier(2)

    fun coordinateNextAcquisitions() {
        coordinate = true
    }

    override fun tryAcquire(): Boolean {
        if (acquired) return false
        if (coordinate) race.await(5, TimeUnit.SECONDS)
        acquired = true
        return true
    }

    override fun release() {
        acquired = false
    }
}
