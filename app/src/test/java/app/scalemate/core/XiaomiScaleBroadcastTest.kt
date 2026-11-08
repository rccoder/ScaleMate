package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class XiaomiScaleBroadcastTest {
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test
    fun weightScaleBroadcastParsesThePublicKilogramVectorForBothGenerations() {
        val first = XiaomiWeightScaleBroadcast.parse(
            advertisement("MI_SCALE", XiaomiWeightScaleBroadcast.SERVICE_WEIGHT, "223e30e607020e10293a"),
        )!!
        val second = XiaomiWeightScaleBroadcast.parse(
            advertisement("MI_SCALE2", XiaomiWeightScaleBroadcast.SERVICE_WEIGHT, "223e30e607020e10293a"),
        )!!

        assertSame(ScaleCatalog.xiaomiWeightScale2, first.model)
        assertSame(ScaleCatalog.xiaomiWeightScale2, second.model)
        assertEquals(61.75, first.frame.kg, 0.00001)
        assertEquals(1, first.frame.state)
        assertEquals(address, first.frame.identity)
    }

    @Test
    fun weightScaleBroadcastConvertsPoundsAndJinToKilograms() {
        val pounds = XiaomiWeightScaleBroadcast.parse(
            advertisement("MI_SCALE2", XiaomiWeightScaleBroadcast.SERVICE_WEIGHT, "237233e607020e10293a"),
        )!!
        val jin = XiaomiWeightScaleBroadcast.parse(
            advertisement("MI_SCALE2", XiaomiWeightScaleBroadcast.SERVICE_WEIGHT, "323e30e607020e10293a"),
        )!!

        assertEquals(131.7 * 0.45359237, pounds.frame.kg, 0.00001)
        assertEquals(61.75, jin.frame.kg, 0.00001)
    }

    @Test
    fun weightScaleUnstableAndRemovedFramesCannotStartButRemovedResetsToZero() {
        val unstable = XiaomiWeightScaleBroadcast.parse(
            advertisement("MI_SCALE2", XiaomiWeightScaleBroadcast.SERVICE_WEIGHT, "023e30e607020e10293a"),
        )!!
        val removed = XiaomiWeightScaleBroadcast.parse(
            advertisement("MI_SCALE2", XiaomiWeightScaleBroadcast.SERVICE_WEIGHT, "a23e30e607020e10293a"),
        )!!

        assertEquals(61.75, unstable.frame.kg, 0.00001)
        assertEquals(2, unstable.frame.state)
        assertEquals(0.0, removed.frame.kg, 0.0)
        assertEquals(2, removed.frame.state)
    }

    @Test
    fun weightScaleRejectsWrongServiceSizeUnitAndWeight() {
        val valid = "223e30e607020e10293a"

        assertNull(
            XiaomiWeightScaleBroadcast.parse(
                advertisement("MI_SCALE2", "0000181b-0000-1000-8000-00805f9b34fb", valid),
            ),
        )
        assertNull(
            XiaomiWeightScaleBroadcast.parse(
                advertisement("MI_SCALE2", XiaomiWeightScaleBroadcast.SERVICE_WEIGHT, valid.dropLast(2)),
            ),
        )
        assertNull(
            XiaomiWeightScaleBroadcast.parse(
                advertisement("MI_SCALE2", XiaomiWeightScaleBroadcast.SERVICE_WEIGHT, "203e30e607020e10293a"),
            ),
        )
        assertNull(
            XiaomiWeightScaleBroadcast.parse(
                advertisement("MI_SCALE2", XiaomiWeightScaleBroadcast.SERVICE_WEIGHT, "2261eae607020e10293a"),
            ),
        )
        assertNull(
            XiaomiWeightScaleBroadcast.parse(
                ScaleAdvertisement(
                    "invalid",
                    "MI_SCALE2",
                    emptyMap(),
                    mapOf(XiaomiWeightScaleBroadcast.SERVICE_WEIGHT to valid.hex()),
                ),
            ),
        )
    }

    @Test
    fun bodyScaleBroadcastParsesKilogramsPoundsAndObjectWeight() {
        val first = XiaomiBodyScaleBroadcast.parse(
            advertisement("MIBCS", XiaomiBodyScaleBroadcast.SERVICE_BODY, "0226e607020e10293af7019a38"),
        )!!
        val second = XiaomiBodyScaleBroadcast.parse(
            advertisement("MIBFS", XiaomiBodyScaleBroadcast.SERVICE_BODY, "0226e607020e10293af7019a38"),
        )!!
        val pounds = XiaomiBodyScaleBroadcast.parse(
            advertisement("MIBFS", XiaomiBodyScaleBroadcast.SERVICE_BODY, "0326e607020e10293af701f136"),
        )!!
        val objectWeight = XiaomiBodyScaleBroadcast.parse(
            advertisement("MIBFS", XiaomiBodyScaleBroadcast.SERVICE_BODY, "0624e607020e10293a0000fc03"),
        )!!

        assertSame(ScaleCatalog.xiaomiBodyScale2, first.model)
        assertSame(ScaleCatalog.xiaomiBodyScale2, second.model)
        assertEquals(72.45, second.frame.kg, 0.00001)
        assertEquals(140.65 * 0.45359237, pounds.frame.kg, 0.00001)
        assertEquals(5.1, objectWeight.frame.kg, 0.00001)
    }

    @Test
    fun bodyScaleUnstableAndRemovedFramesCannotStartButRemovedResetsToZero() {
        val unstable = XiaomiBodyScaleBroadcast.parse(
            advertisement("MIBFS", XiaomiBodyScaleBroadcast.SERVICE_BODY, "0204e607020e10293a00009a38"),
        )!!
        val removed = XiaomiBodyScaleBroadcast.parse(
            advertisement("MIBFS", XiaomiBodyScaleBroadcast.SERVICE_BODY, "02a6e607020e10293af7019a38"),
        )!!

        assertEquals(72.45, unstable.frame.kg, 0.00001)
        assertEquals(2, unstable.frame.state)
        assertEquals(0.0, removed.frame.kg, 0.0)
        assertEquals(2, removed.frame.state)
    }

    @Test
    fun repeatedFramesKeepTokensAndChangedWeightChangesTheToken() {
        val first = XiaomiBodyScaleBroadcast.parse(
            advertisement("MIBFS", XiaomiBodyScaleBroadcast.SERVICE_BODY, "0226e607020e10293af7019a38"),
        )!!
        val same = XiaomiBodyScaleBroadcast.parse(
            advertisement("MIBFS", XiaomiBodyScaleBroadcast.SERVICE_BODY, "0226e607020e10293af7019a38"),
        )!!
        val changed = XiaomiBodyScaleBroadcast.parse(
            advertisement("MIBFS", XiaomiBodyScaleBroadcast.SERVICE_BODY, "0226e607020e10293af7019b38"),
        )!!

        assertEquals(first.frame.token, same.frame.token)
        assertNotEquals(first.frame.token, changed.frame.token)
    }

    @Test
    fun bodyScaleRejectsWrongServiceSizeAndWeight() {
        val valid = "0226e607020e10293af7019a38"

        assertNull(
            XiaomiBodyScaleBroadcast.parse(
                advertisement("MIBFS", "0000181d-0000-1000-8000-00805f9b34fb", valid),
            ),
        )
        assertNull(
            XiaomiBodyScaleBroadcast.parse(
                advertisement("MIBFS", XiaomiBodyScaleBroadcast.SERVICE_BODY, valid.dropLast(2)),
            ),
        )
        assertNull(
            XiaomiBodyScaleBroadcast.parse(
                advertisement("MIBFS", XiaomiBodyScaleBroadcast.SERVICE_BODY, "0226e607020e10293a000061ea"),
            ),
        )
    }

    private fun advertisement(name: String, service: String, payload: String) =
        ScaleAdvertisement(
            address = address,
            name = name,
            manufacturerData = emptyMap(),
            serviceData = mapOf(service to payload.hex()),
        )

    private fun String.hex(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
