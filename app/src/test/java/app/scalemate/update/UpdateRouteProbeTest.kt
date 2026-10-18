package app.scalemate.update

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class UpdateRouteProbeTest {
    private class Connection(private val status: Int, val payload: ByteArray) : HttpURLConnection(URL("https://github.com")) {
        val stream = ByteArrayInputStream(payload)
        var disconnected = false
        override fun getResponseCode() = status
        override fun getInputStream() = stream
        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy() = false
    }
    private fun apk(size: Int) = ByteArray(size).also {
        it[0] = 0x50; it[1] = 0x4b; it[2] = 3; it[3] = 4
    }

    @Test fun serverIgnoringRangeStillCannotConsumeFullApk() {
        val connection = Connection(200, apk(UPDATE_PROBE_BYTES * 4))
        var ticks = 0L
        val result = UpdateRouteProbe({ connection }, { ticks++ * 1_000_000 })
            .measure(UpdateSources.GITHUB_OFFICIAL, "v0.2.1") { false }
        assertEquals(UPDATE_PROBE_BYTES, result.bytesRead)
        assertEquals(UPDATE_PROBE_BYTES * 3, connection.stream.available())
        assertEquals("bytes=0-65535", connection.getRequestProperty("Range"))
        assertTrue(connection.disconnected)
    }

    @Test fun htmlShortResponsesAndHttpFailuresAreNotRecommendations() {
        for (connection in listOf(
            Connection(206, ByteArray(UPDATE_PROBE_BYTES) { '<'.code.toByte() }),
            Connection(206, apk(1024)),
            Connection(403, apk(UPDATE_PROBE_BYTES))
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                UpdateRouteProbe({ connection }).measure(UpdateSources.GITHUB_OFFICIAL, "v0.2.1") { false }
            }
            assertTrue(connection.disconnected)
        }
        assertNull(fastestProbe(listOf(RouteProbeResult("bad", 0, failed = true))))
    }

    @Test fun cancelAndDeadlineStopReading() {
        val cancelled = Connection(206, apk(UPDATE_PROBE_BYTES))
        var checks = 0
        assertThrows(InterruptedException::class.java) {
            UpdateRouteProbe({ cancelled }).measure(UpdateSources.GITHUB_OFFICIAL, "v0.2.1") { ++checks > 1 }
        }
        assertEquals(UPDATE_PROBE_BYTES, cancelled.stream.available())
        assertTrue(cancelled.disconnected)
        val timedOut = Connection(206, apk(UPDATE_PROBE_BYTES))
        var ticks = 0L
        assertThrows(java.net.SocketTimeoutException::class.java) {
            UpdateRouteProbe({ timedOut }, { ticks++ * 9_000_000_000 })
                .measure(UpdateSources.GITHUB_OFFICIAL, "v0.2.1") { false }
        }
        assertEquals(UPDATE_PROBE_BYTES, timedOut.stream.available())
        assertTrue(timedOut.disconnected)
    }

    @Test fun fastestIgnoresFailuresAndInsufficientSamples() {
        assertEquals("fast", fastestProbe(listOf(
            RouteProbeResult("slow", 0, UPDATE_PROBE_BYTES, 1000),
            RouteProbeResult("fast", 0, UPDATE_PROBE_BYTES, 100),
            RouteProbeResult("short", 0, 1024, 1),
            RouteProbeResult("failed", 0, failed = true)
        )))
    }
}
