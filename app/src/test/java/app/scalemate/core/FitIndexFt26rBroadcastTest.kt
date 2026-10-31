package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FitIndexFt26rBroadcastTest {
    private val address = "00:00:5E:00:53:01"
    private val companyId = 0xFFFF
    private val signature = byteArrayOf(0x51, 0x0E, 0x03)

    private fun payload(
        status: Int = 0x15,
        weightRaw: Int = 7030,
        modelSignature: ByteArray = signature,
    ) = ByteArray(23).apply {
        this[0] = 0xAA.toByte()
        this[1] = 0xBB.toByte()
        this[15] = status.toByte()
        this[17] = weightRaw.toByte()
        this[18] = (weightRaw ushr 8).toByte()
        modelSignature.copyInto(this, 19)
    }

    private fun advertisement(
        packet: ByteArray? = payload(),
        name: String? = null,
        id: Int = companyId,
        deviceAddress: String = address,
    ) = ScaleAdvertisement(
        address = deviceAddress,
        name = name,
        manufacturerData = if (packet == null) emptyMap() else mapOf(id to packet),
    )

    @Test fun parsesNamedProductWhenSignatureMatches() {
        val detected = FitIndexFt26rBroadcast.parse(advertisement(name = "FITINDEX FT-26R"))!!

        assertSame(ScaleCatalog.fitIndexFt26rW, detected.model)
        assertEquals(70.30, detected.frame.kg, 0.00001)
        assertEquals(1, detected.frame.state)
        assertEquals(address, detected.frame.identity)
    }

    @Test fun movingStatusCannotStartButStableStatusCan() {
        val moving = FitIndexFt26rBroadcast.parse(advertisement(payload(status = 0x14)))!!
        val stable = FitIndexFt26rBroadcast.parse(advertisement(payload(status = 0x15)))!!

        assertEquals(2, moving.frame.state)
        assertEquals(1, stable.frame.state)
        assertEquals(moving.frame.kg, stable.frame.kg, 0.0)
    }

    @Test fun tokenTracksTheRawWeightInsteadOfTheConstantStatusByte() {
        val first = FitIndexFt26rBroadcast.parse(advertisement(payload(weightRaw = 7030)))!!
        val same = FitIndexFt26rBroadcast.parse(advertisement(payload(weightRaw = 7030)))!!
        val changed = FitIndexFt26rBroadcast.parse(advertisement(payload(weightRaw = 7035)))!!

        assertEquals(first.frame.token, same.frame.token)
        assertNotEquals(first.frame.token, changed.frame.token)
    }

    @Test fun rejectsGenericAabbFitIndexNameAndOtherWireFailures() {
        assertNull(
            FitIndexFt26rBroadcast.parse(
                advertisement(payload(modelSignature = byteArrayOf(0, 0, 0)), name = "FITINDEX"),
            ),
        )
        assertNull(FitIndexFt26rBroadcast.parse(advertisement(id = 0x1234)))
        assertNull(
            FitIndexFt26rBroadcast.parse(
                advertisement(payload().also { it[0] = 0x00; it[1] = 0x00 }),
            ),
        )
        assertNull(FitIndexFt26rBroadcast.parse(advertisement(payload().copyOf(21))))
        assertNull(FitIndexFt26rBroadcast.parse(advertisement(payload(weightRaw = 30001))))
        assertNull(FitIndexFt26rBroadcast.parse(advertisement(packet = null)))
        assertNull(FitIndexFt26rBroadcast.parse(advertisement(deviceAddress = "invalid")))
    }
}
