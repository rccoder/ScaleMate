package app.scalemate

import app.scalemate.core.Phase
import app.scalemate.core.SessionState
import app.scalemate.data.Measurement
import org.junit.Assert.*
import org.junit.Test

class SavedWeightFeedbackTest {
    @Test fun feedbackUsesTheConfirmedRecordEvenWhenAnotherRecordIsNewer() {
        val saved=Measurement("saved","scale","home",70.0,1000,0,status="permission")
        val newer=saved.copy(id="newer",time=2000,status="synced")
        val state=AppState(records=listOf(newer,saved),session=SessionState(Phase.CONFIRMED),lastSavedRecordId=saved.id)
        assertEquals("permission",state.confirmedMeasurement()?.status)
        val synced=state.copy(records=listOf(newer,saved.copy(status="synced")))
        assertEquals("synced",synced.confirmedMeasurement()?.status)
        assertNull(state.copy(lastSavedRecordId="missing").confirmedMeasurement())
        assertNull(state.copy(session=SessionState(Phase.MEASURING)).confirmedMeasurement())
    }
}
