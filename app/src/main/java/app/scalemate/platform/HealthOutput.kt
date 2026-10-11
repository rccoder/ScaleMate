package app.scalemate.platform

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.*
import android.health.connect.datatypes.*
import android.health.connect.datatypes.units.Mass
import android.os.OutcomeReceiver
import app.scalemate.data.Measurement
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HealthOutput(private val context: Context) {
    companion object { const val WRITE = "android.permission.health.WRITE_WEIGHT" }
    val available get() = context.getSystemService(HealthConnectManager::class.java) != null
    val permitted get() = context.checkSelfPermission(WRITE) == PackageManager.PERMISSION_GRANTED
    private fun manager() = context.getSystemService(HealthConnectManager::class.java) ?: error("Health Connect unavailable")
    suspend fun write(m: Measurement): String = suspendCancellableCoroutine { continuation ->
        try {
            val metadata = Metadata.Builder().setClientRecordId(m.id).setClientRecordVersion(1)
                .setRecordingMethod(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED)
                .setDevice(Device.Builder().setManufacturer("YUNMAI").setModel("YMBS-M263").setType(Device.DEVICE_TYPE_SCALE).build()).build()
            val record = WeightRecord.Builder(metadata, Instant.ofEpochMilli(m.time), Mass.fromGrams(m.kg * 1000)).setZoneOffset(ZoneOffset.ofTotalSeconds(m.offset)).build()
            manager().insertRecords(listOf(record),context.mainExecutor,object: OutcomeReceiver<InsertRecordsResponse,HealthConnectException> {
                override fun onResult(result: InsertRecordsResponse) { if(continuation.isActive) continuation.resume(result.records.first().metadata.id) }
                override fun onError(error: HealthConnectException) { if(continuation.isActive) continuation.resumeWithException(error) }
            })
        } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
    }
    suspend fun delete(m: Measurement): Unit = suspendCancellableCoroutine { continuation ->
        try {
            // Client identity works even if a previous process died before persisting the platform ID.
            manager().deleteRecords(listOf(RecordIdFilter.fromClientRecordId(WeightRecord::class.java,m.id)),context.mainExecutor,object: OutcomeReceiver<Void,HealthConnectException> {
                override fun onResult(result: Void?) { if(continuation.isActive) continuation.resume(Unit) }
                override fun onError(error: HealthConnectException) { if(continuation.isActive) continuation.resumeWithException(error) }
            })
        } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
    }
}
