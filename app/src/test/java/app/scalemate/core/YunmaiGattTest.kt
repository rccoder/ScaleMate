package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YunmaiGattTest {
    @Test fun identifiesMiniAndSeProductNames() {
        assertSame(ScaleCatalog.yunmaiMiniGatt, YunmaiGattProtocol.identify(advertisement("YUNMAI-SIGNAL-1")))
        assertSame(ScaleCatalog.yunmaiMiniGatt, YunmaiGattProtocol.identify(advertisement("YUNMAI-ISC2P")))
        assertSame(ScaleCatalog.yunmaiMiniGatt, YunmaiGattProtocol.identify(advertisement("YUNMAI-Scale-3")))
        assertSame(ScaleCatalog.yunmaiSeGatt, YunmaiGattProtocol.identify(advertisement("YUNMAI-ISSE")))
        assertNull(YunmaiGattProtocol.identify(advertisement("YUNMAI-X")))
    }

    @Test fun userPacketMatchesMiniProtocolLayoutAndChecksum() {
        val packet = YunmaiWire.userPacket(
            GattHandshakeProfile(userId = 7, heightCm = 180, age = 36, male = false),
        )
        assertEquals(18, packet.size)
        assertEquals(0x0D, packet.u8(0))
        assertEquals(0x12, packet.u8(1))
        assertEquals(0x10, packet.u8(2))
        assertEquals(7, packet.u16be(6))
        assertEquals(180, packet.u8(8))
        assertEquals(2, packet.u8(9))
        assertEquals(36, packet.u8(10))
        assertEquals(xor(packet, 1, packet.lastIndex), packet.u8(17))
    }

    @Test fun connectedCodecWritesProfileTimeSubscribesAndStarts() {
        val codec = YunmaiGattProtocol.create(
            ScaleCatalog.yunmaiMiniGatt,
            "AA:BB:CC:DD:EE:01",
            GattHandshakeProfile(),
        )!!
        val operations = codec.onConnected(1_700_000_000_000).operations
        assertEquals(4, operations.size)
        assertEquals(GattOperationKind.WRITE, operations[0].kind)
        assertEquals(GattOperationKind.NOTIFY, operations[2].kind)
        assertEquals(YunmaiGattProtocol.NOTIFY_MEASUREMENT, operations[2].characteristicUuid)
        assertEquals(0x13, operations[3].payload.u8(2))
    }

    @Test fun finalFrameProducesWeightForMatchingUser() {
        val address = "AA:BB:CC:DD:EE:01"
        val codec = YunmaiGattProtocol.create(
            ScaleCatalog.yunmaiSeGatt,
            address,
            GattHandshakeProfile(userId = 1),
        )!!
        val frame = codec.onNotification(
            YunmaiGattProtocol.NOTIFY_MEASUREMENT,
            finalFrame(userId = 1, weightRaw = 7180),
            1_000,
        ).frame!!
        assertEquals(address, frame.identity)
        assertEquals(71.8, frame.kg, 0.0001)
        assertTrue(frame.final)
    }

    @Test fun finalFrameForAnotherUserIsRejected() {
        val codec = YunmaiGattProtocol.create(
            ScaleCatalog.yunmaiSeGatt,
            "AA:BB:CC:DD:EE:01",
            GattHandshakeProfile(userId = 1),
        )!!
        assertNull(
            codec.onNotification(
                YunmaiGattProtocol.NOTIFY_MEASUREMENT,
                finalFrame(userId = 2, weightRaw = 7180),
                1_000,
            ).frame,
        )
    }

    @Test fun finalFrameWithInvalidChecksumIsRejected() {
        val codec = YunmaiGattProtocol.create(
            ScaleCatalog.yunmaiSeGatt,
            "AA:BB:CC:DD:EE:01",
            GattHandshakeProfile(userId = 1),
        )!!
        val data = finalFrame(userId = 1, weightRaw = 7180)
        data[data.lastIndex] = (data.u8(data.lastIndex) xor 0x01).toByte()
        assertNull(
            codec.onNotification(
                YunmaiGattProtocol.NOTIFY_MEASUREMENT,
                data,
                1_000,
            ).frame,
        )
    }

    private fun finalFrame(userId: Int, weightRaw: Int): ByteArray {
        val data = byteArrayOf(
            0x0D,
            0x13,
            0x12,
            0x02,
            0x00,
            0x01,
            0x02,
            0x03,
            0x04,
            (userId ushr 24).toByte(),
            (userId ushr 16).toByte(),
            (userId ushr 8).toByte(),
            userId.toByte(),
            (weightRaw ushr 8).toByte(),
            weightRaw.toByte(),
            0x01,
            0xF4.toByte(),
            0x00,
        )
        data[data.lastIndex] = xor(data, 1, data.lastIndex).toByte()
        return data
    }

    private fun advertisement(name: String) = ScaleAdvertisement(
        address = "AA:BB:CC:DD:EE:01",
        name = name,
        manufacturerData = emptyMap(),
    )

    private fun xor(data: ByteArray, start: Int, endExclusive: Int): Int {
        var value = 0
        for (index in start until endExclusive) value = value xor data.u8(index)
        return value
    }
}
