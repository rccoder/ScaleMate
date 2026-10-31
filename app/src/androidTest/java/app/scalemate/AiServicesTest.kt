package app.scalemate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.data.Store
import app.scalemate.food.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AiServicesTest {
    private data class Reply(val body:String,val status:Int=200)
    private class LocalApi(private val replies:List<Reply>):AutoCloseable {
        private val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val base="http://127.0.0.1:${server.localPort}"
        val requests=Collections.synchronizedList(mutableListOf<List<String>>())
        val bodies=Collections.synchronizedList(mutableListOf<String>())
        private val failure=AtomicReference<Throwable?>()
        private val worker=Thread {
            try {replies.forEach {reply->server.accept().use {socket->
                socket.soTimeout=5000
                val input=socket.getInputStream().buffered()
                fun line():String {val bytes=java.io.ByteArrayOutputStream();while(true){val b=input.read();if(b<0||b==10)break;if(b!=13)bytes.write(b)};return bytes.toString("UTF-8")}
                val lines=mutableListOf<String>();while(true){val value=line();if(value.isEmpty())break;lines+=value}
                requests+=lines
                var remaining=lines.firstOrNull {it.startsWith("Content-Length:",true)}?.substringAfter(':')?.trim()?.toInt() ?: 0
                val body=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192);while(remaining>0){val n=input.read(buffer,0,minOf(buffer.size,remaining));if(n<0)break;body.write(buffer,0,n);remaining-=n}
                bodies+=body.toString("UTF-8")
                val bytes=reply.body.toByteArray()
                socket.getOutputStream().apply {write("HTTP/1.1 ${reply.status} Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nLocation: http://127.0.0.1:1/never-follow\r\n\r\n".toByteArray());write(bytes);flush()}
            }}}catch(e:Throwable){if(!server.isClosed)failure.set(e)}
        }.apply {isDaemon=true;start()}
        fun check(){worker.join(2000);failure.get()?.let {throw AssertionError("Local API failed",it)}}
        override fun close(){server.close();worker.join(2000)}
    }
    private fun service(base:String,format:String):AiService {
        val initial=AiService(baseUrl=base,format=format)
        return initial.copy(encryptedKey=AiSecrets().encrypt(initial.id,"synthetic-local-only"))
    }
    @Test fun modelListingUsesGetAuthPaginationAndDistinctIds()=runBlocking {
        for(format in listOf("openai","anthropic"))LocalApi(listOf(
            Reply("""{"data":[{"id":"model-z","display_name":"Zed"},{"id":"model-z"},{"id":""}],"has_more":true,"last_id":"model/one"}"""),
            Reply("""{"data":[{"id":"model-a","display_name":"Alpha"}],"has_more":false}""")
        )).use {api->
            val result=withTimeout(10000){AiClient().listModels(service(api.base+"/v1",format))}
            assertEquals(listOf("model-a","model-z"),result.models.map {it.id});assertFalse(result.truncated)
            api.check();assertEquals(2,api.requests.size)
            assertTrue(api.requests[0][0].startsWith("GET /v1/models "))
            assertTrue(api.requests[1][0].contains("after_id=model%2Fone"))
            val headers=api.requests[0].joinToString("\n").lowercase()
            if(format=="anthropic") {assertTrue(headers.contains("x-api-key: synthetic-local-only"));assertTrue(headers.contains("anthropic-version: 2023-06-01"));assertFalse(headers.contains("authorization:"))}
            else {assertTrue(headers.contains("authorization: bearer synthetic-local-only"));assertFalse(headers.contains("x-api-key:"))}
        }
    }
    @Test fun unsupportedAuthRedirectAndInvalidListsFailWithoutFollowingRedirects()=runBlocking {
        for((reply,error) in listOf(
            Reply("{}",404) to R.string.ai_models_unsupported,
            Reply("{}",401) to R.string.food_ai_auth,
            Reply("{}",302) to R.string.food_ai_redirect,
            Reply("not json") to R.string.ai_models_invalid,
            Reply("{}") to R.string.ai_models_invalid
        ))LocalApi(listOf(reply)).use {api->
            try {withTimeout(10000){AiClient().listModels(service(api.base+"/v1","openai"))};fail("Invalid model endpoint accepted")}
            catch(e:FoodFailure){assertEquals(error,e.resource)}
            api.check();assertEquals(1,api.requests.size)
        }
        LocalApi(listOf(Reply("""{"data":[]}"""))).use {api->assertTrue(AiClient().listModels(service(api.base+"/v1","openai")).models.isEmpty())}
    }
    @Test fun recognitionSavesFinalAiIntakeWithoutSecondDivision()=runBlocking {
        LocalApi(listOf(Reply("""{"choices":[{"finish_reason":"stop","message":{"content":"{\"calculationMode\":\"personal\",\"foods\":[{\"name\":\"Curry pork rice\",\"personalGrams\":260,\"personalNutrients\":{\"kcal\":360},\"shareCount\":2,\"consumedFraction\":0.5,\"intakeSummary\":\"Full allocated share for two diners.\"}]}"}}]}"""))).use {api->
            val configured=service(api.base+"/v1","openai").copy(images=true,model="local-model")
            val item=withTimeout(10000){AiClient().recognize(configured,listOf(byteArrayOf(1)),"",emptySet(),2,outputLanguage="en-US")}.single()
            assertEquals(360.0,item.nutrients.kcal!!,0.001);assertEquals(260.0,item.grams!!,0.001)
            assertEquals(1.0,item.factor,0.0);assertEquals("personal",item.estimateContext!!.resultMode)
            api.check();assertTrue(api.requests.single().first().startsWith("POST /v1/chat/completions "))
        }
    }
    @Test fun recognitionParsesMealAdviceAndProviderUsage()=runBlocking {
        LocalApi(listOf(Reply("""{"choices":[{"finish_reason":"stop","message":{"content":"{\"calculationMode\":\"personal\",\"foods\":[{\"name\":\"Curry pork rice\",\"personalNutrients\":{\"kcal\":360}}],\"mealAdvice\":{\"healthiness\":\"A grain-and-protein meal.\",\"balance\":\"Add vegetables.\",\"nextTime\":\"Choose a less oily main.\",\"note\":\"Oil is uncertain.\"}}"}}],"usage":{"prompt_tokens":1234,"completion_tokens":321,"total_tokens":1555}}"""))).use {api->
            val configured=service(api.base+"/v1","openai").copy(images=true,model="local-model")
            val result=withTimeout(10000){AiClient().recognizeDetailed(configured,listOf(byteArrayOf(1)),"",emptySet(),1,outputLanguage="en-US")}
            assertEquals("Add vegetables.",result.advice.balance)
            assertEquals(1234L,result.usage.inputTokens);assertEquals(321L,result.usage.outputTokens);assertEquals(1555L,result.usage.totalTokens)
        }
    }
    @Test fun revisionRequestCarriesEditablePreviousResultAndCurrentInstructions()=runBlocking {
        LocalApi(listOf(Reply("""{"choices":[{"finish_reason":"stop","message":{"content":"{\"calculationMode\":\"personal\",\"foods\":[{\"name\":\"咖喱猪排饭\",\"personalGrams\":130,\"personalNutrients\":{\"kcal\":325}}]}"}}]}"""))).use {api->
            val configured=service(api.base+"/v1","openai").copy(images=true,model="local-model")
            val previous=FoodItem(
                name="咖喱猪排饭",portion="260 g",grams=260.0,base=Nutrients(650.0,28.0,80.0,22.0),
                quantity=0.5,photos=listOf("meal.jpg"),assumptions="按可见份量估算。",
                estimateContext=FoodEstimateContext(
                    shareCount=2,tags=listOf("少酱"),description="两人均分",
                    interpretation="两人均分后只吃自己那份的一半。",resultMode="personal",
                    summary="计入本人分到份额的一半。"
                )
            )
            val advice=MealAdvice("主食和蛋白质为主。","酱汁按需蘸取。","下次搭配蔬菜。","蔬菜被遮挡。")
            val result=withTimeout(10000){
                AiClient().recognizeDetailed(
                    configured,listOf(byteArrayOf(1)),"这次米饭再少一半",setOf("少油"),1,
                    listOf("meal.jpg"),outputLanguage="zh-CN",previous=listOf(previous),previousAdvice=advice
                )
            }
            assertEquals("咖喱猪排饭",result.items.single().name)
            api.check();assertEquals(1,api.bodies.size)
            val body=org.json.JSONObject(api.bodies.single())
            val messages=body.getJSONArray("messages")
            val system=messages.getJSONObject(0).getString("content")
            assertTrue(system.contains("REVISION MODE"))
            val user=messages.getJSONObject(1).getJSONArray("content")
            val texts=(0 until user.length()).mapNotNull {index->
                user.getJSONObject(index).takeIf {it.optString("type")=="text"}?.getString("text")
            }
            assertTrue(texts.any {it.contains("这次米饭再少一半")&&it.contains("少油")})
            val previousText=texts.single {it.startsWith("Previous analysis")}.substringAfter('\n')
            val previousJson=org.json.JSONObject(previousText)
            val previousFood=previousJson.getJSONArray("foods").getJSONObject(0)
            assertEquals(130.0,previousFood.getDouble("personalGrams"),0.001)
            assertEquals(325.0,previousFood.getJSONObject("personalNutrients").getDouble("kcal"),0.001)
            assertEquals("两人均分",previousJson.getJSONObject("previousFoodContext").getString("description"))
            assertEquals("计入本人分到份额的一半。",previousFood.getString("intakeSummary"))
            assertEquals("下次搭配蔬菜。",previousJson.getJSONObject("mealAdvice").getString("nextTime"))
        }
    }
    @Test fun copiedCredentialsHaveIndependentIdentityAndDoNotReplaceDefaults() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database="test-ai-services-copy.db";context.deleteDatabase(database)
        try {
            val secrets=AiSecrets()
            val original=service("https://example.com/v1","anthropic").copy(name="已有服务",model="model-a",images=true,version=4,consentVersion=4)
            val copied=secrets.duplicate(original,"已有服务（副本）")
            assertNotEquals(original.id,copied.id);assertNotEquals(original.encryptedKey,copied.encryptedKey)
            assertEquals(secrets.decrypt(original),secrets.decrypt(copied));assertEquals(1L,copied.version);assertEquals(0L,copied.consentVersion)
            assertEquals(original.baseUrl,copied.baseUrl);assertEquals(original.format,copied.format);assertEquals(original.images,copied.images)
            try {secrets.decrypt(copied.copy(id=original.id));fail("Copied cipher accepted wrong identity")}catch(_:javax.crypto.AEADBadTagException){}
            Store(context,database).use {db->val store=FoodStore(db)
                store.service(original);store.setting("vision",original.id);store.setting("text",original.id)
                assertEquals(1,store.services().size)
                store.service(copied.copy(model="model-b"));assertEquals(2,store.services().size)
                assertEquals(original,store.services().single {it.id==original.id})
                assertEquals(original.id,store.setting("vision"));assertEquals(original.id,store.setting("text"))
                store.deleteService(copied.id);assertEquals(listOf(original),store.services())
            }
        }finally {context.deleteDatabase(database)}
    }
}
