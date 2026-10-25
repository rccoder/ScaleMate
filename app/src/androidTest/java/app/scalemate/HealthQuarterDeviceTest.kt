package app.scalemate

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.data.Store
import app.scalemate.health.*
import java.io.File
import java.time.ZoneId
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in emulator benchmark: actual platform records and the complete ViewModel sync path. */
@RunWith(AndroidJUnit4::class)
class HealthQuarterDeviceTest {
    @Test fun fullQuarterThenNoopRestartAndSparseChanges()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("healthQuarterLive")=="true")
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        assertEquals("app.scalemate.fooddev",context.packageName)
        val mode=arguments.getString("quarterMode") ?: "baseline"
        assertTrue(mode in setOf("baseline","fixed"))
        val report=JSONObject().put("mode",mode)
        val phases=JSONArray();report.put("phases",phases)
        val resultFile=File(context.filesDir,"health-quarter-$mode.json")
        val events=ConcurrentLinkedQueue<HealthReadEvent>()
        val fullCalls=ConcurrentLinkedQueue<Set<String>>()
        val reader=HealthReader(context){events.add(it)}
        val categories=HealthCatalog.kinds.filter {reader.status(it.id)=="ready"}.map {it.id}.toSet()
        report.put("categories",JSONArray(categories.sorted())).put("historyGranted",reader.granted(HealthCatalog.HISTORY))
        assertEquals(55,categories.size)
        assertTrue("Three months must be readable",reader.granted(HealthCatalog.HISTORY))
        val source=object:HealthSyncSource by reader {
            override suspend fun full(window:HealthWindow,categories:Set<String>,sources:Map<String,String>,progress:(String)->Unit):HealthSyncRead {
                fullCalls.add(categories)
                return reader.full(window,categories,sources,progress)
            }
        }
        val name="health-quarter-${UUID.randomUUID()}.db"
        val models=ViewModelStore()
        val rawFiles=mutableSetOf<String>()
        lateinit var vm:HealthViewModel
        fun foreground() {instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))}
        suspend fun create() {
            instrumentation.runOnMainSync {
                vm=HealthViewModel(context.applicationContext as Application,source,name)
                models.put("quarter",vm)
            }
            withTimeout(15000){while(!vm.state.value.loaded){delay(20)}}
        }
        suspend fun phase(label:String,action:()->Unit) {
            events.clear();fullCalls.clear()
            val before=vm.state.value.snapshot
            val start=SystemClock.elapsedRealtime()
            instrumentation.sendStatus(2,Bundle().apply {putString("quarterPhase",label)})
            instrumentation.runOnMainSync(action)
            withTimeout(240000) {
                var nextProgress=start+10000
                while(vm.state.value.busy) {
                    delay(50)
                    if(SystemClock.elapsedRealtime()>nextProgress) {
                        instrumentation.sendStatus(2,Bundle().apply {putString("quarterProgress","$label ${SystemClock.elapsedRealtime()-start} ms; requests=${events.size}")})
                        nextProgress=SystemClock.elapsedRealtime()+10000
                    }
                }
            }
            val state=vm.state.value
            val snapshot=state.snapshot
            if(snapshot!=null)rawFiles.add(snapshot.rawFile)
            val byOperation=events.groupBy {it.operation}.mapValues {(_,rows)->rows.size}
            val errors=events.filter {it.failure.isNotEmpty()}.groupBy {"${it.category}:${it.operation}:${it.errorCode ?: it.failure}"}.mapValues {(_,rows)->rows.size}
            val failures=snapshot?.coverage.orEmpty().filter {it.status !in setOf("available","no_records")}.associate {it.kind to it.status}
            val output=JSONObject().put("phase",label).put("elapsedMs",SystemClock.elapsedRealtime()-start)
                .put("platformRequests",JSONObject(byOperation)).put("platformErrors",JSONObject(errors))
                .put("fullCategories",JSONArray(fullCalls.flatten().distinct().sorted())).put("coverageFailures",JSONObject(failures))
                .put("errorResource",state.error ?: JSONObject.NULL).put("heapUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                .put("reusedRawFile",before!=null&&before.rawFile==snapshot?.rawFile)
            Store(context,name).use {db->
                db.readableDatabase.rawQuery("SELECT count(*) FROM health_records",null).use {it.moveToFirst();output.put("cachedRows",it.getLong(0))}
                val counts=JSONObject()
                db.readableDatabase.rawQuery("SELECT category,count(*) FROM health_records WHERE recordId NOT LIKE 'aggregate:%' GROUP BY category",null).use {while(it.moveToNext())counts.put(it.getString(0),it.getLong(1))}
                output.put("rawCounts",counts)
            }
            output.put("rawFileBytes",snapshot?.rawFile?.let {File(context.cacheDir,"health_raw/$it").length()} ?: 0)
            phases.put(output);resultFile.writeText(report.toString(2))
            instrumentation.sendStatus(2,Bundle().apply {putString("quarterResult",output.toString())})
            if(mode=="fixed") {
                assertNull(state.error)
                assertTrue("Failed categories in $label: $failures",failures.isEmpty())
                if(label!="bootstrap")assertTrue("Unexpected full rescan in $label",fullCalls.isEmpty())
                if(label.startsWith("range"))assertTrue("Viewing range must use cache",events.isEmpty())
                if(label.startsWith("unchanged")||label=="after-restart"||label=="after-change-noop") {
                    assertTrue("No-op must only query changes",events.all {it.operation=="changes"})
                    assertTrue("No-op must reuse raw detail",output.getBoolean("reusedRawFile"))
                    assertEquals(before?.evidence,snapshot?.evidence)
                }
                if(label.startsWith("range"))assertEquals(before?.readAt,snapshot?.readAt)
                if(label=="bootstrap") {
                    val expected=mapOf("Steps" to 26496,"Distance" to 8832,"ActiveCaloriesBurned" to 8832,"HeartRate" to 2208,"HeartRateVariabilityRmssd" to 3312,"Nutrition" to 276,"SleepSession" to 92,"Weight" to 92,"ExerciseSession" to 46)
                    expected.forEach {(kind,count)->assertTrue("Missing quarter records: $kind",output.getJSONObject("rawCounts").getInt(kind)>=count)}
                }
            }
        }
        suspend fun verifyDailyTotals(dates:List<LocalDate>) {
            val zone=ZoneId.of("Asia/Shanghai")
            for(date in dates)for(kind in listOf("Steps","Nutrition","Distance")) {
                val start=date.atStartOfDay(zone).toInstant();val end=date.plusDays(1).atStartOfDay(zone).toInstant()
                val expected=reader.totals(kind,start,end,zone).flatMap {it.values.entries}.associate {it.key to it.value}
                val actual=Store(context,name).use {HealthStore(it).records(setOf(kind),start.toEpochMilli(),end.toEpochMilli()).filter {it.id.startsWith("aggregate:")&&it.start==start.toEpochMilli()}.flatMap {it.values.entries}.associate {it.key to it.value}}
                assertEquals("Native totals differ for $kind $date",expected,actual)
            }
        }
        try {
            Store(context,name).use {HealthStore(it).categories(categories)}
            foreground();create()
            phase("bootstrap"){vm.refresh()}
            if(mode=="fixed")verifyDailyTotals(listOf(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1),LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(60)))
            phase("unchanged-1"){vm.refresh()}
            phase("unchanged-2"){vm.refresh()}
            phase("range-90"){vm.window(HealthWindow.recent(90,zone=ZoneId.of("Asia/Shanghai")))}
            phase("range-7"){vm.window(HealthWindow.recent(7,zone=ZoneId.of("Asia/Shanghai")))}
            instrumentation.runOnMainSync {models.clear()}
            create()
            phase("after-restart"){vm.refresh()}
            if(arguments.getString("quarterChange")=="true") {
                instrumentation.uiAutomation.executeShellCommand("am force-stop app.scalemate.healthseed").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
                instrumentation.uiAutomation.executeShellCommand("run-as app.scalemate.healthseed rm -f files/quarter-result.json").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
                instrumentation.uiAutomation.executeShellCommand("am start -W -n app.scalemate.healthseed/.SeedActivity --es action change").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
                withTimeout(30000) {
                    while(true) {
                        val text=instrumentation.uiAutomation.executeShellCommand("run-as app.scalemate.healthseed cat files/quarter-result.json").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)}
                        val result=runCatching {JSONObject(text)}.getOrNull()
                        if(result?.optString("action")=="change"){assertTrue(result.getBoolean("ok"));break}
                        delay(100)
                    }
                }
                foreground()
                phase("sparse-change"){vm.refresh()}
                if(mode=="fixed")verifyDailyTotals(listOf(1L,45L,46L).map {LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(it)})
                phase("after-change-noop"){vm.refresh()}
            }
        }finally {
            resultFile.writeText(report.toString(2))
            instrumentation.runOnMainSync {models.clear()}
            context.deleteDatabase(name)
            rawFiles.filter {it.matches(Regex("snapshot-[A-Za-z0-9-]+\\.jsonl"))}.forEach {File(context.cacheDir,"health_raw/$it").delete()}
        }
    }
}
