package app.scalemate.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.scalemate.core.ScaleCatalog
import app.scalemate.core.ScaleFrame
import app.scalemate.core.ScaleModel
import app.scalemate.platform.ConfigurableScaleTransportFactory
import app.scalemate.platform.ScaleDiscovery
import app.scalemate.platform.ScaleTransport

/**
 * Debug-only deterministic scale used for emulator UI verification. It never
 * enters release builds and does not claim BLE hardware compatibility.
 */
object DebugScaleTransportFactory : ConfigurableScaleTransportFactory {
    @Volatile
    private var selectedModelId: String? = null

    override fun selectMockModel(modelId: String?) {
        selectedModelId = modelId
    }

    override fun create(context: Context): ScaleTransport =
        MockScaleTransport(ScaleCatalog.forId(selectedModelId) ?: ScaleCatalog.xiaomiWeightScale2)
}

private class MockScaleTransport(
    private val model: ScaleModel,
) : ScaleTransport {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var pending: Runnable? = null
    private val address = mockAddress(model)
    private val weightKg = mockWeight(model)
    private val token = protocolToken(model.id, weightKg)

    override val requiresBluetoothPermission: Boolean = false

    override fun available(): Boolean = true

    override fun discover(found: (ScaleDiscovery) -> Unit, failure: (Int) -> Unit) {
        stop()
        val token = generation
        pending = Runnable {
            if (generation != token) return@Runnable
            Log.d(TAG, "discover model=${model.id} address=$address")
            found(ScaleDiscovery(address, model, -42))
        }.also { handler.postDelayed(it, 350) }
    }

    override fun start(
        address: String,
        model: ScaleModel,
        received: (ScaleFrame, Long) -> Unit,
        failure: (Int) -> Unit,
    ) {
        stop()
        if (!address.equals(this.address, ignoreCase = true) || model.id != this.model.id) {
            failure(-1)
            return
        }
        val token = generation
        val base = android.os.SystemClock.elapsedRealtime()
        handler.postDelayed({
            if (generation == token) received(ScaleFrame(address, 0.0, 1, this.token), base + 200)
        }, 250)
        pending = Runnable {
            if (generation == token) {
                Log.d(TAG, "measure model=${model.id} weightKg=$weightKg")
                received(
                    ScaleFrame(address, weightKg, 2, this.token, final = true),
                    android.os.SystemClock.elapsedRealtime(),
                )
            }
        }.also { handler.postDelayed(it, 900) }
    }

    override fun stop() {
        generation++
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    companion object {
        private const val TAG = "ScaleMateMock"
        private const val MOCK_ADDRESS_PREFIX = "AA:BB:CC:DD:EE:"

        private fun mockAddress(model: ScaleModel): String {
            val index = ScaleCatalog.supported.indexOf(model).coerceAtLeast(0)
            return MOCK_ADDRESS_PREFIX + "%02X".format(index + 1)
        }

        private fun mockWeight(model: ScaleModel): Double {
            val index = ScaleCatalog.supported.indexOf(model).coerceAtLeast(0)
            return 68.0 + index * 0.3
        }

        private fun protocolToken(modelId: String, weightKg: Double): Int {
            var hash = 0x811C9DC5.toInt()
            for (value in listOf(modelId.hashCode(), weightKg.hashCode())) {
                hash = hash xor value
                hash *= 0x01000193
            }
            return hash
        }
    }
}
