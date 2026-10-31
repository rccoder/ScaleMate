package app.scalemate.core

enum class ScaleConnection { ADVERTISEMENT, GATT }

enum class GattOperationKind { NOTIFY, WRITE, DISCONNECT }

/** Android-free GATT command so protocol codecs remain fully unit-testable. */
data class GattOperation(
    val kind: GattOperationKind,
    val serviceUuid: String,
    val characteristicUuid: String,
    val payload: ByteArray = byteArrayOf(),
    val withResponse: Boolean = true,
)

data class GattUpdate(
    val operations: List<GattOperation> = emptyList(),
    val frame: ScaleFrame? = null,
)

/**
 * The current app has no age/sex/height profile. These neutral values are only
 * used for protocols that require a syntactically valid user record before they
 * stream weight. ScaleMate stores weight only and does not claim body composition.
 */
data class GattHandshakeProfile(
    val userId: Int = 1,
    val heightCm: Int = 170,
    val age: Int = 30,
    val male: Boolean = true,
    val previousWeightKg: Double? = null,
)

interface ScaleGattCodec {
    val model: ScaleModel
    fun onConnected(now: Long): GattUpdate
    fun onNotification(characteristicUuid: String, data: ByteArray, now: Long): GattUpdate
    fun onDisconnected(now: Long): GattUpdate = GattUpdate()
    fun periodicOperation(): GattOperation? = null
}

interface ScaleGattProtocol {
    val adapterId: String
    fun identify(advertisement: ScaleAdvertisement): ScaleModel?
    fun create(model: ScaleModel, address: String, profile: GattHandshakeProfile): ScaleGattCodec?
}

object ScaleGattProtocols {
    val all: List<ScaleGattProtocol> = listOf(
        XiaomiGattProtocol,
        PicoocGattProtocol,
        HuaweiAhCh100GattProtocol,
        RealmeGattProtocol,
        YunmaiGattProtocol,
    )
}

internal fun String.normalizedUuid(): String = lowercase()

internal fun u16le(data: ByteArray, offset: Int): Int =
    data.u8(offset) or (data.u8(offset + 1) shl 8)

internal fun u16beAt(data: ByteArray, offset: Int): Int =
    (data.u8(offset) shl 8) or data.u8(offset + 1)

internal fun ByteArray.le16(offset: Int): ByteArray =
    byteArrayOf(this[offset], this[offset + 1])
