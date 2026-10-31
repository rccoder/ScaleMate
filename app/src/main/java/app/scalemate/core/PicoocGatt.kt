package app.scalemate.core

import java.util.Locale
import kotlin.math.roundToInt

/**
 * PICOOC's connection-oriented line (Latin, C/CC/CQ, S3 Lite V2 and related
 * models). The scale advertises PICOOC-L for its broadcast-only Mini family;
 * those stay on [PicoocMiniLiteBroadcast].
 */
object PicoocGattProtocol : ScaleGattProtocol {
    const val ADAPTER = "picooc-connection-gatt-v1"
    internal const val SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb"
    internal const val NOTIFY = "0000fff1-0000-1000-8000-00805f9b34fb"
    internal const val WRITE = "0000fff2-0000-1000-8000-00805f9b34fb"

    override val adapterId: String = ADAPTER

    override fun identify(advertisement: ScaleAdvertisement): ScaleModel? {
        val name = advertisement.name.orEmpty().trim().lowercase(Locale.ROOT)
        if (name.startsWith("latin-")) return ScaleCatalog.picoocGatt
        if (!name.startsWith("picooc")) return null
        if (name.startsWith("picooc-l")) return null
        return ScaleCatalog.picoocGatt
    }

    override fun create(model: ScaleModel, address: String, profile: GattHandshakeProfile): ScaleGattCodec =
        PicoocGattCodec(model, address, profile)
}

private class PicoocGattCodec(
    override val model: ScaleModel,
    private val address: String,
    private val profile: GattHandshakeProfile,
) : ScaleGattCodec {
    private var prefix = PicoocGattWire.PREFIX_LATIN
    private var lastWeight: Double? = null
    private var lastToken: Int = 0
    private var completed = false

    override fun onConnected(now: Long): GattUpdate = GattUpdate(
        operations = listOf(
            GattOperation(GattOperationKind.NOTIFY, PicoocGattProtocol.SERVICE, PicoocGattProtocol.NOTIFY),
        ),
    )

    override fun onNotification(characteristicUuid: String, data: ByteArray, now: Long): GattUpdate {
        if (!characteristicUuid.equals(PicoocGattProtocol.NOTIFY, ignoreCase = true) || data.isEmpty()) {
            return GattUpdate()
        }
        val opcode = data.u8(0)
        return when (opcode) {
            PicoocGattWire.OP_HANDSHAKE,
            PicoocGattWire.OP_TIME_SYNC,
            PicoocGattWire.OP_HANDSHAKE_ALT -> {
                if (opcode == PicoocGattWire.OP_HANDSHAKE_ALT) prefix = PicoocGattWire.PREFIX_MODERN
                handshake(now / 1000L)
            }
            PicoocGattWire.OP_USER_REQUEST -> GattUpdate(
                operations = listOf(
                    write(ack(opcode)),
                    write(userProfile()),
                ),
            )
            PicoocGattWire.OP_LIVE_WEIGHT,
            PicoocGattWire.OP_LIVE_WEIGHT_ALT -> {
                if (opcode == PicoocGattWire.OP_LIVE_WEIGHT_ALT) prefix = PicoocGattWire.PREFIX_MODERN
                val parsed = PicoocLiveFrame.parse(data) ?: return GattUpdate(operations = listOf(write(ack(opcode))))
                lastWeight = parsed.weightKg
                lastToken = protocolToken(parsed.timestampHash, parsed.weightKg.hashCode())
                completed = parsed.complete
                GattUpdate(
                    operations = listOf(write(ack(opcode))),
                    frame = frame(parsed.weightKg, parsed.complete, lastToken),
                )
            }
            PicoocGattWire.OP_BODY_COMPOSITION -> {
                val kg = PicoocLiveFrame.parseCompositionWeight(data)
                if (kg != null && !completed) {
                    lastWeight = kg
                    lastToken = protocolToken(data.contentHashCode(), kg.hashCode())
                    completed = true
                }
                GattUpdate(operations = listOf(write(ack(opcode))))
            }
            PicoocGattWire.OP_HEART_RATE,
            PicoocGattWire.OP_HISTORY,
            PicoocGattWire.OP_HISTORY_DONE,
            PicoocGattWire.OP_MULTI_FREQ,
            PicoocGattWire.OP_BALANCE -> GattUpdate(operations = listOf(write(ack(opcode))))
            else -> GattUpdate()
        }
    }

    override fun onDisconnected(now: Long): GattUpdate {
        val kg = lastWeight ?: return GattUpdate()
        return if (completed) GattUpdate() else {
            completed = true
            GattUpdate(frame = frame(kg, true, lastToken))
        }
    }

    private fun handshake(epochSeconds: Long): GattUpdate = GattUpdate(
        operations = listOf(
            write(timeReply(epochSeconds)),
            write(userProfile()),
        ),
    )

    private fun frame(kg: Double, final: Boolean, token: Int): ScaleFrame? =
        if (validScaleWeight(kg) && kg > 0.0) {
            ScaleFrame(address, kg, 2, token, final)
        } else {
            null
        }

    private fun write(payload: ByteArray): GattOperation =
        GattOperation(GattOperationKind.WRITE, PicoocGattProtocol.SERVICE, PicoocGattProtocol.WRITE, payload)

    private fun ack(opcode: Int): ByteArray =
        byteArrayOf(prefix.toByte(), 0x03, opcode.toByte())

    private fun timeReply(epochSeconds: Long): ByteArray {
        val seconds = epochSeconds.toInt()
        return byteArrayOf(
            prefix.toByte(),
            0x0A,
            PicoocGattWire.OP_HANDSHAKE.toByte(),
            (seconds ushr 24).toByte(),
            (seconds ushr 16).toByte(),
            (seconds ushr 8).toByte(),
            seconds.toByte(),
            0x00,
            0x00,
            0x00,
        )
    }

    private fun userProfile(): ByteArray {
        val height = ((profile.heightCm * 10f - 1000f) / 5f).roundToInt().coerceIn(0, 255)
        return byteArrayOf(
            0x31,
            0x06,
            0x01,
            if (profile.male) 0x01 else 0x02,
            height.toByte(),
            profile.age.coerceIn(18, 255).toByte(),
        )
    }
}

