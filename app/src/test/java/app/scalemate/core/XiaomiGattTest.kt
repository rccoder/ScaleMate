package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiGattTest {
    @Test fun identifiesOfficialWeightAndBodyCompositionModels() {
        assertSame(
            ScaleCatalog.xiaomiWeightScale2,
            XiaomiGattProtocol.identify(advertisement("MI_SCALE2")),
        )
        assertSame(
            ScaleCatalog.xiaomiWeightScale2,
            XiaomiGattProtocol.identify(
                advertisement("MI_SCALE", setOf(XiaomiGattProtocol.SERVICE_WEIGHT)),
            ),
        )
        assertSame(
            ScaleCatalog.xiaomiBodyScale2,
            XiaomiGattProtocol.identify(advertisement("MIBCS")),
        )
        assertSame(
            ScaleCatalog.xiaomiBodyScale2,
            XiaomiGattProtocol.identify(
                advertisement("MI-BODY", setOf(XiaomiGattProtocol.SERVICE_BODY)),
            ),
        )
        assertNull(XiaomiGattProtocol.identify(advertisement("unknown")))
    }

    @Test fun standardWeightMeasurementAtDefaultResolutionProducesFinalWeight() {
        val codec = XiaomiGattProtocol.create(
            ScaleCatalog.xiaomiWeightScale2,
            "AA:BB:CC:DD:EE:FF",
            GattHandshakeProfile(),
        )!!
        val update = codec.onNotification(
            XiaomiGattProtocol.CHAR_WEIGHT_MEASUREMENT,
            byteArrayOf(0x00, 0x18, 0x38),
            1_000,
        )
        val frame = update.frame!!
        assertEquals("AA:BB:CC:DD:EE:FF", frame.identity)
        assertEquals(71.8, frame.kg, 0.0001)
        assertTrue(frame.final)
    }

    @Test fun highResolutionFlagUsesHundredthKilogramUnits() {
        assertEquals(
            71.8,
            XiaomiWeightMeasurement.parseKg(byteArrayOf(0x02, 0x0C, 0x1C))!!,
            0.0001,
        )
    }

    @Test fun imperialFlagConvertsPoundsToKilograms() {
        assertEquals(
            68.0388555,
            XiaomiWeightMeasurement.parseKg(byteArrayOf(0x01, 0x98.toByte(), 0x3A))!!,
            0.0001,
        )
    }

    private fun advertisement(name: String, services: Set<String> = emptySet()) =
        ScaleAdvertisement(
            address = "AA:BB:CC:DD:EE:FF",
            name = name,
            manufacturerData = emptyMap(),
            serviceUuids = services,
        )
}
