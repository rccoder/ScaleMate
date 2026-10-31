package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScaleProtocolsTest {
    private fun yunmaiM263() = ScaleAdvertisement(
        address = "ED:68:00:69:8E:CA",
        name = null,
        manufacturerData = mapOf(
            0x8ECA to byteArrayOf(
                0x69, 0, 0x68, 0xED.toByte(), 8, 0x52, 0x16, 0x1D,
                1, 0x1B, 0xDA.toByte(), 0, 0, 0xDD.toByte(),
            ),
        ),
    )

    private fun yunmaiX() = ScaleAdvertisement(
        address = "C0:FF:EE:12:34:56",
        name = null,
        manufacturerData = mapOf(
            0x3456 to ByteArray(14).apply {
                byteArrayOf(0x12, 0xEE.toByte(), 0xFF.toByte(), 0xC0.toByte()).copyInto(this)
                byteArrayOf(0x0B, 0x74, 0x17).copyInto(this, 4)
                this[7] = 0x04
                this[8] = 0x03
                this[9] = 0x1F
                this[10] = 0x40
                var checksum = 0
                for (index in 7..12) checksum = checksum xor u8(index)
                this[13] = checksum.toByte()
            },
        ),
    )

    private fun picooc() = ScaleAdvertisement(
        address = "D0:49:00:4B:4F:1E",
        name = "PICOOC-L",
        manufacturerData = mapOf(
            0x49D0 to ByteArray(14).apply {
                byteArrayOf(0x00, 0x4B, 0x4F, 0x1E).copyInto(this)
                this[4] = 0x39
                this[5] = 0x1F
                this[6] = 0x06
                this[7] = 0x5E
                val full = ByteArray(16)
                byteArrayOf(0xD0.toByte(), 0x49).copyInto(full)
                copyInto(full, 2)
                full[15] = (0xFF - ((0 until 15).sumOf { full[it].u8() } and 0xFF)).toByte()
                full.copyOfRange(2, 16).copyInto(this)
            },
        ),
    )

    private fun etekcity() = ScaleAdvertisement(
        address = "A0:B1:C2:D3:E4:F5",
        name = null,
        manufacturerData = mapOf(
            0x06D0 to ByteArray(20).apply {
                this[10] = 0xEC.toByte()
                this[11] = 0x26
                this[12] = 0x01
                this[15] = 1
            },
        ),
        serviceUuids = setOf("0000ffd0-0000-1000-8000-00805f9b34fb"),
    )

    private fun fitIndex() = ScaleAdvertisement(
        address = "00:00:5E:00:53:01",
        name = null,
        manufacturerData = mapOf(
            0xFFFF to ByteArray(23).apply {
                this[0] = 0xAA.toByte()
                this[1] = 0xBB.toByte()
                this[15] = 0x15
                this[17] = 0x76
                this[18] = 0x1B
                byteArrayOf(0x51, 0x0E, 0x03).copyInto(this, 19)
            },
        ),
    )

    private fun okok() = ScaleAdvertisement(
        address = "11:22:33:44:55:66",
        name = "ADV",
        manufacturerData = mapOf(0xF0FF to byteArrayOf(0, 0, 0xDF.toByte(), 0x02)),
    )

    @Test fun routesEachAdvertisementToItsProductModel() {
        val expected = listOf(
            ScaleCatalog.yunmai3Se to yunmaiM263(),
            ScaleCatalog.yunmaiX to yunmaiX(),
            ScaleCatalog.picoocMiniLite to picooc(),
            ScaleCatalog.etekcityFit8S to etekcity(),
            ScaleCatalog.fitIndexFt26rW to fitIndex(),
            ScaleCatalog.okokVf0 to okok(),
        )

        for ((model, advertisement) in expected) {
            assertSame(model, ScaleProtocols.parse(advertisement)?.model)
        }
    }

    @Test fun expectedAdapterPreventsCrossFamilyMisidentification() {
        assertSame(
            ScaleCatalog.picoocMiniLite,
            ScaleProtocols.parse(picooc(), PicoocMiniLiteBroadcast.ADAPTER)?.model,
        )
        assertNull(ScaleProtocols.parse(etekcity(), YunmaiXBroadcast.ADAPTER))
        assertNull(ScaleProtocols.parse(fitIndex(), OkOkBroadcast.ADAPTER))
        assertNull(ScaleProtocols.parse(yunmaiM263(), "unknown-adapter"))
    }

    @Test fun sharedAdaptersStillResolveTheWireSignatureToTheCorrectProduct() {
        assertSame(ScaleCatalog.okokVf0, ScaleProtocols.parse(okok(), OkOkBroadcast.ADAPTER)?.model)
        assertSame(ScaleCatalog.yunmaiX, ScaleProtocols.parse(yunmaiX(), YunmaiXBroadcast.ADAPTER)?.model)
    }

    @Test fun rejectsEmptyForeignAndMalformedAdvertisements() {
        assertNull(ScaleProtocols.parse(ScaleAdvertisement("11:22:33:44:55:66", "watch", emptyMap())))
        assertNull(
            ScaleProtocols.parse(
                ScaleAdvertisement(
                    "11:22:33:44:55:66",
                    "Weight Scale",
                    mapOf(0xFFFF to byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
                ),
            ),
        )
        assertNull(
            ScaleProtocols.parse(
                ScaleAdvertisement(
                    "invalid",
                    "ADV",
                    mapOf(0xF0FF to byteArrayOf(0, 0, 0xDF.toByte(), 0x02)),
                ),
            ),
        )
    }

    @Test fun identifiesConnectionOnlyScalesFromAdvertisementNames() {
        val expected = listOf(
            "MI_SCALE2" to ScaleCatalog.xiaomiWeightScale2,
            "MIBCS" to ScaleCatalog.xiaomiBodyScale2,
            "Latin-S" to ScaleCatalog.picoocGatt,
            "AH100" to ScaleCatalog.honorBodyFatScaleAh100,
            "realme Smart Scale" to ScaleCatalog.realmeSmartScale,
            "YUNMAI-ISSE" to ScaleCatalog.yunmaiSeGatt,
            "YUNMAI-SIGNAL" to ScaleCatalog.yunmaiMiniGatt,
        )
        for ((name, model) in expected) {
            val advertisement = ScaleAdvertisement("AA:BB:CC:DD:EE:FF", name, emptyMap())
            assertSame(model, ScaleProtocols.identify(advertisement))
        }
    }
}
