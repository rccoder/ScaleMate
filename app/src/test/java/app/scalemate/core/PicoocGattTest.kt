package app.scalemate.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PicoocGattTest {
    @Test fun identifiesConnectionModelsButLeavesMiniBroadcastToItsOwnParser() {
        assertSame(ScaleCatalog.picoocGatt, PicoocGattProtocol.identify(advertisement("Latin-S")))
        assertSame(ScaleCatalog.picoocGatt, PicoocGattProtocol.identify(advertisement("PICOOC-CQ")))
        assertNull(PicoocGattProtocol.identify(advertisement("PICOOC-L")))
        assertNull(PicoocGattProtocol.identify(advertisement("scale")))
    }

    @Test fun settledShortFrameProducesFinalWeight() {
        val codec = codec()
        val frame = codec.onNotification(
            PicoocGattProtocol.NOTIFY,
            byteArrayOf(
                0x39,
                0x0A,
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x9A.toByte(),
                0x15,
                0x36,
            ),
            1_000,
        ).frame!!
        assertEquals(71.7, frame.kg, 0.0001)
        assertTrue(frame.final)
    }

    @Test fun handshakeRepliesWithTimeAndProfile() {
        val update = codec().onNotification(
            PicoocGattProtocol.NOTIFY,
            byteArrayOf(0x3A, 0x03, 0x00),
            1_700_000_000_000,
        )
        assertEquals(2, update.operations.size)
        assertTrue(update.operations.all { it.characteristicUuid == PicoocGattProtocol.WRITE })
        val time = update.operations[0].payload
        assertArrayEquals(byteArrayOf(0xF1.toByte(), 0x0A, 0x3A), time.copyOfRange(0, 3))
        assertEquals(1_700_000_000, u32be(time, 3))
        val profile = update.operations[1].payload
        assertArrayEquals(byteArrayOf(0x31, 0x06, 0x01, 0x01, 0x8C.toByte(), 30), profile)
    }

    @Test fun userRequestAcknowledgesAndSendsProfile() {
        val update = codec().onNotification(
            PicoocGattProtocol.NOTIFY,
            byteArrayOf(0x30, 0x03, 0x00),
            1_000,
        )
        assertArrayEquals(byteArrayOf(0xF1.toByte(), 0x03, 0x30), update.operations[0].payload)
        assertEquals(0x31, update.operations[1].payload.u8(0))
    }

    private fun codec() = PicoocGattProtocol.create(
        ScaleCatalog.picoocGatt,
        "AA:BB:CC:DD:EE:FF",
        GattHandshakeProfile(heightCm = 170, age = 30, male = true),
    )!!

    private fun advertisement(name: String) = ScaleAdvertisement(
        address = "AA:BB:CC:DD:EE:FF",
        name = name,
        manufacturerData = emptyMap(),
    )

    private fun u32be(data: ByteArray, offset: Int): Long =
        ((data.u8(offset).toLong() shl 24) or
            (data.u8(offset + 1).toLong() shl 16) or
            (data.u8(offset + 2).toLong() shl 8) or
            data.u8(offset + 3).toLong())
}
