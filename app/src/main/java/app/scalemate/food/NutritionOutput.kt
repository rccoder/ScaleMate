package app.scalemate.food

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.*
import android.health.connect.datatypes.*
import android.health.connect.datatypes.units.Energy
import android.health.connect.datatypes.units.Mass
import android.os.OutcomeReceiver
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NutritionOutput(private val context: Context) {
    companion object {const val WRITE="android.permission.health.WRITE_NUTRITION"}
    val available get()=context.getSystemService(HealthConnectManager::class.java)!=null
    val permitted get()=context.checkSelfPermission(WRITE)==PackageManager.PERMISSION_GRANTED
    private fun manager()=context.getSystemService(HealthConnectManager::class.java) ?: error("Health Connect unavailable")
    suspend fun write(m: Meal): String = suspendCancellableCoroutine {c->
        try {
            val meta=Metadata.Builder().setClientRecordId("food:${m.id}").setClientRecordVersion(m.revision).setRecordingMethod(Metadata.RECORDING_METHOD_MANUAL_ENTRY).build()
            val end=Instant.ofEpochMilli(m.time);val offset=ZoneOffset.ofTotalSeconds(m.offset)
            val builder=NutritionRecord.Builder(meta,end.minusSeconds(60),end).setStartZoneOffset(offset).setEndZoneOffset(offset).setMealType(m.mealType).setMealName(m.title)
            val n=m.nutrients
            n.kcal?.let {builder.setEnergy(Energy.fromCalories(it*1000))};n.protein?.let {builder.setProtein(Mass.fromGrams(it))};n.carbs?.let {builder.setTotalCarbohydrate(Mass.fromGrams(it))};n.fat?.let {builder.setTotalFat(Mass.fromGrams(it))}
            manager().insertRecords(listOf(builder.build()),context.mainExecutor,object:OutcomeReceiver<InsertRecordsResponse,HealthConnectException>{
                override fun onResult(r:InsertRecordsResponse){if(c.isActive)c.resume(r.records.first().metadata.id)}
                override fun onError(e:HealthConnectException){if(c.isActive)c.resumeWithException(e)}
            })
        }catch(e:Exception){if(c.isActive)c.resumeWithException(e)}
    }
    suspend fun delete(id: String): Unit = suspendCancellableCoroutine {c->
        try {manager().deleteRecords(listOf(RecordIdFilter.fromClientRecordId(NutritionRecord::class.java,"food:$id")),context.mainExecutor,object:OutcomeReceiver<Void,HealthConnectException>{
            override fun onResult(r:Void?){if(c.isActive)c.resume(Unit)}
            override fun onError(e:HealthConnectException){if(c.isActive)c.resumeWithException(e)}
        })}catch(e:Exception){if(c.isActive)c.resumeWithException(e)}
    }
}
