package app.scalemate.core

/**
 * FITINDEX FT-26R-W uses the QN/Renpho AABB broadcast frame. The device exposes
 * weight only, so this parser intentionally accepts no body-composition fields.
 */
object FitIndexFt26rBroadcast : ScaleParser {
    const val ADAPTER = "fitindex-ft-26r-w-adv-v1"
    override val adapterId = ADAPTER

    private const val companyId = 0xFFFF
    private val ft26rSignature = byteArrayOf(0x51, 0x0E, 0x03)

    override fun parse(advertisement: ScaleAdvertisement): DetectedScaleFrame? {
        val payload = advertisement.manufacturerData[companyId] ?: return null
        if (payload.size < 22 || payload.u8(0) != 0xAA || payload.u8(1) != 0xBB) return null
        val address = canonicalBluetoothAddress(advertisement.address) ?: return null
        if (!payload.hasSignature(19, ft26rSignature)) return null
        val rawWeight = payload.u16le(17)
        val kg = rawWeight / 100.0
        if (!validScaleWeight(kg)) return null
        val status = payload.u8(15)
        val state = if ((status and 0x01) != 0) 1 else 2
        return DetectedScaleFrame(
            ScaleCatalog.fitIndexFt26rW,
            ScaleFrame(address, kg, state, protocolToken(rawWeight)),
        )
    }

    private fun ByteArray.hasSignature(offset: Int, signature: ByteArray): Boolean =
        size >= offset + signature.size && signature.indices.all { this[offset + it] == signature[it] }
}
