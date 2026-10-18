package app.scalemate

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.update.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateRouteProbeDeviceTest {
    @Test fun smallSamplesOnRealNetwork() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probePublicRoutes") == "true")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(UpdateSources.all.map { source -> Callable {
                val result = runCatching {
                    UpdateRouteProbe().measure(source, "v0.2.1") { Thread.currentThread().isInterrupted }
                }.getOrElse { RouteProbeResult(source.id, System.currentTimeMillis(), failed = true) }
                val message = "${source.id}: bytes=${result.bytesRead}, ms=${result.elapsedMillis}, failed=${result.failed}\n"
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", message) })
                result
            } }).map { it.get() }
            assertTrue("At least one route must serve the public release", results.any { !it.failed })
            assertTrue(results.all { it.failed || it.bytesRead == UPDATE_PROBE_BYTES })
        } finally {
            executor.shutdownNow()
        }
    }
}
