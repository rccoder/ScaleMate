package app.scalemate.core

import org.junit.Assert.*
import org.junit.Test

class WeighingSessionTest {
    private fun frame(kg: Double=71.3,state: Int=1,token: Int=29,address: String="A")=ScaleFrame(address,kg,state,token)
    private fun final(kg: Double=71.8,token: Int=31,address: String="A")=ScaleFrame(address,kg,2,token,true)
    private fun stable(s: WeighingSession){for(t in 0L..3000L step 1000)s.frame(frame(),t,100000+t)}
    @Test fun needsFullWindow(){val s=WeighingSession("A",true);for(t in 0L..2000L step 1000)s.frame(frame(),t,t);assertEquals(Phase.MEASURING,s.state.phase);s.frame(frame(),3000,3000);assertEquals(Phase.COUNTDOWN,s.state.phase)}
    @Test fun countdownUsesElapsedTime(){val s=WeighingSession("A",true);stable(s);assertFalse(s.tick(7999));assertTrue(s.tick(8000));assertFalse(s.tick(9000));assertEquals(Phase.CONFIRMED,s.state.phase)}
    @Test fun repeatedStableDoesNotRestart(){val s=WeighingSession("A",true);stable(s);s.frame(frame(state=2),5000,105000);assertTrue(s.tick(8000));assertEquals(103000L,s.state.candidateAt)}
    @Test fun cancelSuppressesRepeats(){val s=WeighingSession("A",true);stable(s);s.cancel();for(t in 4000L..30000L step 1000)s.frame(frame(),t,t);assertFalse(s.tick(31000));assertEquals(Phase.CANCELLED,s.state.phase)}
    @Test fun driftRestartsWindow(){val s=WeighingSession("A",true);stable(s);s.frame(frame(72.0),4000,4000);assertEquals(Phase.MEASURING,s.state.phase);assertFalse(s.tick(8000))}
    @Test fun rejectsDifferentScale(){val s=WeighingSession("A",true);for(t in 0L..10000L step 1000)s.frame(frame(address="B"),t,t);assertEquals(Phase.WAITING,s.state.phase)}
    @Test fun duplicateTimestampNotSamples(){val s=WeighingSession("A",true);repeat(20){s.frame(frame(),1000,1000)};assertEquals(Phase.MEASURING,s.state.phase);assertFalse(s.tick(6000))}
    @Test fun zeroResetsCandidate(){val s=WeighingSession("A",true);stable(s);s.frame(frame(0.0),4000,4000);assertEquals(Phase.WAITING,s.state.phase);assertFalse(s.tick(8000))}
    @Test fun unverifiedStateFlagDoesNotConfirm(){val s=WeighingSession("A",true);for(t in 0L..5000L step 1000)s.frame(frame(state=3),t,t);assertEquals(Phase.WAITING,s.state.phase)}
    @Test fun oldTokenNeedsReset(){val s=WeighingSession("A",true,29);stable(s);assertEquals(Phase.WAITING,s.state.phase);s.frame(frame(0.0),4000,4000);for(t in 5000L..8000L step 1000)s.frame(frame(),t,t);assertEquals(Phase.COUNTDOWN,s.state.phase)}
    @Test fun newTokenCanStart(){val s=WeighingSession("A",true,28);stable(s);assertEquals(Phase.COUNTDOWN,s.state.phase)}
    @Test fun staleCannotSubmit(){val s=WeighingSession("A",true);stable(s);assertFalse(s.tick(14000));assertEquals(Phase.EXPIRED,s.state.phase)}
    @Test fun manualConfirmOnlyAfterStable(){val s=WeighingSession("A",false);assertFalse(s.confirm(0));stable(s);assertEquals(Phase.STABLE,s.state.phase);assertFalse(s.tick(8000));assertTrue(s.confirm(8000));assertFalse(s.confirm(8001))}
    @Test fun staleManualRejected(){val s=WeighingSession("A",false);stable(s);assertFalse(s.confirm(14000))}
    @Test fun gapsDoNotImplyContinuousStability(){val s=WeighingSession("A",true);s.frame(frame(),0,0);s.frame(frame(),3000,3000);s.frame(frame(),4000,4000);assertEquals(Phase.MEASURING,s.state.phase)}
    @Test fun changingTokenResetsCandidate(){val s=WeighingSession("A",true);stable(s);s.frame(frame(token=30),4000,4000);assertEquals(Phase.MEASURING,s.state.phase);assertFalse(s.tick(8000))}
    @Test fun continuousListeningDoesNotSaveCancelledMeasurementAgain() {
        val first=WeighingSession("A",true)
        stable(first)
        val token=first.state.token
        first.cancel()
        val waiting=WeighingSession("A",true,token)
        for(t in 4000L..14000L step 1000) {
            waiting.frame(frame(),t,t)
            assertFalse(waiting.tick(t))
        }
        assertEquals(Phase.WAITING,waiting.state.phase)
        waiting.frame(frame(0.0),15000,15000)
        for(t in 16000L..19000L step 1000)waiting.frame(frame(),t,t)
        assertEquals(Phase.COUNTDOWN,waiting.state.phase)
        assertTrue(waiting.tick(24000))
    }
    @Test fun continuousListeningRequiresNewMeasurementAfterSaving() {
        val first=WeighingSession("A",true)
        stable(first)
        assertTrue(first.tick(8000))
        val waiting=WeighingSession("A",true,first.state.token)
        for(t in 9000L..20000L step 1000) {
            waiting.frame(frame(state=2),t,t)
            assertFalse(waiting.tick(t))
        }
        assertEquals(Phase.WAITING,waiting.state.phase)
        for(t in 21000L..24000L step 1000)waiting.frame(frame(token=30),t,t)
        assertFalse(waiting.tick(28999))
        assertTrue(waiting.tick(29000))
        assertFalse(waiting.tick(30000))
    }
    @Test fun completedSetupTrialIsNotReusedForAutomaticSaving() {
        val trial=WeighingSession("A",false)
        stable(trial)
        assertEquals(Phase.STABLE,trial.state.phase)
        assertFalse(trial.tick(8000))
        assertTrue(trial.confirm(8000))
        val daily=WeighingSession("A",true,trial.state.token)
        for(t in 9000L..20000L step 1000) {
            daily.frame(frame(),t,t)
            assertFalse(daily.tick(t))
        }
        assertEquals(Phase.WAITING,daily.state.phase)
        for(t in 21000L..24000L step 1000)daily.frame(frame(token=30),t,t)
        assertEquals(Phase.COUNTDOWN,daily.state.phase)
        assertFalse(daily.tick(28999))
        assertTrue(daily.tick(29000))
    }
    @Test fun finalGattFrameCanEnterManualStableStateWithoutThreeSamples() {
        val session=WeighingSession("A",false)
        session.frame(final(),1000,101000)
        assertEquals(Phase.STABLE,session.state.phase)
        assertEquals(71.8,session.state.candidateKg!!,0.0)
        assertEquals(101000L,session.state.candidateAt)
        assertTrue(session.confirm(2000))
    }
    @Test fun finalGattFrameStartsAutomaticCountdownImmediately() {
        val session=WeighingSession("A",true)
        session.frame(final(),1000,101000)
        assertEquals(Phase.COUNTDOWN,session.state.phase)
        assertFalse(session.tick(5999))
        assertTrue(session.tick(6000))
    }
    @Test fun duplicateFinalGattFrameDoesNotRestartCountdown() {
        val session=WeighingSession("A",true)
        session.frame(final(),1000,101000)
        session.frame(final(),2000,102000)
        assertFalse(session.tick(5999))
        assertTrue(session.tick(6000))
        assertEquals(101000L,session.state.candidateAt)
    }
    @Test fun previousGattTokenNeedsZeroOrChangedTokenBeforeReuse() {
        val session=WeighingSession("A",true,31)
        session.frame(final(),1000,101000)
        assertEquals(Phase.WAITING,session.state.phase)
        session.frame(final(kg=0.0),2000,102000)
        session.frame(final(),3000,103000)
        assertEquals(Phase.COUNTDOWN,session.state.phase)
    }
}
