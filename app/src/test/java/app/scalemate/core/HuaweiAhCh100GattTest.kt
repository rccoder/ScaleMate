package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiAhCh100GattTest {
    @Test fun identifiesAh100AndCh100ButNotCh100S() {
        assertSame(
            ScaleCatalog.honorBodyFatScaleAh100,
            HuaweiAhCh100GattProtocol.identify(advertisement("AH100")),
        )
        assertSame(
            ScaleCatalog.honorBodyFatScaleAh100,
            HuaweiAhCh100GattProtocol.identify(advertisement("HONOR CH100")),
        )
        assertNull(HuaweiAhCh100GattProtocol.identify(advertisement("CH100S")))
    }

    @Test fun authTokenAndMagicKeyMatchReferenceFixture() {
        val token = HuaweiAhCh100Wire.authToken(1)
        assertEquals("11223344551001", token.hex())
        assertEquals(
            "4de8e04c2e5f5d2a980fde3456732156",
            HuaweiAhCh100Wire.deriveMagicKey(
                token,
                HuaweiAhCh100Wire.macBytes("5C:CA:D3:08:7B:4F"),
            ).hex(),
        )
    }

    @Test fun realHardwareCaptureDecodesDisplayedWeights() {
        val mac = HuaweiAhCh100Wire.macBytes("5C:CA:D3:14:2B:B3")
        val key = HuaweiAhCh100Wire.deriveMagicKey(HuaweiAhCh100Wire.authToken(1), mac)
        val first = HuaweiAhCh100Wire.decodeMeasurement(
            "bc110eabf78678303ba462fc93d1db4b348861".hexBytes(),
            key,
            mac,
        )
        val second = HuaweiAhCh100Wire.decodeMeasurement(
            "bc110eabf48679303ba462fc93dce84b3f8861".hexBytes(),
            key,
            mac,
        )
        assertEquals(126.6, first.weightKg, 0.0001)
        assertEquals(33.8, first.fatPercent, 0.0001)
        assertEquals(306, first.impedanceOhm)
        assertEquals(126.5, second.weightKg, 0.0001)
        assertEquals(33.9, second.fatPercent, 0.0001)
        assertEquals(313, second.impedanceOhm)
    }

    @Test fun codecAuthenticatesThenPublishesFinalWeight() {
        val address = "5C:CA:D3:08:7B:4F"
        val mac = HuaweiAhCh100Wire.macBytes(address)
        val codec = HuaweiAhCh100GattProtocol.create(
            ScaleCatalog.honorBodyFatScaleAh100,
            address,
            GattHandshakeProfile(userId = 1),
        )!!
        codec.onConnected(1_700_000_000_000)
        val authResult = byteArrayOf(0xDB.toByte(), 0x04, 0x26) +
            xor(byteArrayOf(0x01, 0x00, 0x00, 0x00), mac)
        val authUpdate = codec.onNotification(
            HuaweiAhCh100GattProtocol.NOTIFY,
            authResult,
            1_000,
        )
        assertTrue(authUpdate.operations.size >= 4)
        val frame = codec.onNotification(
            HuaweiAhCh100GattProtocol.NOTIFY,
            "bc100edef702c9bbf2176c755b6eccdd5b92b0".hexBytes(),
            2_000,
        ).frame!!
        assertEquals(address, frame.identity)
        assertEquals(97.0, frame.kg, 0.0001)
        assertTrue(frame.final)
    }

    private fun advertisement(name: String) = ScaleAdvertisement(
        address = "5C:CA:D3:08:7B:4F",
        name = name,
        manufacturerData = emptyMap(),
    )

    private fun xor(data: ByteArray, mac: ByteArray): ByteArray =
        ByteArray(data.size) { index ->
            (data[index].toInt() xor mac[index % mac.size].toInt()).toByte()
        }

    private fun String.hexBytes(): ByteArray =
        ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
