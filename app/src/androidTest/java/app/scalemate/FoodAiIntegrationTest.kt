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
    private data class PromptBaseline(val name:String,val expectedGroups:Set<Set<String>>,val forbidden:Set<String>)
    private val promptBaseline=listOf(
        PromptBaseline("kung-pao-chicken-with-rice",setOf(setOf("宫保鸡丁","米饭")),setOf("花生","辣椒","葱")),
        PromptBaseline("tomato-egg-with-rice",setOf(setOf("番茄炒蛋","米饭")),setOf("番茄","鸡蛋")),
        PromptBaseline("cheeseburger-with-fries",setOf(setOf("芝士汉堡","薯条"),setOf("汉堡薯条套餐")),emptySet()),
        PromptBaseline("bacon-eggs-english-muffin",setOf(setOf("炒蛋","培根","英式松饼"),setOf("美式早餐拼盘")),emptySet())
    )
    private val promptWords=listOf(
        "宫保鸡丁" to "kung pao chicken","米饭" to "rice","花生" to "peanut","辣椒" to "chili","葱" to "scallion",
        "番茄炒蛋" to "tomato scrambled eggs","番茄" to "tomato","鸡蛋" to "egg","芝士汉堡" to "cheeseburger",
        "汉堡薯条套餐" to "burger and fries","薯条" to "fries","炒蛋" to "scrambled eggs","培根" to "bacon",
        "英式松饼" to "english muffin","美式早餐拼盘" to "american breakfast plate"
    )
    private fun countWord(text:String,word:String):Int = Regex(Regex.escape(word)).findAll(text.lowercase()).count()
    private fun promptWord(zh:String,language:String)=if(language=="zh-CN")zh else promptWords.toMap()[zh] ?: zh
    private fun recognizedNames(asset:String,language:String):List<String> = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val privateFixture=File(context.filesDir,"ai-validation-private.json")
        assumeTrue("Private AI test fixture is not provisioned",privateFixture.exists())
        var processed:File?=null
        var source:File?=null
        try {
            val cfg=JSONObject(privateFixture.readText())
            val secrets=AiSecrets()
            val photo=context.assets.open("food-prompt-baseline/$asset.png").use {it.readBytes()}
            source=File(context.cacheDir,"prompt-baseline-$asset.png").apply {writeBytes(photo)}
            processed=FoodImages.import(context,Uri.fromFile(source))
            source.delete();source=null
            val format="openai"
            val base=cfg.getString("baseUrl")
            val service=AiService(name="Prompt baseline",baseUrl=base,model="codex/gpt-6-luna",images=true,format=format)
                .let {it.copy(encryptedKey=secrets.encrypt(it.id,cfg.getString("key")))}
            val items=withTimeout(90000){AiClient(secrets).recognize(service,listOf(processed!!.readBytes()),"",emptySet(),1,outputLanguage=language)}
            assertTrue("Expected actual food estimates for $asset",items.isNotEmpty())
            assertTrue("Personal intake contract changed for $asset",items.all {
                it.needsPortionReview&&it.base.valid()&&it.base.hasValue()&&it.factor==1.0&&
                    it.estimateContext?.resultMode=="personal"&&it.estimateContext?.reportedNutrients==it.base
            })
            items.map {it.name}
        }finally {processed?.delete();source?.delete()}
    }
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
            assertTrue(processed!!.length() in 1..FoodImages.MAX_IMAGE_BYTES)
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
    @Test fun privateFixturePromptBaselineChineseAndEnglish()=runBlocking {
        val fixture=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"ai-validation-private.json")
        assumeTrue("Private AI test fixture is not provisioned",fixture.exists())
        try {
            for(language in listOf("zh-CN","en-US")) {
                val words=promptWords.map {promptWord(it.first,language)}
                val han=Regex("[\u4e00-\u9fff]")
                for(sample in promptBaseline) {
                    val names=recognizedNames(sample.name,language)
                    val normalized=names.map {name->
                        words.filter {countWord(name,it)>0}.maxByOrNull {countWord(name,it)*it.length}.orEmpty()
                    }.toSet()
                    val expected=sample.expectedGroups.map {group->group.map {promptWord(it,language)}.toSet()}.toSet()
                    assertTrue("Unexpected grouping for ${sample.name}: $names",normalized in expected)
                    val forbidden=sample.forbidden.map {promptWord(it,language)}.toSet()
                    assertFalse("Ingredient split for ${sample.name}: $names",normalized.any {it in forbidden})
                    if(language=="en-US")assertFalse("English output contains Chinese: $names",names.any {han.containsMatchIn(it)})
                    InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {putString("stream","$language ${sample.name}: ${names.joinToString(" | ")}\n")})
                }
            }
        }finally {fixture.delete()}
    }
    @Test fun privateFixtureRevisionUsesPreviousResult()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val fixture=File(context.filesDir,"ai-validation-private.json")
        assumeTrue("Private AI test fixture is not provisioned",fixture.exists())
        var processed:File?=null
        var source:File?=null
        try {
            val cfg=JSONObject(fixture.readText())
            val secrets=AiSecrets()
            val photo=context.assets.open("food-prompt-baseline/kung-pao-chicken-with-rice.png").use {it.readBytes()}
            source=File(context.cacheDir,"revision-baseline.png").apply {writeBytes(photo)}
            processed=FoodImages.import(context,Uri.fromFile(source))
            source.delete();source=null
            val service=AiService(
                name="Revision baseline",baseUrl=cfg.getString("baseUrl"),model="codex/gpt-6-luna",
                images=true,format="openai"
            ).let {it.copy(encryptedKey=secrets.encrypt(it.id,cfg.getString("key")))}
            val client=AiClient(secrets)
            val first=withTimeout(90000){
                client.recognizeDetailed(service,listOf(processed!!.readBytes()),"",emptySet(),1,outputLanguage="zh-CN")
            }
            val firstNameText=first.items.joinToString(" "){it.name}
            assertTrue("Baseline did not identify rice: $firstNameText",countWord(firstNameText,"米饭")>0)
            val revised=withTimeout(90000){
                client.recognizeDetailed(
                    service,listOf(processed!!.readBytes()),
                    "本次只改一处：不要米饭，只保留宫保鸡丁；没有提到的内容保持不变。",
                    emptySet(),1,outputLanguage="zh-CN",previous=first.items,previousAdvice=first.advice
                )
            }
            val revisedNameText=revised.items.joinToString(" "){it.name}
            assertTrue("Revision dropped the retained food: $revisedNameText",countWord(revisedNameText,"宫保鸡丁")>0)
            assertFalse("Revision kept a food explicitly removed by the new request: $revisedNameText",countWord(revisedNameText,"米饭")>0)
            assertEquals("Revision duplicated the retained food: $revisedNameText",1,revised.items.count {countWord(it.name,"宫保鸡丁")>0})
            instrumentation.sendStatus(2,android.os.Bundle().apply {putString("stream","revision mode: $revisedNameText\n")})
        }finally {fixture.delete();processed?.delete();source?.delete()}
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
