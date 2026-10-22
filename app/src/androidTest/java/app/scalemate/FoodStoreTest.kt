package app.scalemate

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.data.Store
import app.scalemate.food.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodStoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="test-food-v2.db"
    @After fun cleanup(){context.deleteDatabase(name)}
    @Test fun versionOneMigrationKeepsWeightsAndDevices() {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use {db->
            db.execSQL("CREATE TABLE scales(id TEXT PRIMARY KEY,name TEXT NOT NULL,address TEXT UNIQUE NOT NULL,verified INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE measurements(id TEXT PRIMARY KEY,deviceId TEXT NOT NULL,source TEXT NOT NULL,kg REAL NOT NULL,time INTEGER NOT NULL,offset INTEGER NOT NULL,status TEXT NOT NULL,platformId TEXT NOT NULL,attempts INTEGER NOT NULL)")
            db.execSQL("INSERT INTO scales VALUES('scale','home','A',1)")
            db.execSQL("INSERT INTO measurements VALUES('weight','scale','home',71.2,1000,28800,'synced','platform',1)")
            db.version=1
        }
        Store(context,name).use {db->assertEquals(71.2,db.records().single().kg,0.001);assertTrue(db.scales().single().verified)
            val food=FoodStore(db);food.save(Meal(items=listOf(FoodItem(name="rice",base=Nutrients(100.0)))));assertEquals(1,food.meals().size)}
    }
    @Test fun developmentHealthSchemaDoesNotDowngradeDatabase() {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use {db->
            db.execSQL("CREATE TABLE scales(id TEXT PRIMARY KEY,name TEXT NOT NULL,address TEXT UNIQUE NOT NULL,verified INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE measurements(id TEXT PRIMARY KEY,deviceId TEXT NOT NULL,source TEXT NOT NULL,kg REAL NOT NULL,time INTEGER NOT NULL,offset INTEGER NOT NULL,status TEXT NOT NULL,platformId TEXT NOT NULL,attempts INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE health_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("INSERT INTO scales VALUES('scale','home','A',1)")
            db.execSQL("INSERT INTO measurements VALUES('weight','scale','home',71.2,1000,28800,'synced','platform',1)")
            db.execSQL("INSERT INTO health_settings VALUES('sentinel','kept')")
            db.version=5
        }
        Store(context,name).use {db->
            assertEquals(71.2,db.records().single().kg,0.001)
            db.readableDatabase.rawQuery("SELECT value FROM health_settings WHERE key='sentinel'",null).use {
                assertTrue(it.moveToFirst());assertEquals("kept",it.getString(0))
            }
            FoodStore(db).save(Meal(items=listOf(FoodItem(name="rice",base=Nutrients(100.0)))))
            assertEquals(1,FoodStore(db).meals().size)
        }
    }
    @Test fun pendingEditAndDeleteSurviveRestart() {
        context.deleteDatabase(name)
        val meal=Meal(items=listOf(FoodItem(name="rice",base=Nutrients(100.0))))
        Store(context,name).use {db->FoodStore(db).apply {save(meal);status(meal.id,"synced","platform");update(meal.copy(revision=2));tombstone(meal.id)}}
        Store(context,name).use {db->val food=FoodStore(db);assertTrue(food.meals().isEmpty());val row=food.meals(true).single();assertEquals(2,row.revision);assertTrue(row.deleted);assertEquals("deleting",row.status);assertEquals("platform",row.platformId)}
    }
    @Test fun transactionsAndTemplateSnapshotsDoNotCorruptHistory() {
        context.deleteDatabase(name)
        Store(context,name).use {db->val food=FoodStore(db);val m=Meal(items=listOf(FoodItem(name="rice",base=Nutrients(100.0),fraction=0.5)))
            try {food.transaction {food.save(m);error("simulate failure")}}catch(_:IllegalStateException){}
            assertTrue(food.meals().isEmpty());food.save(m)
            food.template(FoodTemplate(item=m.items.single().reuse()))
            food.remove(m.id);assertEquals(50.0,food.templates().single().item.nutrients.kcal!!,0.001)
        }
    }
    @Test fun encryptedKeysAndDefaultRoutesRemainSeparate() {
        context.deleteDatabase(name)
        val secrets=AiSecrets();val service=AiService(name="synthetic",baseUrl="https://example.com/v1",model="test",images=true)
        val raw="synthetic-not-a-real-key"
        val saved=service.copy(encryptedKey=secrets.encrypt(service.id,raw))
        assertTrue(saved.encryptedKey!=raw);assertTrue(secrets.decrypt(saved)==raw)
        Store(context,name).use {db->val food=FoodStore(db);food.service(saved);food.setting("vision",saved.id);food.setting("text",saved.id);food.deleteService(saved.id);assertTrue(food.setting("vision").isEmpty());assertTrue(food.setting("text").isEmpty())}
    }
    @Test fun strictAiContractAndDraftRoundTrip() {
        val f=FoodJson.recognitionPersonal("""{"calculationMode":"personal","foods":[{"name":"rice","personalNutrients":{"kcal":300,"protein":null,"carbs":65,"fat":1}}]}""",2).single()
        assertTrue(f.needsPortionReview);assertNull(f.base.protein)
        val d=FoodDraft(batch=FoodBatch(results=listOf(f),description="rice 1/3",tags=setOf("shared")))
        assertEquals(d,FoodJson.draft(FoodJson.draft(d)))
        try {FoodJson.recognitionPersonal("""{"calculationMode":"personal","foods":[{"name":"rice","personalNutrients":{"kcal":-3}}]}""",2);fail("Negative AI nutrition accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun directPersonalSnapshotPreservesInputsWithoutScaling() {
        val raw="""{"calculationMode":"personal","foods":[{"name":"米饭","personalGrams":50,"personalNutrients":{"kcal":100},"intakeSummary":"两人均分后吃自己那份的三分之一。","contextInterpretation":"米饭只吃自己分到份额的三分之一，少酱按描述估算。"}]}"""
        val food=FoodJson.recognitionPersonal(raw,2,tags=setOf("少酱"),description="米饭只吃自己那份的1/3").single()
        val snapshot=food.estimateContext!!
        assertEquals(2,snapshot.shareCount);assertEquals(listOf("少酱"),snapshot.tags)
        assertEquals("米饭只吃自己那份的1/3",snapshot.description)
        assertEquals("personal",snapshot.resultMode);assertEquals(1.0,snapshot.factor,0.0)
        assertEquals(100.0,food.nutrients.kcal!!,0.001);assertEquals(50.0,food.grams!!,0.001)
        assertEquals(100.0,snapshot.reportedNutrients.kcal!!,0.001)
        assertEquals(food,FoodJson.item(FoodJson.item(food)))
        val withWarning=food.copy(estimateContext=snapshot.copy(warning="食用比例不明确，暂按本人整份估算。"))
        assertEquals(withWarning,FoodJson.item(FoodJson.item(withWarning)))
        val restored=FoodJson.draft(FoodJson.draft(FoodDraft(batch=FoodBatch(results=listOf(food)))))
        assertEquals(food,restored.batch!!.results.single())
        val old=FoodJson.item(food).apply {remove("estimateContext")}
        assertNull(FoodJson.item(old).estimateContext)
        val legacy=FoodJson.item(food).apply {getJSONObject("estimateContext").remove("resultMode")}
        assertEquals("legacy",FoodJson.item(legacy).estimateContext!!.resultMode)
    }
    @Test fun eatingAdviceSurvivesDraftMealAndLibraryAndOldItemsRemainReadable() {
        context.deleteDatabase(name)
        val item=FoodJson.recognitionPersonal("""{"calculationMode":"personal","foods":[{"name":"咖喱猪排饭","personalNutrients":{"kcal":650},"assumptions":"按可见炸猪排估算，酱汁用量不确定。","eatingAdvice":"酱汁可以按需蘸取，搭配一份蔬菜。","nextTimeAdvice":"下次可选择非油炸肉类。"}]}""",1).single().copy(needsPortionReview=false)
        val draft=FoodDraft(batch=FoodBatch(results=listOf(item)))
        assertEquals(draft,FoodJson.draft(FoodJson.draft(draft)))
        val old=FoodJson.item(item).apply {remove("eatingAdvice");remove("nextTimeAdvice");remove("positiveFeedback")}
        assertFalse(FoodJson.item(old).hasEatingSuggestions)
        Store(context,name).use {db->val food=FoodStore(db)
            food.save(Meal(items=listOf(item)));food.template(FoodTemplate(item=item.reuse()))
        }
        Store(context,name).use {db->val food=FoodStore(db)
            assertEquals(item.eatingAdvice,food.meals().single().items.single().eatingAdvice)
            assertEquals(item.nextTimeAdvice,food.meals().single().items.single().nextTimeAdvice)
            assertEquals(item.eatingAdvice,food.templates().single().item.eatingAdvice)
        }
        val noAdvice=FoodJson.recognitionPersonal("""{"calculationMode":"personal","foods":[{"name":"米饭","personalNutrients":{"kcal":100},"eatingAdvice":null,"nextTimeAdvice":"  ","positiveFeedback":null}]}""",1).single()
        assertFalse(noAdvice.hasEatingSuggestions)
        val feedback=item.copy(eatingAdvice="",nextTimeAdvice="",positiveFeedback="搭配包含主食与蛋白质。")
        assertEquals(feedback,FoodJson.item(FoodJson.item(feedback)))
    }
    @Test fun mealAdviceAndProviderUsageSurviveRecognitionDraftAndSavedMeal() {
        context.deleteDatabase(name)
        val raw="""{"calculationMode":"personal","foods":[{"name":"咖喱猪排饭","personalGrams":260,"personalNutrients":{"kcal":650},"eatingAdvice":"酱汁可以按需蘸取。"}],"mealAdvice":{"healthiness":"以主食和蛋白质为主，蔬菜偏少。","balance":"先吃蔬菜，酱汁按需蘸取。","nextTime":"下次搭配一份深色蔬菜。","note":"蔬菜被遮挡，实际份量可能更多。"},"usage":{"prompt_tokens":1200,"completion_tokens":240,"total_tokens":1440}}"""
        val recognized=FoodJson.recognition(raw,1)
        assertEquals("先吃蔬菜，酱汁按需蘸取。",recognized.advice.balance)
        val usage=FoodJson.usage(raw)
        assertEquals(1200L,usage.inputTokens);assertEquals(240L,usage.outputTokens);assertEquals(1440L,usage.totalTokens)
        val batch=FoodBatch(results=recognized.items,status="review",advice=recognized.advice,usage=usage,analysisMs=12_345)
        val draft=FoodDraft(batch=batch)
        assertEquals(draft,FoodJson.draft(FoodJson.draft(draft)))
        val committed=draft.commitBatch()
        assertEquals(batch.advice,committed.meal.advice)
        assertEquals(12_345L,committed.meal.analysisMs)
        val meal=committed.meal
        Store(context,name).use {db->FoodStore(db).save(meal)}
        Store(context,name).use {db->
            val restored=FoodStore(db).meals().single()
            assertEquals(recognized.advice,restored.advice);assertEquals(usage,restored.usage);assertEquals(12_345L,restored.analysisMs)
        }
    }
    @Test fun libraryCountsNewMealsOnlyAndFavoritesKeepBaseline() {
        context.deleteDatabase(name)
        Store(context,name).use {db->val food=FoodStore(db)
            val first=FoodItem(name="咖喱猪排饭",base=Nutrients(650.0),photos=listOf("/private/thumb.jpg"))
            val m=Meal(items=listOf(first,first.copy(id=newFoodId())))
            food.save(m)
            assertEquals(1L,food.library().single().useCount)
            assertTrue(food.meals().single().items.all {it.photos.isEmpty()})
            food.favorite(first.copy(base=Nutrients(700.0)),true)
            food.save(Meal(items=listOf(first.copy(base=Nutrients(400.0)))))
            var e=food.library().single();assertEquals(2L,e.useCount);assertEquals(700.0,e.item.nutrients.kcal!!,0.001)
            food.update(m.copy(revision=2,items=listOf(first.copy(base=Nutrients(300.0)))))
            assertEquals(2L,food.library().single().useCount)
            food.editLibrary(e.id,e.item.copy(base=Nutrients(550.0)))
            assertEquals(550.0,food.library().single().item.nutrients.kcal!!,0.001)
            food.favorite(first,false)
            food.save(Meal(items=listOf(first.copy(name=" 咖喱猪排饭 ",base=Nutrients(450.0)))))
            e=food.library().single();assertEquals(3L,e.useCount);assertEquals(450.0,e.item.nutrients.kcal!!,0.001)
            try {food.transaction {food.save(Meal(items=listOf(first)));error("rollback")}}catch(_:IllegalStateException){}
            assertEquals(3L,food.library().single().useCount)
        }
    }
    @Test fun libraryMigrationIsIdempotentAndPreservesOldFavorites() {
        context.deleteDatabase(name)
        Store(context,name).use {db->val food=FoodStore(db);val f=FoodItem(name="咖喱猪排饭",base=Nutrients(650.0))
            food.save(Meal(items=listOf(f)))
            db.writableDatabase.delete("food_library",null,null)
            food.template(FoodTemplate(item=f.copy(base=Nutrients(700.0))))
            food.migrateLibrary();food.migrateLibrary()
            assertEquals(1,food.library().size);assertEquals(1L,food.library().single().useCount);assertTrue(food.library().single().favorite);assertEquals(700.0,food.library().single().item.nutrients.kcal!!,0.001)
        }
    }
    @Test fun finalPersonalIntakeNeverDividesByGroupOrLegacyFractions() {
        val raw="""{"calculationMode":"personal","foods":[{"name":"咖喱猪排饭","personalGrams":260,"personalNutrients":{"kcal":360,"protein":null},"wholeNutrients":{"kcal":720},"shareCount":2,"consumedFraction":0.5,"fractionBasis":"own","imageIndices":[0,1]}]}"""
        for(group in listOf(1,2,6)) {
            val food=FoodJson.recognitionPersonal(raw,group,listOf("a","b")).single()
            assertEquals(360.0,food.nutrients.kcal!!,0.001);assertEquals(260.0,food.grams!!,0.001)
            assertEquals(1.0,food.factor,0.0);assertEquals(food.nutrients,food.reuse().nutrients)
            assertNull(food.base.protein);assertEquals(listOf("a","b"),food.photos)
        }
        try {FoodJson.recognitionPersonal(raw,2,listOf("a"));fail("Invalid image index accepted")}catch(_:IllegalArgumentException){}
        for(invalid in listOf(raw.replace("360","-1"),raw.replace("personalNutrients","wholeOnly"),raw.replace("\"calculationMode\":\"personal\",",""))) {
            try {FoodJson.recognitionPersonal(invalid,2);fail("Invalid final-intake contract accepted")}catch(_:Exception){}
        }
    }
    @Test fun analyzingDraftRestoresAsInterruptedWithoutLosingPhotos() {
        val draft=FoodDraft(batch=FoodBatch(photos=listOf("a","b"),status="analyzing",description="两人均分",shareCount=2))
        val restored=FoodJson.draft(FoodJson.draft(draft))
        assertEquals("interrupted",restored.batch!!.status);assertEquals(listOf("a","b"),restored.batch!!.photos);assertTrue(restored.meal.items.isEmpty())
    }
    @Test fun pendingAiResultsKeepFullFoodDataWhenRestoringLegacySplitDraft() {
        val food=FoodJson.recognitionPersonal("""{"calculationMode":"personal","foods":[{"name":"咖喱猪排饭","personalGrams":260,"personalNutrients":{"kcal":650},"assumptions":"按可见份量估算。","eatingAdvice":"酱汁按需蘸取。","nextAdvice":"下次搭配蔬菜。","contextWarning":"油量不确定。"}],"mealAdvice":{"healthiness":"主食充足。"}}""",1).single()
        val original=Meal(items=listOf(food.copy(needsPortionReview=true)))
        val restored=FoodJson.draft(JSONObject().put("meal",FoodJson.meal(original)).put("batch",JSONObject.NULL).put("editing",false).put("updatedAt",System.currentTimeMillis()).toString())
        assertEquals(original.items.single(),restored.batch!!.results.single())
        assertEquals(original.advice,restored.batch!!.advice)
    }

    @Test fun libraryCapRetainsFavoritesAcrossSaveAndRestart() {
        context.deleteDatabase(name)
        Store(context,name).use {db->val food=FoodStore(db)
            food.favorite(FoodItem(name="咖喱猪排饭",base=Nutrients(650.0)),true)
            food.save(Meal(items=(1..501).map {FoodItem(name="家常菜$it",base=Nutrients(100.0))}))
            assertEquals(501,food.library().size);assertEquals(500,food.library().count {!it.favorite})
        }
        Store(context,name).use {db->val food=FoodStore(db)
            assertEquals("咖喱猪排饭",food.library().first().item.name)
            assertEquals(0L,food.library().first().useCount)
            assertEquals(500,food.library().count {!it.favorite})
        }
    }

}
