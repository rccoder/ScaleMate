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
            listOf("health_settings","health_snapshots","health_reports","health_conversations","health_reminders").forEach {sql.execSQL("DROP TABLE $it")}
            sql.version=4
        }
        Store(context,name).use {db->
            assertEquals(5,db.writableDatabase.version)
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
            assertEquals(listOf(5,5,5),tasks.map {it.get(10,java.util.concurrent.TimeUnit.SECONDS)})
        }finally {threads.shutdownNow()}
    }
}
