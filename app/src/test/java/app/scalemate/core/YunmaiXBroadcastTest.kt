package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class YunmaiXBroadcastTest {
    private val address = "C0:FF:EE:12:34:56"
    private val manufacturerId = 0x3456
    private val international = byteArrayOf(0x0B, 0x74, 0x17)
    private val chinese = byteArrayOf(0x06, 0x52, 0x11)

    private fun payload(
        state: Int,
        weightRaw: Int,
        signature: ByteArray = international,
        sessionByte: Int = 0x04,
        macEcho: ByteArray = byteArrayOf(0x12, 0xEE.toByte(), 0xFF.toByte(), 0xC0.toByte()),
    ) = ByteArray(14).apply {
        macEcho.copyInto(this, 0)
        signature.copyInto(this, 4)
        this[7] = sessionByte.toByte()
        this[8] = state.toByte()
        this[9] = (weightRaw ushr 8).toByte()
        this[10] = weightRaw.toByte()
        var checksum = 0
        for (index in 7..12) checksum = checksum xor u8(index)
        this[13] = checksum.toByte()
    }

    private fun advertisement(
        packet: ByteArray? = payload(3, 8000),
        id: Int = manufacturerId,
        deviceAddress: String = address,
    ) = ScaleAdvertisement(
        address = deviceAddress,
        name = null,
        manufacturerData = if (packet == null) emptyMap() else mapOf(id to packet),
    )

    @Test fun parsesYunmaiXFinalFrame() {
        val detected = YunmaiXBroadcast.parse(advertisement())!!

        assertSame(ScaleCatalog.yunmaiX, detected.model)
        assertEquals(address, detected.frame.identity)
        assertEquals(80.0, detected.frame.kg, 0.00001)
        assertEquals(1, detected.frame.state)
        assertEquals(0x04, detected.frame.token)
    }

    @Test fun parsesChineseMini2sSignatureAsItsOwnProduct() {
        val packet = payload(3, 6540, signature = chinese, sessionByte = 0x10)
        val detected = YunmaiXBroadcast.parse(advertisement(packet))!!

        assertSame(ScaleCatalog.yunmaiMini2S, detected.model)
        assertEquals(65.4, detected.frame.kg, 0.00001)
    }

    @Test fun liveAndStableFramesDoNotStartASessionButCanResetAtZero() {
        assertEquals(2, YunmaiXBroadcast.parse(advertisement(payload(1, 7955)))!!.frame.state)
        assertEquals(2, YunmaiXBroadcast.parse(advertisement(payload(2, 8000)))!!.frame.state)
        val reset = YunmaiXBroadcast.parse(advertisement(payload(1, 0)))!!
        assertEquals(0.0, reset.frame.kg, 0.0)
        assertEquals(2, reset.frame.state)
    }

    @Test fun rejectsWrongManufacturerIdMacEchoOrAddress() {
        assertNull(YunmaiXBroadcast.parse(advertisement(id = 0x1234)))
        assertNull(
            YunmaiXBroadcast.parse(
                advertisement(payload(3, 8000, macEcho = byteArrayOf(1, 2, 3, 4))),
            ),
        )
        assertNull(YunmaiXBroadcast.parse(advertisement(deviceAddress = "not-an-address")))
    }

    @Test fun rejectsChecksumSignatureStateAndSizeFailures() {
        assertNull(YunmaiXBroadcast.parse(advertisement(payload(3, 8000).also { it[9] = 0x1C })))
        assertNull(
            YunmaiXBroadcast.parse(
                advertisement(payload(3, 8000, signature = byteArrayOf(1, 2, 3))),
            ),
        )
        assertNull(YunmaiXBroadcast.parse(advertisement(payload(0, 8000))))
        assertNull(YunmaiXBroadcast.parse(advertisement(payload(4, 8000))))
        assertNull(YunmaiXBroadcast.parse(advertisement(payload(3, 30001))))
        assertNull(YunmaiXBroadcast.parse(advertisement(payload(3, 8000).copyOf(13))))
        assertNull(YunmaiXBroadcast.parse(advertisement(payload(3, 8000).copyOf(15))))
        assertNull(YunmaiXBroadcast.parse(advertisement(packet = null)))
    }
}
