package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RealmeGattTest {
    @Test fun identifiesByProductNameOrVendorService() {
        assertSame(ScaleCatalog.realmeSmartScale, RealmeGattProtocol.identify(advertisement("realme Smart Scale")))
        assertSame(
            ScaleCatalog.realmeSmartScale,
            RealmeGattProtocol.identify(
                advertisement("Scale", setOf(RealmeGattProtocol.SERVICE)),
            ),
        )
        assertNull(RealmeGattProtocol.identify(advertisement("Other Scale")))
    }

    @Test fun connectedCodecSubscribesAndSendsSixPartHandshake() {
        val codec = codec()
        val operations = codec.onConnected(1_700_000_000_000).operations
        assertEquals(8, operations.size)
        assertEquals(GattOperationKind.NOTIFY, operations[0].kind)
        assertEquals(GattOperationKind.NOTIFY, operations[1].kind)
        assertEquals(6, operations.drop(2).count { it.kind == GattOperationKind.WRITE })
    }

    @Test fun macObfuscatedMeasurementProducesFinalWeight() {
        val address = "02:11:22:33:44:55"
        val codec = RealmeGattProtocol.create(
            ScaleCatalog.realmeSmartScale,
            address,
            GattHandshakeProfile(),
        )!!
        val payload = ByteArray(17)
        payload[0] = 0x10
        payload[8] = 0x1C
        payload[9] = 0x0C
        val frame = codec.onNotification(
            RealmeGattProtocol.NOTIFY_MEASUREMENT,
            obfuscate(payload, RealmeWire.macBytes(address)),
            1_000,
        ).frame!!
        assertEquals(71.8, frame.kg, 0.0001)
        assertEquals(address, frame.identity)
        assertTrue(frame.final)
    }

    @Test fun keepAliveUsesDocumentedOneSecondCommand() {
        assertEquals(
            listOf(0x00, 0x01, 0xD9),
            codec().periodicOperation()!!.payload.map { it.u8() },
        )
    }

    private fun codec() = RealmeGattProtocol.create(
        ScaleCatalog.realmeSmartScale,
        "02:11:22:33:44:55",
        GattHandshakeProfile(),
    )!!

    private fun advertisement(name: String, services: Set<String> = emptySet()) =
        ScaleAdvertisement(
            address = "02:11:22:33:44:55",
            name = name,
            manufacturerData = emptyMap(),
            serviceUuids = services,
        )

    private fun obfuscate(payload: ByteArray, mac: ByteArray): ByteArray =
        ByteArray(payload.size + 2).also { output ->
            output[0] = 0x10
            output[1] = 0x11
            for (index in payload.indices) {
                output[index + 2] =
                    (payload[index].toInt() xor mac[index % mac.size].toInt()).toByte()
            }
        }
}
