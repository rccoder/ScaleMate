package app.scalemate.core

import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** realme Smart Scale RMH2011 (Lifesense A6 lineage). */
object RealmeGattProtocol : ScaleGattProtocol {
    const val ADAPTER = "realme-rmh2011-gatt-v1"
    internal const val SERVICE = "0000a602-0000-1000-8000-00805f9b34fb"
    internal const val NOTIFY_MEASUREMENT = "0000a621-0000-1000-8000-00805f9b34fb"
    internal const val WRITE_KEEPALIVE = "0000a622-0000-1000-8000-00805f9b34fb"
    internal const val WRITE_HANDSHAKE = "0000a624-0000-1000-8000-00805f9b34fb"
    internal const val NOTIFY_EXTRA = "0000a625-0000-1000-8000-00805f9b34fb"

    override val adapterId: String = ADAPTER

    override fun identify(advertisement: ScaleAdvertisement): ScaleModel? {
        val name = advertisement.name.orEmpty().lowercase(Locale.ROOT)
        val hasService = advertisement.serviceUuids.any { it.equals(SERVICE, ignoreCase = true) }
        return ScaleCatalog.realmeSmartScale.takeIf { name.contains("realme") || hasService }
    }

    override fun create(model: ScaleModel, address: String, profile: GattHandshakeProfile): ScaleGattCodec =
        RealmeGattCodec(model, address, profile)
}

private class RealmeGattCodec(
    override val model: ScaleModel,
    private val address: String,
    private val profile: GattHandshakeProfile,
) : ScaleGattCodec {
    private val mac = RealmeWire.macBytes(address)

    override fun onConnected(now: Long): GattUpdate {
        val commands = RealmeWire.handshake(now, profile, mac)
            .map { payload ->
                GattOperation(
                    GattOperationKind.WRITE,
                    RealmeGattProtocol.SERVICE,
                    RealmeGattProtocol.WRITE_HANDSHAKE,
                    payload,
                    withResponse = false,
                )
            }
        return GattUpdate(
            operations = listOf(
                GattOperation(
                    GattOperationKind.NOTIFY,
                    RealmeGattProtocol.SERVICE,
                    RealmeGattProtocol.NOTIFY_MEASUREMENT,
                ),
                GattOperation(
                    GattOperationKind.NOTIFY,
                    RealmeGattProtocol.SERVICE,
                    RealmeGattProtocol.NOTIFY_EXTRA,
                ),
            ) + commands,
        )
    }

    override fun onNotification(characteristicUuid: String, data: ByteArray, now: Long): GattUpdate {
        if (!characteristicUuid.equals(RealmeGattProtocol.NOTIFY_MEASUREMENT, ignoreCase = true)) {
            return GattUpdate()
        }
        if (data.size < 19 || data.u8(0) != 0x10 || data.u8(1) != 0x11) return GattUpdate()
        val payload = RealmeWire.deobfuscate(data, mac)
        val kg = u16beAt(payload, 8) / 100.0
        if (!validScaleWeight(kg) || kg <= 0.0) return GattUpdate()
        return GattUpdate(
            frame = ScaleFrame(
                identity = address,
                kg = kg,
                state = 2,
                token = protocolToken(payload.contentHashCode(), kg.hashCode()),
                final = true,
            ),
        )
    }

    override fun periodicOperation(): GattOperation = GattOperation(
        GattOperationKind.WRITE,
        RealmeGattProtocol.SERVICE,
        RealmeGattProtocol.WRITE_KEEPALIVE,
        byteArrayOf(0x00, 0x01, 0xD9.toByte()),
        withResponse = false,
    )
}

internal object RealmeWire {
    fun handshake(now: Long, profile: GattHandshakeProfile, mac: ByteArray): List<ByteArray> {
        val timestamp = (now / 1000L).toInt()
        val timezoneMinutes = (TimeZone.getDefault().getOffset(now) / 60_000).toByte()
        val height = profile.heightCm.coerceIn(50, 250)
        val sex = if (profile.male) 0x00 else 0x80
        val weight = profile.previousWeightKg
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.times(100.0)
            ?.roundToInt()
            ?.coerceIn(1, 0xFFFE)
            ?: 0xFFFF
        val payloads = listOf(
            byteArrayOf(0x00, 0x08, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02),
            byteArrayOf(
                0x00, 0x0A, 0x18,
                (timestamp ushr 24).toByte(),
                (timestamp ushr 16).toByte(),
                (timestamp ushr 8).toByte(),
                timestamp.toByte(),
                timezoneMinutes,
            ),
            byteArrayOf(0x48, 0x01, 0x00, 0x01),
            byteArrayOf(
                0x10, 0x01, 0x01, sex.toByte(), profile.age.coerceIn(1, 120).toByte(),
                (height ushr 8).toByte(), height.toByte(), 0x00, 0x00,
                (weight ushr 8).toByte(), weight.toByte(),
            ),
            byteArrayOf(0x10, 0x04, 0x00),
            byteArrayOf(0x10, 0x07, 0x01),
        )
        return payloads.map { wrap(it, mac) }
    }

    fun deobfuscate(frame: ByteArray, mac: ByteArray): ByteArray {
        if (frame.size <= 2) return byteArrayOf()
        return ByteArray(frame.size - 2) { index ->
            (frame[index + 2].toInt() xor mac[index % mac.size].toInt()).toByte()
        }
    }

    fun macBytes(address: String): ByteArray {
        val clean = address.replace(":", "").replace("-", "")
        if (clean.length != 12) return ByteArray(6)
        return runCatching {
            ByteArray(6) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }.getOrElse { ByteArray(6) }
    }

    private fun wrap(payload: ByteArray, mac: ByteArray): ByteArray =
        ByteArray(payload.size + 2).also { output ->
            output[0] = 0x10
            output[1] = payload.size.toByte()
            for (index in payload.indices) {
                output[index + 2] = (payload[index].toInt() xor mac[index % mac.size].toInt()).toByte()
            }
        }
}
