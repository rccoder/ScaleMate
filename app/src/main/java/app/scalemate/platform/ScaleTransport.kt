package app.scalemate.platform

import android.content.Context
import app.scalemate.core.ScaleFrame
import app.scalemate.core.ScaleModel

data class ScaleDiscovery(
    val address: String,
    val model: ScaleModel,
    val rssi: Int,
)

/**
 * Android-facing transport boundary. Implementations may scan advertisements,
 * connect over GATT, or provide deterministic test input without BLE hardware.
 */
interface ScaleTransport {
    val requiresBluetoothPermission: Boolean

    fun available(): Boolean
    fun discover(found: (ScaleDiscovery) -> Unit, failure: (Int) -> Unit)
    fun start(
        address: String,
        model: ScaleModel,
        received: (ScaleFrame, Long) -> Unit,
        failure: (Int) -> Unit,
    )

    fun stop()
}

interface ScaleTransportFactory {
    fun create(context: Context): ScaleTransport
}

/** Debug-only hook used by the deterministic transport to select one catalog model. */
interface ConfigurableScaleTransportFactory : ScaleTransportFactory {
    fun selectMockModel(modelId: String?)
}

/**
 * Main code cannot reference a debug-only source set directly. The installer
 * below resolves the mock factory only when explicitly requested by a debug
 * build; release builds simply keep the BLE transport.
 */
object ScaleTransportProvider {
    @Volatile
    private var overrideFactory: ScaleTransportFactory? = null

    fun create(context: Context): ScaleTransport =
        overrideFactory?.create(context) ?: BleTransport(context)

    fun installMockFromIntent(
        context: Context,
        enabled: Boolean,
        mockModelId: String? = null,
    ): Boolean {
        if (!enabled) {
            overrideFactory = null
            return false
        }
        return runCatching {
            val factoryClass = Class.forName("app.scalemate.debug.DebugScaleTransportFactory")
            val instance = factoryClass.getField("INSTANCE").get(null) as ScaleTransportFactory
            (instance as? ConfigurableScaleTransportFactory)?.selectMockModel(mockModelId)
            overrideFactory = instance
            true
        }.getOrDefault(false)
    }
}
