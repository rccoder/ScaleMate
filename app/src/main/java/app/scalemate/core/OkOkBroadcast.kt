package app.scalemate.core

/** OKOK V11, V20 and VF0 broadcast scales expose weight-only frames without GATT. */
object OkOkBroadcast : ScaleParser {
    const val ADAPTER = "okok-adv-v1"
    override val adapterId = ADAPTER

    private const val v20 = 0x20CA
    private const val v11 = 0x11CA
    private const val vf0 = 0xF0FF

    override fun parse(advertisement: ScaleAdvertisement): DetectedScaleFrame? {
        if (!advertisement.name.orEmpty().isKnownOkOkName()) return null
        val address = canonicalBluetoothAddress(advertisement.address) ?: return null
        advertisement.manufacturerData[v20]?.let { payload ->
            parseV20(payload)?.let { return detected(address, ScaleCatalog.okokV20, it) }
        }
        advertisement.manufacturerData[v11]?.let { payload ->
            parseV11(payload)?.let { return detected(address, ScaleCatalog.okokV11, it) }
        }
        advertisement.manufacturerData[vf0]?.let { payload ->
            parseVf0(payload)?.let { return detected(address, ScaleCatalog.okokVf0, it) }
        }
        return null
    }

    private data class ParsedScale(val kg: Double, val token: Int, val state: Int)

    private fun detected(address: String, model: ScaleModel, parsed: ParsedScale) =
        DetectedScaleFrame(model, ScaleFrame(address, parsed.kg, parsed.state, parsed.token))

    private fun parseV20(data: ByteArray): ParsedScale? {
        if (data.size != 19) return null
        var checksum = 0x20
        for (index in 0..11) checksum = checksum xor data.u8(index)
        if ((checksum and 0xFF) != data.u8(12)) return null
        val flags = data.u8(6)
        val rawWeight = data.u16be(8)
        val kg = rawWeight / if ((flags and 0x04) != 0) 100.0 else 10.0
        if (!validScaleWeight(kg)) return null
        val state = if ((flags and 0x01) != 0) 1 else 2
        return ParsedScale(kg, protocolToken(flags, rawWeight), state)
    }

    private fun parseV11(data: ByteArray): ParsedScale? {
        if (data.size != 23) return null
        var checksum = 0xCA xor 0x11
        for (index in 0..15) checksum = checksum xor data.u8(index)
        if ((checksum and 0xFF) != data.u8(16)) return null
        val properties = data.u8(9)
        val resolution = when ((properties shr 1) and 0x03) {
            0 -> 10.0
            1 -> 1.0
            2 -> 100.0
            else -> 10.0
        }
        val raw = data.u16be(3)
        val kg = when ((properties shr 3) and 0x03) {
            0 -> raw / resolution
            1 -> raw / resolution / 2.0
            2 -> raw / resolution / 2.204623
            3 -> {
                val stones = raw ushr 8
                val pounds = (raw and 0xFF) / resolution
                stones * 6.350293 + pounds * 0.453592
            }
            else -> return null
        }
        if (!validScaleWeight(kg)) return null
        return ParsedScale(kg, protocolToken(properties, raw), 1)
    }

    private fun parseVf0(data: ByteArray): ParsedScale? {
        if (data.size < 4) return null
        val raw = data.u8(2) or (data.u8(3) shl 8)
        val kg = raw / 10.0
        if (!validScaleWeight(kg)) return null
        return ParsedScale(kg, raw, 1)
    }

    private fun String.isKnownOkOkName(): Boolean {
        val normalized = trim()
        return normalized.equals("ADV", ignoreCase = true) ||
            normalized.equals("Chipsea-BLE", ignoreCase = true) ||
            normalized.startsWith("Yoda0", ignoreCase = true) ||
            normalized.startsWith("Yoda1", ignoreCase = true)
    }
}
