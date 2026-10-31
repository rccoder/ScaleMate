package app.scalemate.core

import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HONOR/Huawei body-fat scale AH100 and CH100 wire protocol.
 *
 * This is an independent implementation of the documented frame layout and is
 * pinned in tests against a real CH100 capture. ScaleMate uses the weight from
 * the frame; fat and impedance remain available to the codec but are not saved
 * because the app's record model currently contains weight only.
 */
object HuaweiAhCh100GattProtocol : ScaleGattProtocol {
    const val ADAPTER = "huawei-ah100-ch100-gatt-v1"
    internal const val SERVICE = "0000faa0-0000-1000-8000-00805f9b34fb"
    internal const val WRITE = "0000faa1-0000-1000-8000-00805f9b34fb"
    internal const val NOTIFY = "0000faa2-0000-1000-8000-00805f9b34fb"

    override val adapterId: String = ADAPTER

    override fun identify(advertisement: ScaleAdvertisement): ScaleModel? {
        val cleaned = advertisement.name.orEmpty()
            .replace("\u0000", "")
            .trim()
            .uppercase(Locale.ROOT)
        val candidates = setOf(
            cleaned,
            cleaned.removePrefix("HUAWEI ").trim(),
            cleaned.removePrefix("HONOR ").trim(),
            cleaned.substringBefore('-').trim(),
            cleaned.substringBefore('_').trim(),
            cleaned.substringBefore(' ').trim(),
        )
        if ("CH100S" in candidates || "CH100S" in cleaned) return null
        return ScaleCatalog.honorBodyFatScaleAh100.takeIf { "AH100" in candidates || "CH100" in candidates }
    }

    override fun create(model: ScaleModel, address: String, profile: GattHandshakeProfile): ScaleGattCodec =
        HuaweiAhCh100GattCodec(model, address, profile)
}

