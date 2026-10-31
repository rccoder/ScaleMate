package app.scalemate.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.scalemate.core.GattOperation
import app.scalemate.core.GattOperationKind
import app.scalemate.core.GattUpdate
import app.scalemate.core.ScaleAdvertisement
import app.scalemate.core.ScaleConnection
import app.scalemate.core.ScaleFrame
import app.scalemate.core.ScaleModel
import app.scalemate.core.ScaleProtocols
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
class BleTransport(private val context: Context) : ScaleTransport {
    private enum class Mode { IDLE, SCAN, GATT }

    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var active = false
    private var mode = Mode.IDLE

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private var gatt: BluetoothGatt? = null
    private var gattCodec: app.scalemate.core.ScaleGattCodec? = null
    private var receivedFrame: (ScaleFrame, Long) -> Unit = { _, _ -> }
    private var failureCallback: (Int) -> Unit = {}
    private var receivedAnyFrame = false
    private var servicesReady = false

    private val operations = ArrayDeque<GattOperation>()
    private var currentOperation: GattOperation? = null
    private var operationTimeout: Runnable? = null
    private var periodicRunnable: Runnable? = null
    private var connectionTimeout: Runnable? = null

    override val requiresBluetoothPermission: Boolean = true

    override fun available(): Boolean =
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

