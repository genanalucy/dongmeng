package com.verba.interpretation.ui

import java.util.concurrent.atomic.AtomicBoolean

interface RegistrationRequestGate {
    fun tryAcquire(): Boolean
    fun release()
}

class AtomicRegistrationRequestGate : RegistrationRequestGate {
    private val inFlight = AtomicBoolean(false)

    override fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    override fun release() {
        inFlight.set(false)
    }
}