private class HuaweiAhCh100GattCodec(
    override val model: ScaleModel,
    private val address: String,
    private val profile: GattHandshakeProfile,
) : ScaleGattCodec {
    private val mac = HuaweiAhCh100Wire.macBytes(address)
    private val authCode = HuaweiAhCh100Wire.authToken(profile.userId)
    private var magicKey: ByteArray? = null
    private var authAttempts = 0
    private var lastWeight: Double? = profile.previousWeightKg

    override fun onConnected(now: Long): GattUpdate = GattUpdate(
        operations = listOf(
            GattOperation(
                GattOperationKind.NOTIFY,
                HuaweiAhCh100GattProtocol.SERVICE,
                HuaweiAhCh100GattProtocol.NOTIFY,
            ),
        ),
    )

    override fun onNotification(characteristicUuid: String, data: ByteArray, now: Long): GattUpdate {
        if (!characteristicUuid.equals(HuaweiAhCh100GattProtocol.NOTIFY, ignoreCase = true) || data.size < 3) {
            return GattUpdate()
        }
        val op = data.u8(2)
        return when (op) {
            HuaweiAhCh100Wire.NOTIFY_WAKEUP -> GattUpdate(operations = listOf(plainCommand(HuaweiAhCh100Wire.CMD_AUTH, authCode)))
            HuaweiAhCh100Wire.NOTIFY_AUTH_RESULT -> handleAuth(data)
            HuaweiAhCh100Wire.NOTIFY_BIND_OK -> GattUpdate(
                operations = listOf(plainCommand(HuaweiAhCh100Wire.CMD_AUTH, authCode)),
            )
            HuaweiAhCh100Wire.NOTIFY_USER_CHANGED -> if (magicKey != null) {
                GattUpdate(operations = listOf(userInfoCommand()))
            } else {
                GattUpdate()
            }
            HuaweiAhCh100Wire.NOTIFY_MEASUREMENT,
            HuaweiAhCh100Wire.NOTIFY_HISTORY -> handleMeasurement(data)
            else -> GattUpdate()
        }
    }

    private fun handleAuth(frame: ByteArray): GattUpdate {
        val payload = HuaweiAhCh100Wire.deobfuscateTail(frame, mac)
        if (payload.isEmpty() || payload.u8(0) != 1) {
            authAttempts++
            val command = if (authAttempts < 3) {
                plainCommand(HuaweiAhCh100Wire.CMD_AUTH, authCode)
            } else {
                plainCommand(HuaweiAhCh100Wire.CMD_BIND, authCode)
            }
            return GattUpdate(operations = listOf(command))
        }
        magicKey = HuaweiAhCh100Wire.deriveMagicKey(authCode, mac)
        return GattUpdate(
            operations = listOf(
                plainCommand(HuaweiAhCh100Wire.CMD_SET_UNIT, byteArrayOf(0x01)),
                plainCommand(HuaweiAhCh100Wire.CMD_SET_TIME, HuaweiAhCh100Wire.timePayload()),
                userInfoCommand(),
                plainCommand(HuaweiAhCh100Wire.CMD_GET_VERSION, byteArrayOf()),
            ),
        )
    }

    private fun handleMeasurement(frame: ByteArray): GattUpdate {
        if (frame.u8(0) != HuaweiAhCh100Wire.FRAME_NOTIFY_ENCRYPTED) return GattUpdate()
        val key = magicKey ?: return GattUpdate()
        val measurement = runCatching {
            HuaweiAhCh100Wire.decodeMeasurement(frame, key, mac)
        }.getOrNull() ?: return GattUpdate()
        if (!validScaleWeight(measurement.weightKg) || measurement.weightKg <= 0.0) return GattUpdate()
        lastWeight = measurement.weightKg
        return GattUpdate(
            operations = listOf(plainCommand(HuaweiAhCh100Wire.CMD_FAT_ACK, byteArrayOf(0x00))),
            frame = ScaleFrame(
                identity = address,
                kg = measurement.weightKg,
                state = 2,
                token = protocolToken(
                    measurement.userId,
                    measurement.weightKg.hashCode(),
                    measurement.impedanceOhm,
                ),
                final = true,
            ),
        )
    }

    private fun userInfoCommand(): GattOperation {
        val key = magicKey ?: return GattOperation(
            GattOperationKind.DISCONNECT,
            HuaweiAhCh100GattProtocol.SERVICE,
            HuaweiAhCh100GattProtocol.WRITE,
        )
        val ageAndSex = profile.age.coerceIn(1, 120) or if (profile.male) 0 else 0x80
        val weightTenths = ((lastWeight ?: bmi22Weight(profile.heightCm)) * 10.0)
            .toInt()
            .coerceIn(1, 3000)
        val payload = authCode + byteArrayOf(
            ageAndSex.toByte(),
            profile.heightCm.coerceIn(50, 250).toByte(),
            0x00,
            (weightTenths and 0xFF).toByte(),
            ((weightTenths ushr 8) and 0xFF).toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0x1C,
            0xE2.toByte(),
        )
        return HuaweiAhCh100Wire.encryptedCommand(
            cmd = HuaweiAhCh100Wire.CMD_USER_INFO,
            payload = payload,
            key = key,
            mac = mac,
            explicitLength = 14,
        )
    }

    private fun bmi22Weight(heightCm: Int): Double {
        val metres = heightCm.coerceIn(100, 230) / 100.0
        return 22.0 * metres * metres
    }

    private fun plainCommand(cmd: Int, payload: ByteArray): GattOperation =
        HuaweiAhCh100Wire.plainCommand(
            cmd = cmd,
            payload = payload,
            mac = mac,
            service = HuaweiAhCh100GattProtocol.SERVICE,
            characteristic = HuaweiAhCh100GattProtocol.WRITE,
        )
}

internal object HuaweiAhCh100Wire {
    private val initialKey = hex("3DA2784AFB87B12A980FDE3456732156")
    private val initialIv = hex("4EF764322FDA7632123DEB8790FEA219")

    const val FRAME_PLAIN = 0xDB
    const val FRAME_ENCRYPTED = 0xDC
    const val FRAME_NOTIFY_ENCRYPTED = 0xBC

    const val NOTIFY_WAKEUP = 0x00
    const val NOTIFY_MEASUREMENT = 0x0E
    const val NOTIFY_HISTORY = 0x10
    const val NOTIFY_USER_CHANGED = 0x20
    const val NOTIFY_AUTH_RESULT = 0x26
    const val NOTIFY_BIND_OK = 0x27

