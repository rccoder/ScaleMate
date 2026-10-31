package app.scalemate.core

import java.util.Locale

/**
 * Xiaomi/Meituan standard GATT weight measurement.
 *
 * The scale's name distinguishes the weight-only and body-composition product.
 * ScaleMate only consumes the standard Weight Measurement characteristic, so
 * the saved value is the scale-reported weight and no body composition is inferred.
 */
object XiaomiGattProtocol : ScaleGattProtocol {
    const val ADAPTER = "xiaomi-standard-weight-gatt-v1"
    internal const val SERVICE_WEIGHT = "0000181d-0000-1000-8000-00805f9b34fb"
    internal const val SERVICE_BODY = "0000181b-0000-1000-8000-00805f9b34fb"
    internal const val CHAR_WEIGHT_MEASUREMENT = "00002a9d-0000-1000-8000-00805f9b34fb"
    internal const val CHAR_BODY_MEASUREMENT = "00002a9c-0000-1000-8000-00805f9b34fb"

    override val adapterId: String = ADAPTER

    override fun identify(advertisement: ScaleAdvertisement): ScaleModel? {
        val name = advertisement.name.orEmpty().trim().uppercase(Locale.ROOT)
        val services = advertisement.serviceUuids.mapTo(mutableSetOf()) { it.normalizedUuid() }
        return when {
            name == "MIBCS" ||
                name == "MIBFS" ||
                name.startsWith("MI BODY") ||
                (services.contains(SERVICE_BODY) && name.startsWith("MI")) -> ScaleCatalog.xiaomiBodyScale2
            name == "MI SCALE2" ||
                name.startsWith("MI_SCALE") ||
                services.contains(SERVICE_WEIGHT) -> ScaleCatalog.xiaomiWeightScale2
            else -> null
        }
    }

    override fun create(model: ScaleModel, address: String, profile: GattHandshakeProfile): ScaleGattCodec =
        XiaomiGattCodec(model, address)
}

private class XiaomiGattCodec(
    override val model: ScaleModel,
    private val address: String,
) : ScaleGattCodec {
    override fun onConnected(now: Long): GattUpdate = GattUpdate(
        operations = listOf(
            GattOperation(
                GattOperationKind.NOTIFY,
                XiaomiGattProtocol.SERVICE_WEIGHT,
                XiaomiGattProtocol.CHAR_WEIGHT_MEASUREMENT,
            ),
        ),
    )

    override fun onNotification(characteristicUuid: String, data: ByteArray, now: Long): GattUpdate {
        if (!characteristicUuid.equals(XiaomiGattProtocol.CHAR_WEIGHT_MEASUREMENT, ignoreCase = true)) {
            return GattUpdate()
        }
        val kg = XiaomiWeightMeasurement.parseKg(data) ?: return GattUpdate()
        return GattUpdate(
            frame = ScaleFrame(
                identity = address,
                kg = kg,
                state = 2,
                token = protocolToken(data.contentHashCode(), kg.hashCode()),
                final = true,
            ),
        )
    }
}

internal object XiaomiWeightMeasurement {
    /** Bluetooth SIG Weight Measurement: flags, uint16 value, optional timestamp/user. */
    fun parseKg(data: ByteArray): Double? {
        if (data.size < 3) return null
        val flags = data.u8(0)
        val raw = u16le(data, 1)
        if (raw == 0) return null

        val imperial = flags and 0x01 != 0
        val highResolution = flags and 0x02 != 0
        val resolution = when {
            imperial && highResolution -> 0.02
            imperial -> 0.01
            highResolution -> 0.01
            else -> 0.005
        }
        val value = raw * resolution
        val kg = if (imperial) value * 0.45359237 else value
        return kg.takeIf(::validScaleWeight)
    }
}
