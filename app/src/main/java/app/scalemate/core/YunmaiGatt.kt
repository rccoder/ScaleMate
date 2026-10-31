package app.scalemate.core

import java.util.Locale

/**
 * Yunmai Mini/SE connection-oriented GATT protocol.
 *
 * These scales are separate from the M263 broadcast capture already supported by
 * [YunmaiM263Parser]. ScaleMate only uses the final weight field; resistance and
 * body-composition values in the same notification are intentionally ignored.
 */
object YunmaiGattProtocol : ScaleGattProtocol {
    const val ADAPTER = "yunmai-mini-se-gatt-v1"
    internal const val SERVICE_MEASUREMENT = "0000ffe0-0000-1000-8000-00805f9b34fb"
    internal const val NOTIFY_MEASUREMENT = "0000ffe4-0000-1000-8000-00805f9b34fb"
    internal const val SERVICE_COMMAND = "0000ffe5-0000-1000-8000-00805f9b34fb"
    internal const val WRITE_COMMAND = "0000ffe9-0000-1000-8000-00805f9b34fb"

    override val adapterId: String = ADAPTER

    override fun identify(advertisement: ScaleAdvertisement): ScaleModel? {
        val name = advertisement.name.orEmpty().trim().uppercase(Locale.ROOT)
        return when {
            name.startsWith("YUNMAI-ISSE") -> ScaleCatalog.yunmaiSeGatt
            name.startsWith("YUNMAI-SIGNAL") ||
                name.startsWith("YUNMAI-ISM") ||
                name.startsWith("YUNMAI-ISC2P") ||
                name == "YUNMAI-SCALE-3" -> ScaleCatalog.yunmaiMiniGatt
            else -> null
        }
    }

    override fun create(model: ScaleModel, address: String, profile: GattHandshakeProfile): ScaleGattCodec =
        YunmaiGattCodec(model, address, profile)
}

private class YunmaiGattCodec(
    override val model: ScaleModel,
    private val address: String,
    private val profile: GattHandshakeProfile,
) : ScaleGattCodec {
    override fun onConnected(now: Long): GattUpdate = GattUpdate(
        operations = listOf(
            write(YunmaiWire.userPacket(profile)),
            write(YunmaiWire.timePacket(now)),
            GattOperation(
                GattOperationKind.NOTIFY,
                YunmaiGattProtocol.SERVICE_MEASUREMENT,
                YunmaiGattProtocol.NOTIFY_MEASUREMENT,
            ),
            write(YunmaiWire.startPacket()),
        ),
    )

    override fun onNotification(characteristicUuid: String, data: ByteArray, now: Long): GattUpdate {
        if (!characteristicUuid.equals(YunmaiGattProtocol.NOTIFY_MEASUREMENT, ignoreCase = true)) {
            return GattUpdate()
        }
        val measurement = YunmaiWire.parseFinal(data, profile.userId, address) ?: GattUpdate()
        return measurement
    }

    private fun write(payload: ByteArray): GattOperation =
        GattOperation(
            GattOperationKind.WRITE,
            YunmaiGattProtocol.SERVICE_COMMAND,
            YunmaiGattProtocol.WRITE_COMMAND,
            payload,
        )
}

internal object YunmaiWire {
    private const val HEADER = 0x0D
    private const val SET_USER = 0x10
    private const val SET_TIME = 0x11
    private const val START_MEASUREMENT = 0x13
    private const val FINAL_FRAME = 0x02

    fun userPacket(profile: GattHandshakeProfile): ByteArray {
        val userId = profile.userId.coerceIn(1, 0xFFFF)
        val packet = byteArrayOf(
            HEADER.toByte(),
            0x12,
            SET_USER.toByte(),
            0x01,
            0x00,
            0x00,
            (userId ushr 8).toByte(),
            userId.toByte(),
            profile.heightCm.coerceIn(50, 250).toByte(),
            if (profile.male) 0x01 else 0x02,
            profile.age.coerceIn(18, 120).toByte(),
            0x55,
            0x5A,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
        )
        packet[packet.lastIndex] = xor(packet, 1, packet.lastIndex)
        return packet
    }

    fun timePacket(now: Long): ByteArray {
        val seconds = (now / 1000L).toInt()
        val packet = byteArrayOf(
            HEADER.toByte(),
            0x0D,
            SET_TIME.toByte(),
            (seconds ushr 24).toByte(),
            (seconds ushr 16).toByte(),
            (seconds ushr 8).toByte(),
            seconds.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        )
        packet[packet.lastIndex] = xor(packet, 1, packet.lastIndex)
        return packet
    }

    fun startPacket(): ByteArray = byteArrayOf(HEADER.toByte(), 0x05, START_MEASUREMENT.toByte(), 0x00, 0x16)

    fun parseFinal(data: ByteArray, expectedUserId: Int, identity: String): GattUpdate? {
        if (data.size < 18) return null
        if (data.u8(0) != HEADER || data.u8(3) != FINAL_FRAME) return null
        if ((xor(data, 1, data.lastIndex).toInt() and 0xFF) != data.u8(data.lastIndex)) return null
        val userId = (
            (data.u8(9) shl 24) or
                (data.u8(10) shl 16) or
                (data.u8(11) shl 8) or
                data.u8(12)
            )
        if (userId != expectedUserId.coerceIn(1, 0xFFFF)) return null
        val weightKg = u16beAt(data, 13) / 100.0
        if (!validScaleWeight(weightKg) || weightKg <= 0.0) return null
        val timestamp = (
            (data.u8(5) shl 24) or
                (data.u8(6) shl 16) or
                (data.u8(7) shl 8) or
                data.u8(8)
            )
        return GattUpdate(
            frame = ScaleFrame(
                identity = identity,
                kg = weightKg,
                state = 2,
                token = protocolToken(timestamp, weightKg.hashCode()),
                final = true,
            ),
        )
    }

    private fun xor(data: ByteArray, start: Int, endExclusive: Int): Byte {
        var value = 0
        for (index in start until endExclusive) value = value xor data.u8(index)
        return value.toByte()
    }
}
