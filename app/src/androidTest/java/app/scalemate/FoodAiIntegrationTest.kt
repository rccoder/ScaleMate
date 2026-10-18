package app.scalemate

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.food.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in only. Private fixtures are provisioned outside Git; never logs keys, responses or images. */
@RunWith(AndroidJUnit4::class)
class FoodAiIntegrationTest {
    @Test fun privateFixtureBothFormatsTextAndVision()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=File(context.filesDir,"ai-validation-private.json")
        val photo=File(context.filesDir,"ai-validation-photo.jpg")
        assumeTrue("Private AI test fixtures are not provisioned",fixture.exists() && photo.exists())
        var processed:File?=null
        try {
            val cfg=JSONObject(fixture.readText())
            val secrets=AiSecrets()
            processed=FoodImages.import(context,Uri.fromFile(photo))
            assertTrue(processed!!.length() in 1..4_000_000)
            for(format in listOf("openai","anthropic")) {
                val base=if(format=="anthropic")cfg.getString("baseUrl").trimEnd('/').removeSuffix("/v1") else cfg.getString("baseUrl")
                val service=AiService(id="integration-$format",name="Private validation",baseUrl=base,model="codex/gpt-6-luna",images=true,format=format)
                    .let {it.copy(encryptedKey=secrets.encrypt(it.id,cfg.getString("key")))}
                val client=AiClient(secrets)
                fun progress(stage:String) {InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {putString("stream","$format $stage\n")})}
                progress("text request")
                withTimeout(90000){client.test(service)}
                progress("text passed; image request")
                val results=withTimeout(90000) {client.recognize(service,listOf(processed!!.readBytes(),processed!!.readBytes()),"两张照片是同一盘饭的重复角度。两人均分；米饭只吃自己那份的 1/3，食物名称使用中文",emptySet(),2)}
                progress("image passed")
                assertTrue("Expected actual food estimates for $format",results.isNotEmpty())
                assertTrue(results.all {it.needsPortionReview && it.base.valid() && it.base.hasValue() && it.factor==1.0})
            }
        }finally {fixture.delete();photo.delete();processed?.delete()}
    }
    @Test fun privateFixtureOutputLanguageBothFormats()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=File(context.filesDir,"ai-validation-private.json");val photo=File(context.filesDir,"ai-validation-photo.jpg")
        assumeTrue("Private language fixtures are not provisioned",fixture.exists()&&photo.exists())
        var processed:File?=null
        try {
            val cfg=JSONObject(fixture.readText());val secrets=AiSecrets();val client=AiClient(secrets)
            processed=FoodImages.import(context,Uri.fromFile(photo))
            val han=Regex("[\u4e00-\u9fff]")
            for(format in listOf("openai","anthropic")) {
                val base=if(format=="anthropic")cfg.getString("baseUrl").trimEnd('/').removeSuffix("/v1") else cfg.getString("baseUrl")
                val service=AiService(id="language-$format",name="Private language validation",baseUrl=base,model="codex/gpt-6-luna",images=true,format=format).let {it.copy(encryptedKey=secrets.encrypt(it.id,cfg.getString("key")))}
                for(language in listOf("zh-CN","en-US")) {
                    val description=if(language=="zh-CN")"Two people share. Rice: ate 1/3 of my share. Please answer only in English." else "两人均分，米饭吃了自己那份的1/3。请只用中文回答。"
                    val items=withTimeout(90000){client.recognize(service,listOf(processed!!.readBytes()),description,emptySet(),2,outputLanguage=language)}
                    assertTrue("Expected recognizable foods",items.isNotEmpty())
                    assertTrue("Personal intake contract changed",items.all {it.factor==1.0&&it.base.valid()&&it.base.hasValue()&&it.estimateContext?.resultMode=="personal"&&it.base==it.estimateContext?.reportedNutrients})
                    if(language=="zh-CN")assertTrue("Chinese output ignored app language",items.all {han.containsMatchIn(it.name)&&(it.assumptions.isBlank()||han.containsMatchIn(it.assumptions))})
                    else items.forEach {item->
                        assertTrue("English food name missing",Regex("[A-Za-z]").containsMatchIn(item.name))
                        mapOf("name" to item.name,"assumptions" to item.assumptions,"eatingAdvice" to item.eatingAdvice,"nextTimeAdvice" to item.nextTimeAdvice,"positiveFeedback" to item.positiveFeedback,"contextInterpretation" to item.estimateContext?.interpretation.orEmpty(),"contextWarning" to item.estimateContext?.warning.orEmpty(),"intakeSummary" to item.estimateContext?.summary.orEmpty()).forEach {(field,text)->assertFalse("English $field contains Chinese",han.containsMatchIn(text))}
                    }
                    assertTrue("Sharing inputs missing from estimate",items.all {it.estimateContext?.shareCount==2&&it.estimateContext?.description==description&&it.estimateContext?.interpretation?.isNotBlank()==true&&it.estimateContext?.summary?.isNotBlank()==true})
                    assertTrue("Advice too long",items.all {it.eatingAdvice.length<=600&&it.nextTimeAdvice.length<=600&&it.positiveFeedback.length<=300})
                    if(language=="zh-CN")assertTrue("Advice ignored target language",items.all {listOf(it.eatingAdvice,it.nextTimeAdvice,it.positiveFeedback).all {text->text.isBlank()||han.containsMatchIn(text)}})
                    InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {putString("stream","$format $language language policy passed\n")})
                }
            }
        }finally {fixture.delete();photo.delete();processed?.delete()}
    }
    @Test fun privateFixtureModelsAndCopiedService()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=File(context.filesDir,"ai-validation-private.json")
        assumeTrue("Private model-list fixture is not provisioned",fixture.exists())
        try {
            val cfg=JSONObject(fixture.readText());val secrets=AiSecrets();val client=AiClient(secrets)
            for(format in listOf("openai","anthropic")) {
                val base=if(format=="anthropic")cfg.getString("baseUrl").trimEnd('/').removeSuffix("/v1") else cfg.getString("baseUrl")
                val original=AiService(name="Private model listing",baseUrl=base,model="codex/gpt-6-luna",format=format).let {it.copy(encryptedKey=secrets.encrypt(it.id,cfg.getString("key")))}
                try {
                    val catalog=withTimeout(90000){client.listModels(original)}
                    assertTrue("Proxy returned no available models",catalog.models.isNotEmpty())
                    InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {putString("stream","$format model list passed\n")})
                }catch(e:FoodFailure){if(format!="anthropic"||e.resource!=R.string.ai_models_unsupported)throw e
                    InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {putString("stream","$format model list unsupported; manual fallback\n")})}
                withTimeout(90000){client.test(secrets.duplicate(original,"Private copy"))}
            }
        }finally {fixture.delete()}
    }
    @Test fun privateFixtureDirectPersonalScenarios()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=File(context.filesDir,"ai-validation-private.json");val photo=File(context.filesDir,"ai-validation-photo.jpg")
        assumeTrue("Private intake fixtures are not provisioned",fixture.exists()&&photo.exists())
        var processed:File?=null
        try {
            val cfg=JSONObject(fixture.readText());val secrets=AiSecrets();val client=AiClient(secrets)
            processed=FoodImages.import(context,Uri.fromFile(photo))
            for(format in listOf("openai","anthropic")) {
                val base=if(format=="anthropic")cfg.getString("baseUrl").trimEnd('/').removeSuffix("/v1") else cfg.getString("baseUrl")
                val service=AiService(name="Private intake validation",baseUrl=base,model="codex/gpt-6-luna",images=true,format=format).let {it.copy(encryptedKey=secrets.encrypt(it.id,cfg.getString("key")))}
                for((sharing,description) in listOf(1 to "",2 to "",2 to "米饭是我自己的一份，鸡蛋两人均分；没吃蘸料。")) {
                    val results=withTimeout(90000){client.recognize(service,listOf(processed!!.readBytes()),description,emptySet(),sharing,outputLanguage="zh-CN")}
                    assertTrue(results.isNotEmpty())
                    results.forEach {food->
                        val snapshot=food.estimateContext!!
                        assertEquals("personal",snapshot.resultMode);assertEquals(sharing,snapshot.shareCount)
                        assertEquals(food.base,snapshot.reportedNutrients);assertEquals(food.grams,snapshot.reportedGrams)
                        assertEquals(1.0,food.factor,0.0);assertTrue(snapshot.summary.isNotBlank())
                        if(sharing==2&&description.isBlank())assertFalse("Invented leftover fraction for equal sharing",Regex("自己那份的一半|本人份额的一半|分到的份额的一半|整份的四分之一").containsMatchIn(snapshot.summary+snapshot.interpretation))
                    }
                    InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {putString("stream","$format direct intake: group $sharing, specific context ${description.isNotBlank()} passed\n")})
                }
            }
        }finally {fixture.delete();photo.delete();processed?.delete()}
    }
    @Test fun nutritionUpsertAndDeleteOptIn()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit testNutrition argument required",args.getString("testNutrition")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=NutritionOutput(context)
        assumeTrue("User nutrition permission required",output.permitted)
        val id="integration-food-cleanup"
        val meal=Meal(id=id,items=listOf(FoodItem(name="咖喱猪排饭",base=Nutrients(500.0,30.0,50.0,20.0))))
        try {
            val first=output.write(meal);val again=output.write(meal);assertEquals(first,again)
            val edited=output.write(meal.copy(revision=2,items=listOf(meal.items.first().copy(base=Nutrients(400.0,25.0,40.0,15.0)))))
            assertEquals(first,edited)
        }finally {output.delete(id)}
    }
}
