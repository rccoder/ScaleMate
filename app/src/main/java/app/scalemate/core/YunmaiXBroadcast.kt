package app.scalemate.core

/**
 * Yunmai X and the Chinese Haooqing Mini 2S broadcast weight in manufacturer data.
 * Android exposes the manufacturer value after removing its two-byte company identifier.
 */
object YunmaiXBroadcast : ScaleParser {
    const val ADAPTER = "yunmai-x-adv-v1"
    override val adapterId = ADAPTER

    private val internationalSignature = byteArrayOf(0x0B, 0x74, 0x17)
    private val chineseSignature = byteArrayOf(0x06, 0x52, 0x11)

    override fun parse(advertisement: ScaleAdvertisement): DetectedScaleFrame? {
        val address = canonicalBluetoothAddress(advertisement.address) ?: return null
        val octets = address.split(':').map { it.toInt(16) }
        for ((manufacturerId, payload) in advertisement.manufacturerData) {
            if (payload.size != 14) continue
            val signature = payload.copyOfRange(4, 7)
            val model = when {
                signature.contentEquals(internationalSignature) -> ScaleCatalog.yunmaiX
                signature.contentEquals(chineseSignature) -> ScaleCatalog.yunmaiMini2S
                else -> continue
            }
            if (manufacturerId != (octets[4] shl 8 or octets[5])) continue
            if (!payload.macEchoMatches(octets)) continue
            var checksum = 0
            for (index in 7..12) checksum = checksum xor payload.u8(index)
            if (checksum != payload.u8(13)) continue
            val state = payload.u8(8)
            if (state !in 1..3) continue
            val kg = payload.u16be(9) / 100.0
            if (!validScaleWeight(kg)) continue
            // Only the final frame starts a session. Earlier frames still carry a zero
            // weight so stepping off can reset continuous listening.
            val measurementState = if (state == 3) 1 else 2
            return DetectedScaleFrame(model, ScaleFrame(address, kg, measurementState, payload.u8(7)))
        }
        return null
    }

    private fun ByteArray.macEchoMatches(octets: List<Int>): Boolean =
        u8(0) == octets[3] && u8(1) == octets[2] && u8(2) == octets[1] && u8(3) == octets[0]
}
