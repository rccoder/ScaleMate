package app.scalemate.health

import app.scalemate.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Test

class HealthFeatureGateTest {
    @Test fun healthAiStaysOffUntilProductEnablesIt() {
        assertFalse(BuildConfig.HEALTH_AI_ENABLED)
    }
}
