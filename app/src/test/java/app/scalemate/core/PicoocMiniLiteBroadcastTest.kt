package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PicoocMiniLiteBroadcastTest {
    private val address = "D0:49:00:4B:4F:1E"
    private val manufacturerId = 0x49D0

    private fun fullPacket(
        state: Int = PicoocMiniLiteBroadcast.completeState,
        weightRaw: Int = 1630,
        protocol: Int = 0x1F,
    ) = ByteArray(16).apply {
        this[0] = 0xD0.toByte()
        this[1] = 0x49
        this[2] = 0x00
        this[3] = 0x4B
        this[4] = 0x4F
        this[5] = 0x1E
        this[6] = state.toByte()
        this[7] = protocol.toByte()
        this[8] = (weightRaw ushr 8).toByte()
        this[9] = weightRaw.toByte()
        this[10] = 0x01
        this[11] = 0xEC.toByte()
        this[12] = 0x00
        this[13] = 0x00
        this[14] = 0
        this[15] = (0xFF - ((0 until 15).sumOf { u8(it) } and 0xFF)).toByte()
    }

    private fun advertisement(
        packet: ByteArray? = fullPacket(),
        name: String = "PICOOC-L",
        deviceAddress: String = address,
    ): ScaleAdvertisement {
        val payload = packet?.let { if (it.size == 16) it.copyOfRange(2, 16) else it }
        return ScaleAdvertisement(
            address = deviceAddress,
            name = name,
            manufacturerData = if (payload == null) emptyMap() else mapOf(manufacturerId to payload),
        )
    }

    @Test fun parsesCompletedMeasurementAtFiveHundredthsOfAKilogram() {
        val detected = PicoocMiniLiteBroadcast.parse(advertisement())!!

        assertSame(ScaleCatalog.picoocMiniLite, detected.model)
        assertEquals(81.5, detected.frame.kg, 0.00001)
        assertEquals(1, detected.frame.state)
        assertEquals(address, detected.frame.identity)
    }

    @Test fun nonCompletedStatesAreUsableForProgressButCannotStartASession() {
        val packet = fullPacket(state = 0x38, weightRaw = 0)
        val detected = PicoocMiniLiteBroadcast.parse(advertisement(packet))!!

        assertEquals(0.0, detected.frame.kg, 0.0)
        assertEquals(2, detected.frame.state)
    }

    @Test fun acceptsNameCaseInsensitivelyAndBindsToMACBytesRatherThanAFixedCompanyId() {
        assertSame(
            ScaleCatalog.picoocMiniLite,
            PicoocMiniLiteBroadcast.parse(advertisement(name = "picooc-l"))!!.model,
        )

        val changedAddress = "12:34:56:78:9A:BC"
        val packet = fullPacket().apply {
            this[0] = 0x12
            this[1] = 0x34
            this[2] = 0x56
            this[3] = 0x78
            this[4] = 0x9A.toByte()
            this[5] = 0xBC.toByte()
            this[15] = (0xFF - ((0 until 15).sumOf { u8(it) } and 0xFF)).toByte()
        }
        val detected = PicoocMiniLiteBroadcast.parse(
            ScaleAdvertisement(
                changedAddress,
                "PICOOC-L",
                mapOf(0x3412 to packet.copyOfRange(2, 16)),
            ),
        )!!
        assertEquals(changedAddress, detected.frame.identity)
    }

    @Test fun rejectsWrongNameChecksumMacLengthAndWeight() {
        assertNull(PicoocMiniLiteBroadcast.parse(advertisement(name = "PICOOC-L1")))
        assertNull(PicoocMiniLiteBroadcast.parse(advertisement(name = "")))
        assertNull(
            PicoocMiniLiteBroadcast.parse(
                advertisement(fullPacket().also { it[15] = (it[15] + 1).toByte() }),
            ),
        )
        assertNull(
            PicoocMiniLiteBroadcast.parse(
                advertisement(fullPacket().also { it[2] = 0x01 }),
            ),
        )
        assertNull(PicoocMiniLiteBroadcast.parse(advertisement(fullPacket(weightRaw = 6001))))
        assertNull(PicoocMiniLiteBroadcast.parse(advertisement(fullPacket().copyOf(15))))
        assertNull(PicoocMiniLiteBroadcast.parse(advertisement(deviceAddress = "invalid")))
        assertNull(PicoocMiniLiteBroadcast.parse(advertisement(packet = null)))
    }
}
