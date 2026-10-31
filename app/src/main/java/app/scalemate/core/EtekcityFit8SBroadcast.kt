package app.scalemate.core

/** Etekcity Fit 8S advertises grams under company id 0x06D0 with service 0xFFD0. */
object EtekcityFit8SBroadcast : ScaleParser {
    const val ADAPTER = "etekcity-fit-8s-adv-v1"
    override val adapterId = ADAPTER

    private const val manufacturerId = 0x06D0
    private const val serviceUuid = "0000ffd0-0000-1000-8000-00805f9b34fb"

    override fun parse(advertisement: ScaleAdvertisement): DetectedScaleFrame? {
        if (advertisement.serviceUuids.none { it.equals(serviceUuid, ignoreCase = true) }) return null
        val payload = advertisement.manufacturerData[manufacturerId] ?: return null
        if (payload.size != 20) return null
        val address = canonicalBluetoothAddress(advertisement.address) ?: return null
        val rawWeight = payload.u24le(10)
        val kg = rawWeight / 1000.0
        if (!validScaleWeight(kg)) return null
        val stable = payload.u8(15) == 1
        val state = if (stable) 1 else 2
        return DetectedScaleFrame(
            ScaleCatalog.etekcityFit8S,
            ScaleFrame(address, kg, state, protocolToken(rawWeight)),
        )
    }
}
