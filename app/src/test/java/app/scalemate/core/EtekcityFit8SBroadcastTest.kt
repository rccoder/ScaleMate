package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EtekcityFit8SBroadcastTest {
    private val address = "A0:B1:C2:D3:E4:F5"
    private val manufacturerId = 0x06D0
    private val service = "0000ffd0-0000-1000-8000-00805f9b34fb"

    private fun payload(grams: Int = 75500, stable: Boolean = true) = ByteArray(20).apply {
        this[0] = 0x01
        this[10] = grams.toByte()
        this[11] = (grams ushr 8).toByte()
        this[12] = (grams ushr 16).toByte()
        this[13] = 0xF4.toByte()
        this[14] = 0x01
        this[15] = if (stable) 0x01 else 0x00
    }

    private fun advertisement(
        packet: ByteArray? = payload(),
        id: Int = manufacturerId,
        services: Set<String> = setOf(service),
    ) = ScaleAdvertisement(
        address = address,
        name = null,
        manufacturerData = if (packet == null) emptyMap() else mapOf(id to packet),
        serviceUuids = services,
    )

    @Test fun parsesStableWeightFromGrams() {
        val detected = EtekcityFit8SBroadcast.parse(advertisement())!!

        assertSame(ScaleCatalog.etekcityFit8S, detected.model)
        assertEquals(75.5, detected.frame.kg, 0.00001)
        assertEquals(1, detected.frame.state)
        assertEquals(address, detected.frame.identity)
    }

    @Test fun unstableAndZeroFramesCannotStartButCanReset() {
        val unstable = EtekcityFit8SBroadcast.parse(advertisement(payload(stable = false)))!!
        assertEquals(2, unstable.frame.state)

        val reset = EtekcityFit8SBroadcast.parse(advertisement(payload(grams = 0, stable = false)))!!
        assertEquals(0.0, reset.frame.kg, 0.0)
        assertEquals(2, reset.frame.state)
    }

    @Test fun tokenChangesWithRawWeightSoRepeatedAdvertisementsDoNotRestartTiming() {
        val first = EtekcityFit8SBroadcast.parse(advertisement(payload(grams = 75500)))!!
        val same = EtekcityFit8SBroadcast.parse(advertisement(payload(grams = 75500)))!!
        val changed = EtekcityFit8SBroadcast.parse(advertisement(payload(grams = 75510)))!!

        assertEquals(first.frame.token, same.frame.token)
        assertNotEquals(first.frame.token, changed.frame.token)
    }

    @Test fun rejectsMissingOrWrongFingerprintWrongSizeAndInvalidWeight() {
        assertNull(EtekcityFit8SBroadcast.parse(advertisement(services = emptySet())))
        assertNull(EtekcityFit8SBroadcast.parse(advertisement(id = 0x1234)))
        assertNull(EtekcityFit8SBroadcast.parse(advertisement(packet = null)))
        assertNull(EtekcityFit8SBroadcast.parse(advertisement(payload().copyOf(19))))
        assertNull(EtekcityFit8SBroadcast.parse(advertisement(payload(grams = 300001))))
        val stableZero = EtekcityFit8SBroadcast.parse(advertisement(payload(grams = 0, stable = true)))!!
        assertEquals(0.0, stableZero.frame.kg, 0.0)
        assertNull(
            EtekcityFit8SBroadcast.parse(
                ScaleAdvertisement(
                    address = "invalid",
                    name = null,
                    manufacturerData = mapOf(manufacturerId to payload()),
                    serviceUuids = setOf(service),
                ),
            ),
        )
    }
}
