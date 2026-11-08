package app.scalemate.core

import java.util.Locale

/**
 * Xiaomi Mi Smart Scale broadcasts 10 bytes under Weight Scale service 0x181D.
 * The first and second generation share the same wire format.
 */
object XiaomiWeightScaleBroadcast : ScaleParser {
    const val ADAPTER = "xiaomi-mi-smart-scale-adv-v1"
    override val adapterId = ADAPTER

    internal const val SERVICE_WEIGHT = "0000181d-0000-1000-8000-00805f9b34fb"
    internal const val FRAME_SIZE = 10

    override fun parse(advertisement: ScaleAdvertisement): DetectedScaleFrame? {
        val address = canonicalBluetoothAddress(advertisement.address) ?: return null
        val payload = advertisement.xiaomiServiceData(SERVICE_WEIGHT) ?: return null
        if (payload.size != FRAME_SIZE) return null

        val flags = payload.u8(0)
        val stable = flags and 0x20 != 0
        val removed = flags and 0x80 != 0
        val raw = payload.u16le(1)
        val kg = when {
            flags and 0x10 != 0 -> raw / 200.0
            flags and 0x01 != 0 -> raw / 100.0 * 0.45359237
            flags and 0x02 != 0 -> raw / 200.0
            else -> return null
        }
        if (!validScaleWeight(kg)) return null

        val measuredKg = if (removed) 0.0 else kg
        val state = if (stable && !removed && measuredKg > 0.0) 1 else 2
        return DetectedScaleFrame(
            ScaleCatalog.xiaomiWeightScale2,
            ScaleFrame(
                identity = address,
                kg = measuredKg,
                state = state,
                token = protocolToken(1, flags, raw),
            ),
        )
    }
}

/**
 * Xiaomi Mi Body Composition Scale broadcasts 13 bytes under Body Composition
 * service 0x181B. ScaleMate reads the reported weight and intentionally ignores
 * impedance/body-composition fields.
 */
object XiaomiBodyScaleBroadcast : ScaleParser {
    const val ADAPTER = "xiaomi-mi-body-composition-scale-adv-v2"
    override val adapterId = ADAPTER

    internal const val SERVICE_BODY = "0000181b-0000-1000-8000-00805f9b34fb"
    internal const val FRAME_SIZE = 13

    override fun parse(advertisement: ScaleAdvertisement): DetectedScaleFrame? {
        val address = canonicalBluetoothAddress(advertisement.address) ?: return null
        val payload = advertisement.xiaomiServiceData(SERVICE_BODY) ?: return null
        if (payload.size != FRAME_SIZE) return null

        val flags = payload.u8(1)
        val stable = flags and 0x20 != 0
        val removed = flags and 0x80 != 0
        val raw = payload.u16le(11)
        val kg = if (payload.u8(0) and 0x01 != 0) {
            raw / 100.0 * 0.45359237
        } else {
            raw / 200.0
        }
        if (!validScaleWeight(kg)) return null

        val measuredKg = if (removed) 0.0 else kg
        val state = if (stable && !removed && measuredKg > 0.0) 1 else 2
        return DetectedScaleFrame(
            ScaleCatalog.xiaomiBodyScale2,
            ScaleFrame(
                identity = address,
                kg = measuredKg,
                state = state,
                token = protocolToken(2, payload.u8(0), flags, raw),
            ),
        )
    }
}

private fun ScaleAdvertisement.xiaomiServiceData(uuid: String): ByteArray? {
    val expected = uuid.lowercase(Locale.ROOT)
    val short = expected.removePrefix("0000").substringBefore('-')
    return serviceData.entries.firstOrNull { (key, _) ->
        val normalized = key.lowercase(Locale.ROOT)
        normalized == expected ||
            normalized == "0000$short" ||
            normalized == short
    }?.value
}

