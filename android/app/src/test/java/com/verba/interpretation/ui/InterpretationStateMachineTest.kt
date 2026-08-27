package com.verba.interpretation.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InterpretationStateMachineTest {
    @Test fun supportsStartPauseResumeFinish() {
        val reduce = InterpretationStateMachine::reduce
        assertEquals(SessionPhase.STARTING, reduce(SessionPhase.IDLE, SessionAction.Start))
        assertEquals(SessionPhase.RUNNING, reduce(SessionPhase.STARTING, SessionAction.Ready))
        assertEquals(SessionPhase.PAUSED, reduce(SessionPhase.RUNNING, SessionAction.Pause))
        assertEquals(SessionPhase.STARTING, reduce(SessionPhase.PAUSED, SessionAction.Resume))
        assertEquals(SessionPhase.STOPPING, reduce(SessionPhase.RUNNING, SessionAction.Finish))
        assertEquals(SessionPhase.STOPPING, reduce(SessionPhase.STOPPING, SessionAction.Ready))
        assertEquals(SessionPhase.IDLE, reduce(SessionPhase.STOPPING, SessionAction.Drained))
    }

    @Test fun invalidTransitionsAreIgnoredAndErrorResets() {
        assertEquals(SessionPhase.IDLE, InterpretationStateMachine.reduce(SessionPhase.IDLE, SessionAction.Pause))
        assertEquals(SessionPhase.ERROR, InterpretationStateMachine.reduce(SessionPhase.RUNNING, SessionAction.Fail))
        assertEquals(SessionPhase.IDLE, InterpretationStateMachine.reduce(SessionPhase.ERROR, SessionAction.Reset))
    }
}
