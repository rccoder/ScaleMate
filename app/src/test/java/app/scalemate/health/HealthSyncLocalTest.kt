package app.scalemate.health

import app.scalemate.data.Store
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class HealthSyncLocalTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val name="health-sync-validation.db"
    private val zone=ZoneId.of("Asia/Shanghai")
    private val now=Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
    private val window=HealthWindow("2026-10-18","2026-10-25",zone.id)
    @After fun cleanup(){context.deleteDatabase(name)}
    private fun record(id:String,date:String,energy:Double,kind:String="Nutrition"):HealthRecord {
        val time=LocalDate.parse(date).atTime(12,0).atZone(zone).toInstant().toEpochMilli()
        return HealthRecord(id,kind,"watch",time,time+60000,mapOf("energy" to HealthValue(energy,"kcal")),platformId="platform-$id")
    }
    private class Source:HealthSyncSource {
        var rows=listOf<HealthRecord>()
        val permissions=mutableMapOf<String,String>()
        val revisions=mutableMapOf<String,String>()
        val pending=mutableMapOf<String,HealthChanges>()
        val fullRequests=mutableListOf<Set<String>>()
        val changeRequests=mutableListOf<String>()
        val totalRequests=mutableListOf<Pair<String,String>>()
        val failChanges=mutableSetOf<String>()
        val failTotals=mutableSetOf<String>()
        val failFull=mutableSetOf<String>()
        var serial=0
        override fun status(category:String)=permissions[category] ?: "ready"
        override fun fingerprint(category:String)=status(category)+"|"+revisions[category].orEmpty()
        override fun incremental(category:String)=true
        override suspend fun full(window:HealthWindow,categories:Set<String>,sources:Map<String,String>,progress:(String)->Unit):HealthSyncRead {
            assertTrue(window.allHistory)
            fullRequests+=categories;serial++
            val zone=ZoneId.of(window.zone)
            val raw=rows.filter {it.kind in categories}
            val dates=raw.flatMap {r->healthRecordDates(r,zone).map {r.kind to it}}.distinct()
            val totals=dates.flatMap {(category,date)->aggregate(category,LocalDate.parse(date).atStartOfDay(zone).toInstant(),zone)}
            val start=raw.minOfOrNull {Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().toString()} ?: window.start
            val snapshot=HealthSnapshot(window=window.copy(start=minOf(start,window.start)),categories=categories,evidence=emptyList(),
                coverage=categories.map {HealthCoverage(it,if(it in failFull)"partial" else if(raw.any {r->r.kind==it})"available" else "no_records")},
                syncTokens=categories.associateWith {"full-$serial-$it"})
            return HealthSyncRead(snapshot,raw+totals)
        }
        override suspend fun changes(category:String,token:String):HealthChanges {
            changeRequests+=category
            check(category !in failChanges){"Expired token"}
            return pending[category] ?: HealthChanges(emptyList(),emptyList(),"next-$category")
        }
        override suspend fun totals(category:String,start:Instant,end:Instant,zone:ZoneId):List<HealthRecord> {
            totalRequests+=category to start.atZone(zone).toLocalDate().toString()
            check(category !in failTotals){"Aggregate unavailable"}
            return aggregate(category,start,zone)
        }
        private fun aggregate(category:String,start:Instant,zone:ZoneId):List<HealthRecord> {
            val end=start.atZone(zone).plusDays(1).toInstant()
            val selected=rows.filter {it.kind==category&&it.start>=start.toEpochMilli()&&it.start<end.toEpochMilli()}
            if(selected.isEmpty())return emptyList()
            return listOf(HealthRecord("aggregate:$category:$start",category,"Health Connect",start.toEpochMilli(),end.toEpochMilli(),mapOf("energy" to HealthValue(selected.sumOf {it.values.getValue("energy").value},"kcal"))))
        }
    }
    @Test fun bootstrapImportsOlderHistoryAndNoChangeRefreshPreservesDailyTotals()=runBlocking {
        Store(context,name).use {db->
            val store=HealthStore(db);val source=Source()
            source.rows=listOf(record("older","2026-06-01",300.0),record("breakfast","2026-10-25",500.0),record("lunch","2026-10-25",1000.0))
            val sync=HealthSync(store,source){now}
            sync.refresh(window,setOf("Nutrition"),emptyMap())
            assertTrue(store.records().any {it.id=="older"})
            assertTrue(store.syncState("Nutrition").allHistory)
            source.totalRequests.clear()
            val second=sync.refresh(window,setOf("Nutrition"),emptyMap()).snapshot
            assertEquals(1,source.fullRequests.size);assertEquals(listOf("Nutrition"),source.changeRequests)
            assertTrue(source.totalRequests.isEmpty())
            assertEquals(1500.0,second.evidence.single {it.kind=="Nutrition"&&it.field=="energy"&&it.date=="2026-10-25"}.value,0.0)
            assertEquals(2L,second.coverage.single().records)
            val expanded=sync.refresh(window.copy(start="2026-05-01"),setOf("Nutrition"),emptyMap()).snapshot
            assertTrue(expanded.evidence.any {it.date=="2026-06-01"})
            assertEquals(1,source.fullRequests.size)
        }
    }
    @Test fun movedRecordRebuildsOldAndNewDatesWithoutDeletingOtherMeals()=runBlocking {
        Store(context,name).use {db->
            val store=HealthStore(db);val source=Source();val sync=HealthSync(store,source){now}
            val moved=record("moved","2026-10-24",400.0)
            val retained=record("retained","2026-10-24",600.0)
            source.rows=listOf(moved,retained);sync.refresh(window,setOf("Nutrition"),emptyMap())
            val updated=record("moved","2026-10-25",450.0)
            source.rows=listOf(updated,retained)
            source.pending["Nutrition"]=HealthChanges(listOf(updated),emptyList(),"updated")
            val result=sync.refresh(window,setOf("Nutrition"),emptyMap()).snapshot
            assertEquals(setOf("2026-10-24","2026-10-25"),source.totalRequests.map {it.second}.toSet())
            assertEquals(setOf("moved","retained"),store.records().filterNot {it.id.startsWith("aggregate:")}.map {it.id}.toSet())
            assertEquals(mapOf("2026-10-24" to 600.0,"2026-10-25" to 450.0),result.evidence.filter {it.field=="energy"}.associate {it.date to it.value})
            assertEquals("updated",store.syncState("Nutrition").token)
        }
    }
    @Test fun deletionOnlyRemovesDeletedRecordAndItsStaleDailyBucket()=runBlocking {
        Store(context,name).use {db->
            val store=HealthStore(db);val source=Source();val sync=HealthSync(store,source){now}
            val deleted=record("deleted","2026-10-25",500.0)
            val keep=record("keep","2026-10-24",200.0,"ActiveCaloriesBurned")
            source.rows=listOf(deleted,keep);sync.refresh(window,setOf("Nutrition","ActiveCaloriesBurned"),emptyMap())
            source.rows=listOf(keep);source.pending["Nutrition"]=HealthChanges(emptyList(),listOf(deleted.platformId),"deleted")
            val result=sync.refresh(window,setOf("Nutrition","ActiveCaloriesBurned"),emptyMap()).snapshot
            assertTrue(store.records(setOf("Nutrition")).isEmpty())
            assertTrue(store.records().any {it.id=="keep"})
            assertEquals("no_records",result.coverage.single {it.kind=="Nutrition"}.status)
            assertFalse(result.evidence.any {it.kind=="Nutrition"})
        }
    }
    @Test fun failedIncrementalAndRecoveryKeepRowsAndTokenUntilRetrySucceeds()=runBlocking {
        Store(context,name).use {db->
            val store=HealthStore(db);val source=Source();val sync=HealthSync(store,source){now}
            val old=record("meal","2026-10-25",500.0);source.rows=listOf(old)
            sync.refresh(window,setOf("Nutrition"),emptyMap());val token=store.syncState("Nutrition").token
            val changed=old.copy(values=mapOf("energy" to HealthValue(900.0,"kcal")))
            source.rows=listOf(changed);source.pending["Nutrition"]=HealthChanges(listOf(changed),emptyList(),"changed")
            source.failTotals+="Nutrition";source.failFull+="Nutrition"
            val failed=sync.refresh(window,setOf("Nutrition"),emptyMap()).snapshot
            assertEquals(token,store.syncState("Nutrition").token)
            assertEquals(old,store.records().single {it.id==old.id})
            assertEquals("partial",failed.coverage.single().status)
            source.failTotals.clear();source.failFull.clear()
            val retried=sync.refresh(window,setOf("Nutrition"),emptyMap()).snapshot
            assertEquals(900.0,retried.evidence.single {it.field=="energy"}.value,0.0)
            assertEquals("changed",store.syncState("Nutrition").token)
        }
    }
    @Test fun expiredTokenAndHistoryPermissionChangeReconcileOnlyAffectedTypes()=runBlocking {
        Store(context,name).use {db->
            val store=HealthStore(db);val source=Source();val sync=HealthSync(store,source){now}
            source.rows=listOf(record("meal","2026-10-25",500.0),record("walk","2026-10-24",200.0,"ActiveCaloriesBurned"))
            val categories=setOf("Nutrition","ActiveCaloriesBurned")
            sync.refresh(window,categories,emptyMap());source.failChanges+="Nutrition"
            sync.refresh(window,categories,emptyMap())
            assertEquals(setOf("Nutrition"),source.fullRequests.last())
            source.failChanges.clear();source.revisions["ActiveCaloriesBurned"]="history-granted"
            sync.refresh(window,categories,emptyMap())
            assertEquals(setOf("ActiveCaloriesBurned"),source.fullRequests.last())
            source.permissions["Nutrition"]="not_authorized"
            val revoked=sync.refresh(window,categories,emptyMap()).snapshot
            assertFalse(revoked.evidence.any {it.kind=="Nutrition"})
            assertEquals("not_authorized",revoked.coverage.single {it.kind=="Nutrition"}.status)
        }
    }
    @Test fun crossMidnightUpdateRebuildsEachCalendarDayAndStopsAtExclusiveEnd()=runBlocking {
        val zone=ZoneId.of("America/New_York")
        val start=LocalDate.of(2026,3,7).atTime(23,0).atZone(zone).toInstant().toEpochMilli()
        val end=LocalDate.of(2026,3,10).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(setOf("2026-03-07","2026-03-08","2026-03-09"),healthRecordDates(HealthRecord("steps","Steps","watch",start,end),zone))
    }
}