    override fun discover(found: (ScaleDiscovery) -> Unit, failure: (Int) -> Unit) {
        stop()
        val token = generation
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            failure(-1)
            return
        }
        val activeScanner = adapter.bluetoothLeScanner ?: run {
            failure(-1)
            return
        }
        active = true
        mode = Mode.SCAN
        failureCallback = failure
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post {
                    if (!isCurrent(token, Mode.SCAN)) return@post
                    val advertisement = result.advertisement() ?: return@post
                    val model = ScaleProtocols.identify(advertisement) ?: return@post
                    found(ScaleDiscovery(advertisement.address, model, result.rssi))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post {
                    if (isCurrent(token, Mode.SCAN)) finishActive(errorCode)
                }
            }
        }
        scanner = activeScanner
        scanCallback = callback
        try {
            activeScanner.startScan(
                emptyList(),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (_: SecurityException) {
            finishActive(-2)
        } catch (_: IllegalStateException) {
            finishActive(-1)
        }
    }

    override fun start(
        address: String,
        model: ScaleModel,
        received: (ScaleFrame, Long) -> Unit,
        failure: (Int) -> Unit,
    ) {
        stop()
        val token = generation
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            failure(-1)
            return
        }
        active = true
        receivedFrame = received
        failureCallback = failure
        receivedAnyFrame = false
        when (model.connection) {
            ScaleConnection.ADVERTISEMENT -> startAdvertisement(token, address, model)
            ScaleConnection.GATT -> startGatt(token, address, model)
        }
    }

    private fun startAdvertisement(token: Int, address: String, model: ScaleModel) {
        mode = Mode.SCAN
        val activeScanner = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.bluetoothLeScanner ?: run {
            finishActive(-1)
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!isCurrent(token, Mode.SCAN)) return
                if (!result.device.address.equals(address, ignoreCase = true)) return
                val advertisement = result.advertisement() ?: return
                val detected = ScaleProtocols.parse(advertisement, model.adapterId) ?: return
                if (detected.model.id != model.id) return
                main.post {
                    if (isCurrent(token, Mode.SCAN)) {
                        receivedAnyFrame = true
                        receivedFrame(detected.frame, android.os.SystemClock.elapsedRealtime())
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post {
                    if (isCurrent(token, Mode.SCAN)) finishActive(errorCode)
                }
            }
        }
        scanner = activeScanner
        scanCallback = callback
        try {
            activeScanner.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (_: SecurityException) {
            finishActive(-2)
        } catch (_: IllegalStateException) {
            finishActive(-1)
        }
    }

    private fun startGatt(token: Int, address: String, model: ScaleModel) {
        mode = Mode.GATT
        val protocol = ScaleProtocols.gattFor(model.adapterId)
        val codec = protocol?.create(
            model = model,
            address = address,
            profile = app.scalemate.core.GattHandshakeProfile(),
        )
        if (codec == null) {
            finishActive(-3)
            return
        }
        gattCodec = codec
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                callbackGatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                main.post {
                    if (!isCurrent(token, Mode.GATT) || callbackGatt !== gatt) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finishActive(-1)
                    } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (!callbackGatt.discoverServices()) finishActive(-1)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        handleGattDisconnected()
                    }
                }
            }

            override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
                main.post {
                    if (!isCurrent(token, Mode.GATT) || callbackGatt !== gatt) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finishActive(-1)
                        return@post
                    }
                    cancelConnectionTimeout()
                    servicesReady = true
                    val update = runCatching {
                        gattCodec?.onConnected(System.currentTimeMillis()) ?: GattUpdate()
                    }.getOrElse {
                        finishActive(-3)
                        return@post
                    }
                    applyUpdate(update)
                }
            }

            override fun onCharacteristicChanged(
                callbackGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                main.post {
                    if (!isCurrent(token, Mode.GATT) || callbackGatt !== gatt) return@post
                    val update = runCatching {
                        gattCodec?.onNotification(
                            characteristic.uuid.toString(),
                            value.copyOf(),
                            System.currentTimeMillis(),
                        ) ?: GattUpdate()
                    }.getOrElse { GattUpdate() }
                    applyUpdate(update)
                }
            }

            override fun onCharacteristicWrite(
                callbackGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                main.post {
                    if (!isCurrent(token, Mode.GATT) || callbackGatt !== gatt) return@post
                    val operation = currentOperation
                    if (operation?.kind == GattOperationKind.WRITE &&
                        operation.characteristicUuid.equals(
                            characteristic.uuid.toString(),
                            ignoreCase = true,
                        )
                    ) {
                        completeOperation(status == BluetoothGatt.GATT_SUCCESS)
                    }
                }
            }

            override fun onDescriptorWrite(
                callbackGatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                main.post {
                    if (!isCurrent(token, Mode.GATT) || callbackGatt !== gatt) return@post
                    if (currentOperation?.kind == GattOperationKind.NOTIFY &&
                        descriptor.uuid == CCCD_UUID
                    ) {
                        completeOperation(status == BluetoothGatt.GATT_SUCCESS)
                    }
                }
            }
        }
        try {
            val device = context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.getRemoteDevice(address)
                ?: run {
                    finishActive(-1)
                    return
                }
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                finishActive(-1)
                return
            }
        } catch (_: SecurityException) {
            finishActive(-2)
            return
        } catch (_: IllegalArgumentException) {
            finishActive(-1)
            return
        }
        connectionTimeout = Runnable {
            if (isCurrent(token, Mode.GATT)) finishActive(-1)
        }.also { main.postDelayed(it, CONNECTION_TIMEOUT_MS) }
    }

    private fun handleGattDisconnected() {
        if (!active || mode != Mode.GATT) return
        val update = runCatching {
            gattCodec?.onDisconnected(System.currentTimeMillis()) ?: GattUpdate()
        }.getOrElse { GattUpdate() }
        update.frame?.let {
            receivedAnyFrame = true
            receivedFrame(it, android.os.SystemClock.elapsedRealtime())
        }
        if (receivedAnyFrame) finishActive(null) else finishActive(-1)
    }

    private fun applyUpdate(update: GattUpdate) {
        update.frame?.let {
            receivedAnyFrame = true
            receivedFrame(it, android.os.SystemClock.elapsedRealtime())
        }
        if (update.operations.isNotEmpty()) operations.addAll(update.operations)
        runNextOperation()
    }

    private fun runNextOperation() {
        if (!active || mode != Mode.GATT || !servicesReady || currentOperation != null) return
        val operation = operations.pollFirst()
        if (operation == null) {
            armPeriodicOperation()
            return
        }
        currentOperation = operation
        val success = when (operation.kind) {
            GattOperationKind.NOTIFY -> startNotification(operation)
            GattOperationKind.WRITE -> writeCharacteristic(operation)
            GattOperationKind.DISCONNECT -> disconnectGatt()
        }
        if (!success) {
            completeOperation(false)
            return
        }
        if (operation.kind == GattOperationKind.WRITE && !operation.withResponse) {
            completeOperation(true)
            return
        }
        operationTimeout = Runnable {
            if (currentOperation === operation) completeOperation(false)
        }.also { main.postDelayed(it, OPERATION_TIMEOUT_MS) }
    }

    private fun startNotification(operation: GattOperation): Boolean {
        val activeGatt = gatt ?: return false
        val characteristic = findCharacteristic(operation) ?: return false
        if (!activeGatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return false
        val value =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
        return activeGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    }

    private fun writeCharacteristic(operation: GattOperation): Boolean {
        val characteristic = findCharacteristic(operation) ?: return false
        val writeType = if (operation.withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return gatt?.writeCharacteristic(characteristic, operation.payload, writeType) ==
            BluetoothStatusCodes.SUCCESS
    }

    private fun disconnectGatt(): Boolean {
        val activeGatt = gatt ?: return false
        return runCatching { activeGatt.disconnect() }.isSuccess
    }

    private fun findCharacteristic(operation: GattOperation): BluetoothGattCharacteristic? {
        val service = gatt?.getService(UUID.fromString(operation.serviceUuid.lowercase(Locale.ROOT)))
            ?: return null
        return service.getCharacteristic(
            UUID.fromString(operation.characteristicUuid.lowercase(Locale.ROOT)),
        )
    }

    private fun completeOperation(success: Boolean) {
        operationTimeout?.let(main::removeCallbacks)
        operationTimeout = null
        currentOperation = null
        if (!success) {
            finishActive(-3)
            return
        }
        if (mode == Mode.GATT && servicesReady) {
            main.postDelayed({ runNextOperation() }, OPERATION_GAP_MS)
        }
    }

    private fun armPeriodicOperation() {
        if (!active || mode != Mode.GATT || !servicesReady || periodicRunnable != null) return
        val codec = gattCodec ?: return
        if (codec.periodicOperation() == null) return
        val runnable = Runnable {
            periodicRunnable = null
            if (!active || mode != Mode.GATT || !servicesReady) return@Runnable
            val operation = codec.periodicOperation() ?: return@Runnable
            operations.addLast(operation)
            runNextOperation()
        }
        periodicRunnable = runnable
        main.postDelayed(runnable, PERIODIC_INTERVAL_MS)
    }

    override fun stop() {
        generation++
        active = false
        mode = Mode.IDLE
        cleanupScan()
        cleanupGatt()
        failureCallback = {}
        receivedFrame = { _, _ -> }
    }

    private fun finishActive(failureCode: Int?) {
        if (!active) return
        val failure = failureCallback
        generation++
        active = false
        mode = Mode.IDLE
        cleanupScan()
        cleanupGatt()
        failureCallback = {}
        receivedFrame = { _, _ -> }
        if (failureCode != null) failure(failureCode)
    }

    private fun cleanupScan() {
        val callback = scanCallback
        val activeScanner = scanner
        if (callback != null && activeScanner != null) {
            runCatching { activeScanner.stopScan(callback) }
        }
        scanCallback = null
        scanner = null
    }

    private fun cleanupGatt() {
        cancelConnectionTimeout()
        operationTimeout?.let(main::removeCallbacks)
        operationTimeout = null
        periodicRunnable?.let(main::removeCallbacks)
        periodicRunnable = null
        operations.clear()
        currentOperation = null
        servicesReady = false
        gattCodec = null
        val activeGatt = gatt
        gatt = null
        if (activeGatt != null) {
            runCatching { activeGatt.disconnect() }
            runCatching { activeGatt.close() }
        }
    }

    private fun cancelConnectionTimeout() {
        connectionTimeout?.let(main::removeCallbacks)
        connectionTimeout = null
    }

    private fun isCurrent(token: Int, expectedMode: Mode): Boolean =
        active && generation == token && mode == expectedMode

    private fun ScanResult.advertisement(): ScaleAdvertisement? {
        val record = scanRecord ?: return null
        val manufacturer = buildMap {
            record.manufacturerSpecificData?.let { data ->
                for (index in 0 until data.size()) put(data.keyAt(index), data.valueAt(index))
            }
        }
        val serviceData = record.serviceData.orEmpty().mapKeys { (uuid, _) ->
            uuid.uuid.toString().lowercase(Locale.ROOT)
        }
        val services = record.serviceUuids.orEmpty().mapTo(mutableSetOf()) {
            it.uuid.toString().lowercase(Locale.ROOT)
        }
        val name = record.deviceName ?: device.name
        return ScaleAdvertisement(
            address = device.address,
            name = name,
            manufacturerData = manufacturer,
            serviceData = serviceData,
            serviceUuids = services,
        )
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val OPERATION_TIMEOUT_MS = 8_000L
        private const val OPERATION_GAP_MS = 50L
        private const val PERIODIC_INTERVAL_MS = 1_000L
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
