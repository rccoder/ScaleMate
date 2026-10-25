package app.scalemate.health

import app.scalemate.data.*
import app.scalemate.food.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class HealthStoreLocalTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val name="health-local-validation.db"
    @After fun cleanup(){context.deleteDatabase(name)}
    @Test fun versionFourUpgradePreservesWeightsMealsLibraryServicesAndDraft() {
        Store(context,name).use {db->
            db.bind(BoundScale("scale","Home","synthetic-address",true))
            db.save(Measurement("weight","scale","Home",70.0,1000,0))
            val food=FoodStore(db)
            food.save(Meal(id="meal",items=listOf(FoodItem(name="Rice",base=Nutrients(200.0)))))
            food.service(AiService(id="service",name="Synthetic service",model="synthetic",encryptedKey="opaque-ciphertext",consentVersion=3,version=3))
            food.setting("draft","synthetic-draft");food.setting("text","service")
            val sql=db.writableDatabase
            listOf("health_settings","health_snapshots","health_reports","health_conversations","health_reminders","health_records","health_sync_state").forEach {sql.execSQL("DROP TABLE $it")}
            sql.version=4
        }
        Store(context,name).use {db->
            assertEquals(6,db.writableDatabase.version)
            assertEquals("scale",db.scales().single().id);assertEquals(70.0,db.records().single().kg,0.0)
            val food=FoodStore(db);assertEquals("meal",food.meals().single().id);assertEquals(1,food.library().size)
            assertEquals("opaque-ciphertext",food.services().single().encryptedKey);assertEquals("service",food.setting("text"));assertEquals("synthetic-draft",food.setting("draft"))
            HealthStore(db).categories(setOf("SleepSession"));assertEquals(setOf("SleepSession"),HealthStore(db).categories())
        }
    }
    @Test fun healthConsentDoesNotReuseFoodConsentAndIsScopedToVersionAndCategories() {
        Store(context,name).use {db->
            val health=HealthStore(db);val service=AiService(id="s",version=2,consentVersion=2)
            assertFalse(health.consented(service,setOf("SleepSession")))
            health.consent(service,setOf("SleepSession"));assertTrue(health.consented(service,setOf("SleepSession")))
            assertFalse(health.consented(service,setOf("HeartRate")));assertFalse(health.consented(service.copy(version=3),setOf("SleepSession")))
        }
    }
    @Test fun snapshotAndReportsSurviveReopenAndEventKeysAreUnique() {
        val snapshot=HealthSnapshot(window=HealthWindow("2026-10-10","2026-10-17"),categories=setOf("Steps"),evidence=emptyList(),coverage=listOf(HealthCoverage("Steps","no_records")))
        val message=HealthMessage(role="assistant",text="Synthetic response",snapshotId=snapshot.id)
        Store(context,name).use {db->val health=HealthStore(db);health.snapshot(snapshot);health.report(HealthReport(snapshotId=snapshot.id,message=message));health.conversation(HealthConversation(id="conversation",messages=listOf(message)));val r=HealthReminder(topics=listOf("sleep"),evidenceIds=emptyList(),createdAt=System.currentTimeMillis(),snapshotId=snapshot.id,key="same-event");assertTrue(health.reminder(r));assertFalse(health.reminder(r.copy(id="another-id")));assertFalse(health.reminder(r.copy(id="foreground-race",key="different-topic-same-day",topics=listOf("activity"))))}
        Store(context,name).use {db->val health=HealthStore(db);assertEquals(snapshot,health.snapshot(snapshot.id));assertEquals(message,health.reports().single().message);assertEquals(message,health.conversations().single().messages.single());assertEquals(1,health.reminders().size)}
    }
    @Test fun deletingHealthHistoryCannotDeleteBusinessDataOrServices() {
        Store(context,name).use {db->
            db.save(Measurement("weight","s","Home",70.0,1000,0));val food=FoodStore(db);food.setting("draft","keep");food.service(AiService(id="s",name="Keep"))
            val health=HealthStore(db);health.conversation(HealthConversation(title="Delete"));health.clear()
            assertTrue(health.conversations().isEmpty());assertEquals(1,db.records().size);assertEquals("keep",food.setting("draft"));assertEquals("s",food.services().single().id)
        }
    }
    @Test fun pruningKeepsEvidenceReferencedByRetainedConversations() {
        Store(context,name).use {db->
            val health=HealthStore(db);val old=System.currentTimeMillis()-100L*86400000
            val retained=HealthSnapshot(readAt=old,window=HealthWindow("2026-01-01","2026-01-08"),categories=emptySet(),evidence=emptyList(),coverage=emptyList())
            val unused=retained.copy(id="unused")
            health.snapshot(retained);health.snapshot(unused);health.conversation(HealthConversation(id="c",messages=listOf(HealthMessage(role="assistant",text="Old",snapshotId=retained.id))))
            health.prune();assertNotNull(health.snapshot(retained.id));assertNull(health.snapshot(unused.id))
            health.deleteConversation("c");assertNull(health.snapshot(retained.id))
        }
    }
    @Test fun independentFeatureHelpersCanInitializeTheSameNewDatabaseConcurrently() {
        val threads=java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val tasks=(1..3).map {threads.submit<Int> {Store(context,name).use {db->db.readableDatabase.version}}}
            assertEquals(listOf(6,6,6),tasks.map {it.get(10,java.util.concurrent.TimeUnit.SECONDS)})
        }finally {threads.shutdownNow()}
    }
    @Test fun syncIndexReplacesOnlyTheRequestedRangeAndKeepsLatestState() {
        Store(context,name).use {db->
            val health=HealthStore(db)
            val start=java.time.Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
            val end=start+2*86400000
            val old=HealthRecord("old","Steps","watch",start)
            val retained=HealthRecord("retained","Weight","scale",end)
            val previousAggregate=HealthRecord("aggregate:Steps:${start-86400000}","Steps","Health Connect",start-86400000,end-86400000)
            val spanning=HealthRecord("spanning","Steps","watch",start-3600000,end-3600000)
            health.replaceRange(setOf("Steps"),start,end,listOf(old))
            health.upsert(listOf(retained,previousAggregate,spanning))
            health.replaceRange(setOf("Steps"),start,end,listOf(old.copy(id="new")))
            assertEquals(listOf("aggregate:Steps:${start-86400000}","new"),health.records(setOf("Steps")).map {it.id})
            assertEquals(listOf("retained"),health.records(setOf("Weight")).map {it.id})
            val state=HealthSyncState("Steps","token",123,"permission","2026-10-01","2026-10-25",false)
            health.syncState(state)
            assertEquals(state,health.syncState("Steps"))
        }
    }
    @Test fun syncPlanUsesFullReadForNewOrPermissionChangedCategoriesAndIncrementalForTheRest() {
        val states=mapOf(
            "Steps" to HealthSyncState("Steps","token",1,"ready","2026-10-18","2026-10-25"),
            "SleepSession" to HealthSyncState("SleepSession","token",1,"old","2026-10-18","2026-10-25")
        )
        val window=HealthWindow("2026-10-18","2026-10-25")
        val incremental=setOf("Steps","SleepSession","Weight")
        val plan=HealthSyncPlan.plan(setOf("Steps","SleepSession","Weight"),states,{if(it=="SleepSession")"new" else "ready"},window,incremental)
        assertEquals(setOf("SleepSession","Weight"),plan.full)
        assertEquals(setOf("Steps"),plan.incremental)
        assertEquals(setOf("Steps","SleepSession","Weight"),HealthSyncPlan.plan(setOf("Steps","SleepSession","Weight"),states,{"ready"},window,incremental,true).full)
    }
    @Test fun longerWindowRequiresFullReadEvenWhenTokenIsValid() {
        val state=HealthSyncState("Steps","token",1,"ready","2026-10-18","2026-10-25")
        val states=mapOf("Steps" to state)
        val longer=HealthWindow("2026-07-27","2026-10-25")
        val plan=HealthSyncPlan.plan(setOf("Steps"),states,{"ready"},longer,setOf("Steps"))
        assertEquals(setOf("Steps"),plan.full)
        assertTrue(state.covers(HealthWindow("2026-10-20","2026-10-25")))
        assertFalse(state.covers(HealthWindow("2026-10-18","2026-10-25").copy(allHistory=true)))
    }
    @Test fun incrementalTransactionTracksDeletedDatesAndKeepsPlatformIdsLocal() {
        Store(context,name).use {db->
            val health=HealthStore(db);val zone=java.time.ZoneId.of("Asia/Shanghai")
            val first=java.time.Instant.parse("2026-10-24T04:00:00Z").toEpochMilli()
            val second=java.time.Instant.parse("2026-10-25T04:00:00Z").toEpochMilli()
            val old=HealthRecord("old","Steps","watch",first,first,platformId="platform-old")
            health.upsert(listOf(old))
            val affected=health.applyIncremental(listOf(old.copy(id="new",start=second,end=second,platformId="platform-new")),listOf(old),mapOf("Steps" to emptySet()),zone)
            assertEquals(setOf("2026-10-24","2026-10-25"),affected.getValue("Steps"))
            assertEquals(listOf("new"),health.records(setOf("Steps")).map {it.id})
            assertFalse(HealthJson.record(health.records().single()).has("platformId"))
            assertTrue(HealthJson.cacheRecord(health.records().single()).has("platformId"))
        }
    }
    @Test fun failedCursorWriteRollsBackRecordChangesInTheSameTransaction() {
        Store(context,name).use {db->
            val health=HealthStore(db)
            val original=HealthRecord("original","Steps","watch",1000,platformId="platform-original")
            val state=HealthSyncState("Steps","original",123,"permission","2026-10-01","2026-10-25",true)
            health.upsert(listOf(original));health.syncState(state);health.markCacheChanged()
            val revision=health.cacheRevision()
            db.writableDatabase.execSQL("CREATE TRIGGER reject_cursor BEFORE INSERT ON health_sync_state WHEN NEW.token='rejected' BEGIN SELECT RAISE(ABORT,'Synthetic storage failure'); END")
            try {
                health.transaction {
                    health.applyIncremental(listOf(original.copy(start=2000,end=2000)),emptyList(),emptyMap(),java.time.ZoneOffset.UTC)
                    health.markCacheChanged()
                    health.syncState(state.copy(token="rejected"))
                }
                fail("Cursor write should fail")
            }catch(_:android.database.sqlite.SQLiteException){}
            assertEquals(listOf(original),health.records())
            assertEquals(state,health.syncState("Steps"))
            assertEquals(revision,health.cacheRevision())
        }
    }
    @Test fun streamedRawSnapshotKeepsSamplesAndPrivateIdsButExcludesAggregates()=kotlinx.coroutines.runBlocking {
        Store(context,name).use {db->
            val store=HealthStore(db)
            val record=HealthRecord("raw","HeartRate","watch",1000,2000,points=listOf(HealthPoint(1200,70.0,"bpm")),platformId="local-only")
            val aggregate=record.copy(id="aggregate:HeartRate:1000")
            store.upsert(listOf(record,aggregate))
            val out=java.io.StringWriter();store.writeCachedRaw(listOf(record,aggregate),out)
            val lines=out.toString().lineSequence().filter {it.isNotBlank()}.toList()
            assertEquals(1,lines.size)
            assertEquals(record,HealthJson.cachedRecord(org.json.JSONObject(lines.single())))
            assertFalse(HealthJson.record(record).has("platformId"))
        }
    }
}
