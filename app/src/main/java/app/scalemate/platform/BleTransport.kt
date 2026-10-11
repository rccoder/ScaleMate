package app.scalemate.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.scalemate.core.*

class BleTransport(private val context: Context) {
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    @SuppressLint("MissingPermission")
    fun available(): Boolean = try {context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true} catch(_: SecurityException){false}
    @SuppressLint("MissingPermission")
    fun start(address: String?, received: (ScaleFrame, Long, Int) -> Unit, failure: (Int) -> Unit) {
        stop(); val token = generation
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if(adapter == null || !adapter.isEnabled) { failure(-1);return }
        val s = adapter.bluetoothLeScanner ?: run { failure(-1);return }
        val cb = object: ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                if(token != generation) return
                try {
                    val a = result.device.address
                    if(address != null && address != a) return
                    val data = result.scanRecord?.manufacturerSpecificData ?: return
                    for(i in 0 until data.size()) {
                        val f = YunmaiM263.parse(a,data.keyAt(i),data.valueAt(i))
                        if(f != null) main.post { if(token==generation) received(f,result.timestampNanos/1_000_000,result.rssi) }
                        // A bound device may also broadcast idle or unrelated payloads.
                        // Ignore unvalidated frames; only validated samples may refresh a session.
                    }
                } catch(_: SecurityException) { main.post { if(token==generation) failure(-2) } }
            }
            override fun onScanFailed(code: Int) { main.post { if(token==generation) failure(code) } }
        }
        scanner=s; callback=cb
        try { s.startScan(if(address==null) emptyList() else listOf(ScanFilter.Builder().setDeviceAddress(address).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),cb) }
        catch(_: SecurityException) { stop();failure(-2) }
        catch(_: IllegalStateException) { stop();failure(-1) }
    }
    @SuppressLint("MissingPermission")
    fun stop() { generation++; try { callback?.let { scanner?.stopScan(it) } } catch(_: Exception) { };callback=null;scanner=null }
}
