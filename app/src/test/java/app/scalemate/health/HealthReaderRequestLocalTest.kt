package app.scalemate.health

import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.datatypes.StepsRecord
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class HealthReaderRequestLocalTest {
    @Test fun subsequentPagesKeepTheTokenWithoutSettingSortOrder() {
        val end=Instant.parse("2026-10-26T00:00:00Z")
        assertTrue(healthPageRequest(StepsRecord::class.java,null,end).isAscending)
        assertEquals(42L,healthPageRequest(StepsRecord::class.java,null,end,42L).pageToken)
        try {
            ReadRecordsRequestUsingFilters.Builder(StepsRecord::class.java).setAscending(true).setPageToken(42L).build()
            fail("The old request must fail on the real Android builder")
        }catch(_:IllegalStateException){}
    }
}
