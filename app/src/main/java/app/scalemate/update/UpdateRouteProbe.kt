package app.scalemate.update

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

const val UPDATE_PROBE_BYTES = 64 * 1024

/** Probe results describe one small read, not sustained download throughput. */
data class RouteProbeResult(
    val sourceId: String,
    val checkedAt: Long,
    val bytesRead: Int = 0,
    val elapsedMillis: Long = 0,
    val firstByteMillis: Long = 0,
    val failed: Boolean = false
) {
    val kibPerSecond: Double get() = bytesRead * 1000.0 / elapsedMillis.coerceAtLeast(1) / 1024.0
}

class UpdateRouteProbe(
    private val connect: (String) -> HttpURLConnection = {
        URI(it).toURL().openConnection() as HttpURLConnection
    },
    private val nanoTime: () -> Long = System::nanoTime
) {
    fun measure(source: UpdateSource, tag: String, isCancelled: () -> Boolean): RouteProbeResult {
        require(channelFromTag(tag) != null) { "Invalid probe tag" }
        if (isCancelled()) throw InterruptedException("Probe cancelled")
        val connection = connect(UpdateSources.assetUrl(source, tag))
        val started = nanoTime()
        var input: InputStream? = null
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.setRequestProperty("Range", "bytes=0-${UPDATE_PROBE_BYTES - 1}")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "ScaleMate-Android-Route-Probe/1")
            require(connection.responseCode in listOf(200, 206)) { "Probe HTTP failure" }
            input = connection.inputStream
            val sample = ByteArray(UPDATE_PROBE_BYTES)
            var count = 0
            var firstByte = 0L
            while (count < sample.size) {
                if (isCancelled()) throw InterruptedException("Probe cancelled")
                if (elapsed(started) >= 8_000) throw java.net.SocketTimeoutException("Probe timed out")
                // Never consume a full APK, including servers that ignore Range.
                val read = input.read(sample, count, minOf(8 * 1024, sample.size - count))
                if (read < 0) break
                if (count == 0 && read > 0) firstByte = elapsed(started)
                count += read
            }
            val elapsed = elapsed(started)
            require(count == UPDATE_PROBE_BYTES) { "Insufficient probe bytes" }
            require(sample.take(4) == listOf(0x50.toByte(), 0x4b.toByte(), 3.toByte(), 4.toByte())) {
                "Probe did not return an APK header"
            }
            if (elapsed >= 8_000) throw java.net.SocketTimeoutException("Probe timed out")
            return RouteProbeResult(source.id, System.currentTimeMillis(), count,
                elapsed.coerceAtLeast(1), firstByte)
        } finally {
            // Disconnect first so closing an ignored Range response cannot drain
            // the remaining APK to reuse the underlying connection.
            connection.disconnect()
            runCatching { input?.close() }
        }
    }

    private fun elapsed(started: Long): Long = (nanoTime() - started) / 1_000_000
}

fun fastestProbe(results: Collection<RouteProbeResult>): String? = results
    .filter { !it.failed && it.bytesRead == UPDATE_PROBE_BYTES }
    .minByOrNull { it.elapsedMillis }?.sourceId
