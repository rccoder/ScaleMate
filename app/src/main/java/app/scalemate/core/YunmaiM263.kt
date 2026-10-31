package app.scalemate.core

/** M263 signature captured locally. Manufacturer ID contains the first two reversed MAC bytes. */
object YunmaiM263 {
    const val ADAPTER = "yunmai-m263-v1"
    fun parse(address: String, manufacturerId: Int, p: ByteArray?): ScaleFrame? {
        if (p == null || p.size != 14) return null
        val mac = address.split(":").map { it.toIntOrNull(16) ?: return null }
        if (mac.size != 6 || mac.any { it !in 0..255 }) return null
        if (manufacturerId != (mac[4] shl 8 or mac[5])) return null
        for (i in 0..3) if ((p[i].toInt() and 255) != mac[3-i]) return null
        if (p[4] != 8.toByte() || p[5] != 0x52.toByte() || p[6] != 0x16.toByte()) return null
        var xor = 0
        for (i in 7..12) xor = xor xor (p[i].toInt() and 255)
        if (xor != (p[13].toInt() and 255)) return null
        val state = p[8].toInt() and 255
        if (state !in 1..3) return null
        val kg = ((p[9].toInt() and 255) shl 8 or (p[10].toInt() and 255)) / 100.0
        if (kg !in 0.0..300.0) return null
        return ScaleFrame(mac.joinToString(":") { "%02X".format(it) }, kg, state, p[7].toInt() and 255)
    }
}

object YunmaiM263Parser : ScaleParser {
    override val adapterId: String = YunmaiM263.ADAPTER

    override fun parse(advertisement: ScaleAdvertisement): DetectedScaleFrame? {
        val address = canonicalBluetoothAddress(advertisement.address) ?: return null
        for ((manufacturerId, payload) in advertisement.manufacturerData) {
            val frame = YunmaiM263.parse(address, manufacturerId, payload) ?: continue
            return DetectedScaleFrame(ScaleCatalog.yunmai3Se, frame)
        }
        return null
    }
}