internal object PicoocGattWire {
    const val PREFIX_LATIN = 0xF1
    const val PREFIX_MODERN = 0xA1
    const val OP_USER_REQUEST = 0x30
    const val OP_BODY_COMPOSITION = 0x32
    const val OP_TIME_SYNC = 0x35
    const val OP_HISTORY = 0x36
    const val OP_HISTORY_DONE = 0x37
    const val OP_LIVE_WEIGHT = 0x39
    const val OP_HANDSHAKE = 0x3A
    const val OP_HEART_RATE = 0x3C
    const val OP_MULTI_FREQ = 0x3E
    const val OP_BALANCE = 0x3F
    const val OP_HANDSHAKE_ALT = 0x51
    const val OP_LIVE_WEIGHT_ALT = 0x52
}

internal data class PicoocLiveFrame(
    val weightKg: Double,
    val complete: Boolean,
    val timestampHash: Int,
) {
    companion object {
        fun parse(data: ByteArray): PicoocLiveFrame? {
            if (data.size < 10) return null
            val declared = data.u8(1)
            val timestampHash = (
                (data.u8(2) shl 24) or
                    (data.u8(3) shl 16) or
                    (data.u8(4) shl 8) or
                    data.u8(5)
                )
            val weight = u16beAt(data, 6) / 20.0
            if (!validScaleWeight(weight) || weight <= 0.0) return null
            return when {
                declared == 0x0A -> PicoocLiveFrame(weight, true, timestampHash)
                declared == 0x0D && data.size >= 13 -> PicoocLiveFrame(weight, true, timestampHash)
                data.size >= 16 -> PicoocLiveFrame(weight, data.u8(15) == 0, timestampHash)
                else -> null
            }
        }

        fun parseCompositionWeight(data: ByteArray): Double? {
            if (data.size < 4) return null
            val kg = u16beAt(data, 2) / 20.0
            return kg.takeIf { validScaleWeight(it) && it > 0.0 }
        }
    }
}
