package app.scalemate.core

/** PICOOC Mini Lite broadcast protocol advertised under the external name PICOOC-L. */
object PicoocMiniLiteBroadcast : ScaleParser {
    const val ADAPTER = "picooc-mini-lite-adv-v1"
    override val adapterId = ADAPTER

    const val completeState = 0x39
    private const val advertisedName = "PICOOC-L"

    override fun parse(advertisement: ScaleAdvertisement): DetectedScaleFrame? {
        if (!advertisement.name.orEmpty().trim().equals(advertisedName, ignoreCase = true)) return null
        val address = canonicalBluetoothAddress(advertisement.address) ?: return null
        val octets = address.split(':').map { it.toInt(16) }
        for ((manufacturerId, payload) in advertisement.manufacturerData) {
            if (payload.size != 14) continue
            if ((manufacturerId and 0xFF) != octets[0] || (manufacturerId ushr 8) != octets[1]) continue
            if (payload.u8(0) != octets[2] || payload.u8(1) != octets[3] ||
                payload.u8(2) != octets[4] || payload.u8(3) != octets[5]) continue
            val full = ByteArray(16)
            full[0] = manufacturerId.toByte()
            full[1] = (manufacturerId ushr 8).toByte()
            payload.copyInto(full, destinationOffset = 2)
            if ((full.sumOf { it.u8() } and 0xFF) != 0xFF) continue
            val state = full.u8(6)
            val kg = full.u16be(8) / 20.0
            if (!validScaleWeight(kg)) continue
            val measurementState = if (state == completeState) 1 else 2
            return DetectedScaleFrame(
                ScaleCatalog.picoocMiniLite,
                ScaleFrame(address, kg, measurementState, protocolToken(state, full.u16be(8))),
            )
        }
        return null
    }
}
