package app.scalemate

import org.junit.Assert.*
import org.junit.Test

class ScaleListeningPolicyTest {
    @Test fun defaultRecordsPageAllowsAutomaticWeighing() {
        assertFalse(pausesScaleListening("records"))
    }

    @Test fun returningFromFoodToRecordsResumesListening() {
        val paused = listOf("records", "food", "food-services", "food", "records", "home", "settings")
            .map(::pausesScaleListening)
        assertEquals(listOf(false, true, true, true, false, false, false), paused)
    }
}
