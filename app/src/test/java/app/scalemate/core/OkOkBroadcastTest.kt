package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OkOkBroadcastTest {
    private val address = "11:22:33:44:55:66"
    private val v20 = 0x20CA
    private val v11 = 0x11CA
    private val vf0 = 0xF0FF

    private fun v20Payload(rawWeight: Int, flags: Int): ByteArray = ByteArray(19).apply {
        this[6] = flags.toByte()
        this[8] = (rawWeight ushr 8).toByte()
        this[9] = rawWeight.toByte()
        var checksum = 0x20
        for (index in 0..11) checksum = checksum xor u8(index)
        this[12] = checksum.toByte()
    }

    private fun v11Payload(rawWeight: Int, properties: Int): ByteArray = ByteArray(23).apply {
        this[3] = (rawWeight ushr 8).toByte()
        this[4] = rawWeight.toByte()
        this[9] = properties.toByte()
        var checksum = 0xCA xor 0x11
        for (index in 0..15) checksum = checksum xor u8(index)
        this[16] = checksum.toByte()
    }

    private fun vf0Payload(rawWeight: Int) = byteArrayOf(
        0,
        0,
        rawWeight.toByte(),
        (rawWeight ushr 8).toByte(),
    )

    private fun advertisement(
        id: Int,
        payload: ByteArray?,
        name: String? = "ADV",
        deviceAddress: String = address,
    ) = ScaleAdvertisement(
        address = deviceAddress,
        name = name,
        manufacturerData = if (payload == null) emptyMap() else mapOf(id to payload),
    )

    @Test fun parsesV20FinalFrameWithHighResolution() {
        val detected = OkOkBroadcast.parse(advertisement(v20, v20Payload(7350, 0x05)))!!

        assertSame(ScaleCatalog.okokV20, detected.model)
        assertEquals(73.5, detected.frame.kg, 0.00001)
        assertEquals(1, detected.frame.state)
    }

    @Test fun v20MeasuringFrameCannotStartASession() {
        val detected = OkOkBroadcast.parse(advertisement(v20, v20Payload(735, 0x00)))!!

        assertEquals(73.5, detected.frame.kg, 0.00001)
        assertEquals(2, detected.frame.state)
    }

    @Test fun parsesV11KilogramJinPoundAndStonePoundUnits() {
        val kg = OkOkBroadcast.parse(advertisement(v11, v11Payload(735, 0x00)))!!
        assertSame(ScaleCatalog.okokV11, kg.model)
        assertEquals(73.5, kg.frame.kg, 0.00001)

        val jin = OkOkBroadcast.parse(advertisement(v11, v11Payload(1470, 0x08)))!!
        assertEquals(73.5, jin.frame.kg, 0.00001)

        val pounds = OkOkBroadcast.parse(advertisement(v11, v11Payload(1620, 0x10)))!!
        assertEquals(1620 / 10.0 / 2.204623, pounds.frame.kg, 0.00001)

        val stonePounds = OkOkBroadcast.parse(advertisement(v11, v11Payload(0x0B08, 0x18)))!!
        assertEquals(11 * 6.350293 + 0.8 * 0.453592, stonePounds.frame.kg, 0.00001)
    }

    @Test fun parsesVf0LittleEndianTenthsOfAKilogram() {
        val detected = OkOkBroadcast.parse(advertisement(vf0, vf0Payload(735)))!!

        assertSame(ScaleCatalog.okokVf0, detected.model)
        assertEquals(73.5, detected.frame.kg, 0.00001)
        assertEquals(1, detected.frame.state)
    }

    @Test fun tokensChangeWithWeightAndNeverWithRepeatedIdenticalFrames() {
        val first = OkOkBroadcast.parse(advertisement(vf0, vf0Payload(735)))!!
        val same = OkOkBroadcast.parse(advertisement(vf0, vf0Payload(735)))!!
        val changed = OkOkBroadcast.parse(advertisement(vf0, vf0Payload(740)))!!

        assertEquals(first.frame.token, same.frame.token)
        assertNotEquals(first.frame.token, changed.frame.token)
    }

    @Test fun rejectsBadChecksumsLengthsUnitsWeightsAddressAndNames() {
        assertNull(
            OkOkBroadcast.parse(
                advertisement(v20, v20Payload(7350, 0x05).also { it[12] = (it[12] + 1).toByte() }),
            ),
        )
        assertNull(
            OkOkBroadcast.parse(
                advertisement(v11, v11Payload(735, 0x00).also { it[16] = (it[16] + 1).toByte() }),
            ),
        )
        assertNull(OkOkBroadcast.parse(advertisement(v20, v20Payload(7350, 0x05).copyOf(18))))
        assertNull(OkOkBroadcast.parse(advertisement(v11, v11Payload(735, 0x00).copyOf(22))))
        assertNull(OkOkBroadcast.parse(advertisement(vf0, vf0Payload(735).copyOf(3))))
        assertNull(OkOkBroadcast.parse(advertisement(v20, v20Payload(60000, 0x04))))
        assertNull(OkOkBroadcast.parse(advertisement(v11, v11Payload(6000, 0x00))))
        assertNull(OkOkBroadcast.parse(advertisement(vf0, vf0Payload(6000))))
        assertNull(OkOkBroadcast.parse(advertisement(vf0, vf0Payload(735), name = "Unknown")))
        assertNull(OkOkBroadcast.parse(advertisement(vf0, vf0Payload(735), name = null)))
        assertNull(OkOkBroadcast.parse(advertisement(vf0, vf0Payload(735), deviceAddress = "invalid")))
        assertNull(OkOkBroadcast.parse(advertisement(vf0, payload = null)))
    }

    @Test fun acceptsAllKnownOemBroadcastNames() {
        for (name in listOf("ADV", "Chipsea-BLE", "Yoda0-123", "yoda1-scale")) {
            val detected = OkOkBroadcast.parse(advertisement(vf0, vf0Payload(735), name = name))
            assertEquals(73.5, detected!!.frame.kg, 0.00001)
        }
    }
}
