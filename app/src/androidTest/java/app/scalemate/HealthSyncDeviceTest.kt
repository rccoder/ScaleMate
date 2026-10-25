package app.scalemate

import android.content.Intent
import android.content.pm.PackageManager
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.InsertRecordsResponse
import android.health.connect.RecordIdFilter
import android.health.connect.datatypes.Metadata
import android.health.connect.datatypes.NutritionRecord
import android.health.connect.datatypes.units.Energy
import android.os.OutcomeReceiver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.data.Store
import app.scalemate.health.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RunWith(AndroidJUnit4::class)
class HealthSyncDeviceTest {
    @Test fun nativeBootstrapChangesDeletionAndExpiredTokenRecovery()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("healthSyncLive")=="true")
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk_gphone"))
        assertEquals("app.scalemate.fooddev",context.packageName)
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission("android.permission.health.READ_NUTRITION"))
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission("android.permission.health.WRITE_NUTRITION"))
        instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val manager=context.getSystemService(HealthConnectManager::class.java)
        val zone=ZoneId.of("Asia/Shanghai");val window=HealthWindow.recent(7,zone=zone)
        val reader=HealthReader(context);var fullReads=0
        val source=object:HealthSyncSource by reader {
            override suspend fun full(window:HealthWindow,categories:Set<String>,sources:Map<String,String>,progress:(String)->Unit):HealthSyncRead {
                fullReads++;return reader.full(window,categories,sources,progress)
            }
        }
        val clientId="health-sync-acceptance-${UUID.randomUUID()}"
        val name="health-sync-device-${UUID.randomUUID()}.db"
        suspend fun write(time:Instant,kcal:Double,version:Long):String=suspendCancellableCoroutine {continuation->
            val metadata=Metadata.Builder().setClientRecordId(clientId).setClientRecordVersion(version).setRecordingMethod(Metadata.RECORDING_METHOD_MANUAL_ENTRY).build()
            val record=NutritionRecord.Builder(metadata,time.minusSeconds(60),time).setMealName("燕麦酸奶杯").setEnergy(Energy.fromCalories(kcal*1000)).build()
            manager.insertRecords(listOf(record),context.mainExecutor,object:OutcomeReceiver<InsertRecordsResponse,HealthConnectException>{
                override fun onResult(result:InsertRecordsResponse){continuation.resume(result.records.single().metadata.id)}
                override fun onError(error:HealthConnectException){continuation.resumeWithException(error)}
            })
        }
        suspend fun delete():Unit=suspendCancellableCoroutine {continuation->
            manager.deleteRecords(listOf(RecordIdFilter.fromClientRecordId(NutritionRecord::class.java,clientId)),context.mainExecutor,object:OutcomeReceiver<Void,HealthConnectException>{
                override fun onResult(result:Void?){continuation.resume(Unit)}
                override fun onError(error:HealthConnectException){continuation.resumeWithException(error)}
            })
        }
        suspend fun matchesNative(snapshot:HealthSnapshot,date:LocalDate) {
            val start=date.atStartOfDay(zone).toInstant()
            val expected=reader.totals("Nutrition",start,date.plusDays(1).atStartOfDay(zone).toInstant(),zone).mapNotNull {it.values["energy"]}.sumOf {it.value}
            val actual=snapshot.evidence.filter {it.kind=="Nutrition"&&it.field=="energy"&&it.date==date.toString()}
            if(expected==0.0)assertTrue(actual.isEmpty()||actual.single().value==0.0)
            else assertEquals(expected,actual.single().value,.0001)
        }
        try {
            Store(context,name).use {db->
                val store=HealthStore(db);val sync=HealthSync(store,source)
                sync.refresh(window,setOf("Nutrition"),emptyMap())
                assertTrue(store.syncState("Nutrition").initialized)
                sync.refresh(window,setOf("Nutrition"),emptyMap())
                assertEquals(1,fullReads)
                val before=store.records().filterNot {it.id.startsWith("aggregate:")}.map {it.id}.toSet()
                val time=Instant.now().minusSeconds(600)
                val firstDate=time.atZone(zone).toLocalDate()
                val platformId=write(time,320.0,1)
                val inserted=sync.refresh(window,setOf("Nutrition"),emptyMap()).snapshot
                assertTrue(store.records().any {it.platformId==platformId})
                matchesNative(inserted,firstDate)
                val changedTime=time.atZone(zone).minusDays(1).toInstant()
                assertEquals(platformId,write(changedTime,410.0,2))
                val changed=sync.refresh(window,setOf("Nutrition"),emptyMap()).snapshot
                assertEquals(changedTime.minusSeconds(60).toEpochMilli(),store.records().single {it.platformId==platformId}.start)
                matchesNative(changed,firstDate);matchesNative(changed,changedTime.atZone(zone).toLocalDate())
                delete()
                val deleted=sync.refresh(window,setOf("Nutrition"),emptyMap()).snapshot
                assertFalse(store.records().any {it.platformId==platformId})
                assertEquals(before,store.records().filterNot {it.id.startsWith("aggregate:")}.map {it.id}.toSet())
                matchesNative(deleted,firstDate);matchesNative(deleted,changedTime.atZone(zone).toLocalDate())
                assertEquals(1,fullReads)
                store.syncState(store.syncState("Nutrition").copy(token="invalid-token"))
                val recovered=sync.refresh(window,setOf("Nutrition"),emptyMap()).snapshot
                assertEquals(2,fullReads);assertNotEquals("invalid-token",store.syncState("Nutrition").token)
                assertTrue(recovered.coverage.all {it.status in setOf("available","no_records")})
                matchesNative(recovered,firstDate)
            }
        }finally {delete();context.deleteDatabase(name)}
    }
}