    const val CMD_SET_UNIT = 0x02
    const val CMD_SET_TIME = 0x08
    const val CMD_USER_INFO = 0x09
    const val CMD_GET_VERSION = 0x0C
    const val CMD_FAT_ACK = 0x13
    const val CMD_AUTH = 0x24
    const val CMD_BIND = 0x25

    fun authToken(userId: Int): ByteArray {
        val token = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x00, (userId and 0xFF).toByte())
        token[5] = xor(token)
        return token
    }

    fun deriveMagicKey(authCode: ByteArray, mac: ByteArray): ByteArray {
        require(authCode.size == 7)
        return xor(authCode, mac) + initialKey.copyOfRange(7, 16)
    }

    fun timePayload(calendar: Calendar = Calendar.getInstance()): ByteArray {
        val year = calendar.get(Calendar.YEAR)
        val mondayBasedDay = ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        return byteArrayOf(
            (year and 0xFF).toByte(),
            ((year ushr 8) and 0xFF).toByte(),
            (calendar.get(Calendar.MONTH) + 1).toByte(),
            calendar.get(Calendar.DAY_OF_MONTH).toByte(),
            calendar.get(Calendar.HOUR_OF_DAY).toByte(),
            calendar.get(Calendar.MINUTE).toByte(),
            calendar.get(Calendar.SECOND).toByte(),
            mondayBasedDay.toByte(),
        )
    }

    fun plainCommand(
        cmd: Int,
        payload: ByteArray,
        mac: ByteArray,
        service: String = HuaweiAhCh100GattProtocol.SERVICE,
        characteristic: String = HuaweiAhCh100GattProtocol.WRITE,
    ): GattOperation = GattOperation(
        GattOperationKind.WRITE,
        service,
        characteristic,
        byteArrayOf(FRAME_PLAIN.toByte(), (payload.size + 1).toByte(), cmd.toByte()) +
            xor(payload, mac),
    )

    fun encryptedCommand(
        cmd: Int,
        payload: ByteArray,
        key: ByteArray,
        mac: ByteArray,
        explicitLength: Int = payload.size,
    ): GattOperation = GattOperation(
        GattOperationKind.WRITE,
        HuaweiAhCh100GattProtocol.SERVICE,
        HuaweiAhCh100GattProtocol.WRITE,
        byteArrayOf(FRAME_ENCRYPTED.toByte(), explicitLength.toByte(), cmd.toByte()) +
            xor(aesCtr(payload, key), mac),
    )

    fun decodeMeasurement(frame: ByteArray, key: ByteArray, mac: ByteArray): HuaweiMeasurement {
        val deobfuscated = deobfuscateTail(frame, mac)
        val plain = aesCtr(deobfuscated, key)
        require(plain.size >= 15)
        val userId = plain.u8(0)
        val weightTenths = u16le(plain, 1)
        val fatTenths = u16le(plain, 3)
        val impedance = u16le(plain, 13)
        return HuaweiMeasurement(userId, weightTenths / 10.0, fatTenths / 10.0, impedance)
    }

    fun deobfuscateTail(frame: ByteArray, mac: ByteArray): ByteArray =
        if (frame.size <= 3) byteArrayOf() else xor(frame.copyOfRange(3, frame.size), mac)

    fun macBytes(address: String): ByteArray {
        val clean = address.replace(":", "").replace("-", "")
        if (clean.length != 12) return ByteArray(6)
        return runCatching {
            ByteArray(6) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }.getOrElse { ByteArray(6) }
    }

    private fun xor(data: ByteArray, mac: ByteArray): ByteArray {
        val output = data.copyOf()
        if (mac.isEmpty()) return output
        for (index in output.indices) {
            output[index] = (output[index].toInt() xor mac[index % mac.size].toInt()).toByte()
        }
        return output
    }

    private fun xor(data: ByteArray): Byte {
        var value = 0
        data.forEach { value = value xor it.toInt() }
        return value.toByte()
    }

    private fun aesCtr(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(initialIv))
        return cipher.doFinal(data)
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}

internal data class HuaweiMeasurement(
    val userId: Int,
    val weightKg: Double,
    val fatPercent: Double,
    val impedanceOhm: Int,
)
