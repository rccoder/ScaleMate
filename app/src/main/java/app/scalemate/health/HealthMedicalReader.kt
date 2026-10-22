package app.scalemate.health

import android.annotation.SuppressLint
import android.content.Context
import android.health.connect.*
import android.health.connect.datatypes.MedicalResource
import android.os.OutcomeReceiver
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Loaded only after the corresponding runtime classes and permissions are available. */
@SuppressLint("NewApi")
class HealthMedicalReader(private val context:Context) {
    suspend fun read(kind:HealthKind):List<HealthRecord> {
        val type=MedicalResource::class.java.getField("MEDICAL_RESOURCE_TYPE_${kind.medicalType}").getInt(null)
        var request:ReadMedicalResourcesRequest=ReadMedicalResourcesInitialRequest.Builder(type).setPageSize(500).build()
        val seen=mutableSetOf<String>();val records=mutableListOf<HealthRecord>()
        while(true) {
            coroutineContext.ensureActive()
            val response:ReadMedicalResourcesResponse=suspendCancellableCoroutine {c->
                try {context.getSystemService(HealthConnectManager::class.java)!!.readMedicalResources(request,context.mainExecutor,object:OutcomeReceiver<ReadMedicalResourcesResponse,HealthConnectException>{
                    override fun onResult(result:ReadMedicalResourcesResponse){if(c.isActive)c.resume(result)}
                    override fun onError(error:HealthConnectException){if(c.isActive)c.resumeWithException(error)}
                })}catch(e:Exception){if(c.isActive)c.resumeWithException(e)}
            }
            response.medicalResources.forEach {r->
                val sanitized=HealthFhir.sanitize(r.fhirResource.data)
                val at=HealthFhir.date(sanitized) ?: 0L
                records+=HealthRecord(healthHash(r.id.toString()).take(24),kind.id,"Clinical source "+healthHash(r.dataSourceId).take(8),at,values=HealthFhir.numbers(sanitized),detail=sanitized.toString())
            }
            check(records.size<=50000){"Medical selection too large"}
            val token=response.nextPageToken ?: break
            if(token.isEmpty())break
            check(seen.add(token)){"Repeated medical page"}
            request=ReadMedicalResourcesPageRequest.Builder(token).setPageSize(500).build()
        }
        return records.distinctBy {it.id}
    }
}
