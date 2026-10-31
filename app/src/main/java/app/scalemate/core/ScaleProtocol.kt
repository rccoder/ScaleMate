package app.scalemate.core

/**
 * [state] is normalized for the shared session layer: 1 starts a new measurement,
 * 2 is an in-progress frame, and a zero weight always resets continuous listening.
 * [final] is only set when a GATT protocol explicitly marks the reading as complete.
 */
data class ScaleFrame(
    val identity: String,
    val kg: Double,
    val state: Int,
    val token: Int,
    val final: Boolean = false,
)

/** Android-free snapshot of the advertisement fields needed by scale parsers. */
data class ScaleAdvertisement(
    val address: String,
    val name: String?,
    val manufacturerData: Map<Int, ByteArray>,
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val serviceUuids: Set<String> = emptySet(),
)

data class DetectedScaleFrame(val model: ScaleModel, val frame: ScaleFrame)

interface ScaleParser {
    val adapterId: String
    fun parse(advertisement: ScaleAdvertisement): DetectedScaleFrame?
}

/** Ordered parser registry. More specific wire signatures must precede generic families. */
object ScaleProtocols {
    private val parsers: List<ScaleParser> = listOf(
        YunmaiM263Parser,
        YunmaiXBroadcast,
        PicoocMiniLiteBroadcast,
        EtekcityFit8SBroadcast,
        FitIndexFt26rBroadcast,
        OkOkBroadcast,
    )

    fun parse(advertisement: ScaleAdvertisement, expectedAdapterId: String? = null): DetectedScaleFrame? {
        for (parser in parsers) {
            if (expectedAdapterId != null && parser.adapterId != expectedAdapterId) continue
            val result = runCatching { parser.parse(advertisement) }.getOrNull() ?: continue
            if (expectedAdapterId == null || result.model.adapterId == expectedAdapterId) return result
        }
        return null
    }

    /**
     * Identify GATT-only devices from an advertisement. Unlike [parse], this does not
     * require a measurement frame; it is used to make connection-oriented scales
     * discoverable before they are awake.
     */
    fun identify(advertisement: ScaleAdvertisement, expectedAdapterId: String? = null): ScaleModel? {
        parse(advertisement, expectedAdapterId)?.let { return it.model }
        for (protocol in ScaleGattProtocols.all) {
            if (expectedAdapterId != null && protocol.adapterId != expectedAdapterId) continue
            runCatching { protocol.identify(advertisement) }.getOrNull()?.let { return it }
        }
        return null
    }

    fun gattFor(adapterId: String): ScaleGattProtocol? =
        ScaleGattProtocols.all.find { it.adapterId == adapterId }
}

internal const val MAX_SCALE_KG = 300.0

internal fun canonicalBluetoothAddress(address: String): String? {
    val octets = address.split(':').map { it.toIntOrNull(16) ?: return null }
    if (octets.size != 6 || octets.any { it !in 0..255 }) return null
    return octets.joinToString(":") { "%02X".format(it) }
}

internal fun Byte.u8(): Int = toInt() and 0xFF
internal fun ByteArray.u8(index: Int): Int = this[index].u8()
internal fun ByteArray.u16be(index: Int): Int = (u8(index) shl 8) or u8(index + 1)
internal fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)
internal fun ByteArray.u24le(index: Int): Int = u8(index) or (u8(index + 1) shl 8) or (u8(index + 2) shl 16)
internal fun validScaleWeight(kg: Double): Boolean = kg.isFinite() && kg in 0.0..MAX_SCALE_KG

internal fun protocolToken(vararg values: Int): Int {
    var hash = 0x811C9DC5.toInt()
    for (value in values) {
        hash = hash xor value
        hash *= 0x01000193
    }
    return hash
}
