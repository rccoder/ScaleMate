package app.scalemate

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.data.Store
import app.scalemate.food.AiSecrets
import app.scalemate.food.AiService
import app.scalemate.food.FoodStore
import app.scalemate.health.*
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.LocalDate
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Deterministic loopback AI, real ViewModel and rendered HealthScreen; no provider or user data. */
class HealthCoachFollowUpDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    private data class Request(val path:String,val body:String)
    private class Loopback(val handler:(Request,OutputStream)->Unit):AutoCloseable {
        private val socket=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
        val port get()=socket.localPort
        private val thread=Thread {
            while(!socket.isClosed)try {socket.accept().use {client->
                client.soTimeout=3000
                val input=client.getInputStream()
                fun line():String {
                    val bytes=ByteArrayOutputStream()
                    while(true) {
                        val next=input.read()
                        if(next<0||next=='\n'.code)break
                        if(next!='\r'.code)bytes.write(next)
                    }
                    return bytes.toString(Charsets.UTF_8.name())
                }
                val path=line().split(' ')[1]
                val headers=mutableMapOf<String,String>()
                while(true){val header=line();if(header.isEmpty())break;headers[header.substringBefore(':').lowercase()]=header.substringAfter(':').trim()}
                val size=headers["content-length"]?.toIntOrNull() ?: 0
                val bytes=ByteArray(size);var read=0
                while(read<size){val n=input.read(bytes,read,size-read);if(n<0)break;read+=n}
                handler(Request(path,bytes.toString(Charsets.UTF_8)),client.getOutputStream())
            }}catch(_:java.io.IOException){if(socket.isClosed)break}
        }.apply {isDaemon=true;start()}
        override fun close(){socket.close();thread.join(1500)}
    }

    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val name="coach-follow-up-fixed-${System.nanoTime()}.db"
    @After fun cleanup(){app.deleteDatabase(name)}

    private fun sse(out:OutputStream,answer:JSONObject) {
        val escaped=JSONObject.quote(answer.toString())
        val body="data: {\"choices\":[{\"delta\":{\"content\":$escaped},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        val bytes=body.toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes);out.flush()
    }
    private fun screenshot(name:String) {
        compose.waitForIdle()
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("coach-follow-up"),"$name.png")
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }

    @Test fun fullFollowUpFlowConfirmsMemoryAndPlanThenRemovesDeletedMemoryFromContext() {
        require(app.packageName=="app.scalemate.fooddev")
        val today=LocalDate.now()
        val requests=CopyOnWriteArrayList<JSONObject>()
        Loopback {request,out->
            assertEquals("/v1/chat/completions",request.path)
            val payload=JSONObject(request.body)
            val messages=payload.getJSONArray("messages")
            val user=JSONObject(messages.getJSONObject(messages.length()-1).getString("content"))
            requests+=user
            val answer=when(requests.size) {
                1->JSONObject()
                    .put("text","可以先把目标拆成每周稳定安排，目前还缺少足够数据判断长期效果。")
                    .put("claims",JSONArray())
                    .put("suggestions",JSONArray().put("按当前安排先记录一周。"))
                    .put("memoryCandidate",JSONObject().put("text","不喜欢跑步").put("kind","preference"))
                    .put("proposedPlanChange",JSONObject().put("date",today.plusDays(2).toString())
                        .put("action","把周五安排临时改到周六，散步 20 分钟。").put("minutes",20)
                        .put("days",JSONArray()).put("temporary",true).put("reason","周五没空"))
                2->JSONObject()
                    .put("text","今天按已经确认的安排做，累了就缩短，不需要补做。")
                    .put("claims",JSONArray())
                    .put("suggestions",JSONArray().put("先做十分钟，再决定是否继续。"))
                    .put("memoryCandidate",JSONObject.NULL)
                    .put("proposedPlanChange",JSONObject.NULL)
                else->JSONObject()
                    .put("text","现在只能看到零散记录，暂时不能判断本周运动量是否足够。")
                    .put("claims",JSONArray())
                    .put("suggestions",JSONArray().put("继续记录完整运动时长。"))
            }
            sse(out,answer)
        }.use {server->
            val serviceId="coach-follow-up-fixed"
            val service=AiService(
                id=serviceId,name="Deterministic loopback",baseUrl="http://127.0.0.1:${server.port}/v1",
                model="fixed-coach",encryptedKey=AiSecrets().encrypt(serviceId,"synthetic-key"),format="openai"
            )
            val profile=HealthCoachProfile(
                goalType=HealthCoachGoalType.STRENGTH,target="12 周力量目标",
                days=setOf(1,3,5),minutes=30,equipment="哑铃",limitations="避免跳跃"
            )
            val existing=HealthCoachMemory(text="周一三五晚上有空",kind="schedule")
            val snapshot=HealthSnapshot(
                window=HealthWindow.recent(7),categories=emptySet(),evidence=emptyList(),coverage=emptyList()
            )
            Store(app,name).use {db->
                val health=HealthStore(db)
                health.categories(emptySet());health.snapshot(snapshot);health.setting("activeSnapshot",snapshot.id)
                health.coachProfile(profile);health.coachMemories(listOf(existing))
                health.consent(service,emptySet(),"coach-v3:")
                FoodStore(db).apply {this.service(service);setting("text",service.id)}
            }
            val vm=HealthViewModel(app,databaseName=name)
            compose.activity.runOnUiThread {compose.activity.setContent {
                MaterialTheme(colorScheme=scaleMateColorScheme(false,false)) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.systemBars).padding(16.dp)) {
                            HealthScreen(vm,{},{},{})
                        }
                    }
                }
            }}
            compose.waitUntil(15000){vm.state.value.loaded&&!vm.state.value.busy}

            compose.onNodeWithText("问问教练").performScrollTo().performClick()
            compose.onNodeWithText("我的数据够支持目标吗？").performScrollTo().performClick()
            compose.waitUntil(30000) {
                val state=vm.state.value
                !state.busy&&state.coachConversation.messages.lastOrNull()?.status in setOf("complete","failed","cancelled")
            }
            val firstReply=vm.state.value.coachConversation.messages.last()
            assertEquals(firstReply.text, "complete", firstReply.status)
            assertEquals(1,requests.size)
            assertEquals("12 周力量目标",requests.first().getJSONObject("userReportedContext").getString("target"))
            assertEquals(1,requests.first().getJSONArray("confirmedMemories").length())
            assertEquals(3,requests.first().getJSONObject("weeklyPlan").getJSONArray("days").length())
            screenshot("01-follow-up-candidates")

            compose.onNodeWithText("不喜欢跑步").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("确认记住").performScrollTo().performClick()
            compose.waitUntil(5000){vm.state.value.coachMemories.size==2}
            assertEquals("不喜欢跑步",vm.state.value.coachMemories.last().text)
            compose.onNodeWithText("确认应用").performScrollTo().performClick()
            compose.waitUntil(5000){vm.state.value.coachScheduleChanges.size==1}
            assertEquals(setOf(1,3,5),vm.state.value.coachProfile.days)
            assertEquals(today.plusDays(2).toString(),vm.state.value.coachScheduleChanges.single().date)
            screenshot("02-confirmed-candidates")

            compose.onNodeWithText("今天需要按计划做吗？").performScrollTo().performClick()
            compose.waitUntil(30000){!vm.state.value.busy&&requests.size==2}
            assertEquals(2,requests.last().getJSONArray("confirmedMemories").length())
            screenshot("03-second-turn")

            compose.onNodeWithText("‹ 健康").performScrollTo().performClick()
            compose.onNodeWithText("教练记忆").performScrollTo().performClick()
            screenshot("04-memory-before-delete")
            compose.onAllNodesWithText("忘记").onFirst().performScrollTo().performClick()
            compose.waitUntil(5000){vm.state.value.coachMemories.none {it.text=="不喜欢跑步"}}

            compose.onNodeWithText("‹ 健康").performScrollTo().performClick()
            compose.onNodeWithText("问问教练").performScrollTo().performClick()
            compose.onNodeWithText("这周的运动量够吗？").performScrollTo().performClick()
            compose.waitUntil(30000){!vm.state.value.busy&&requests.size==3}
            val third=requests.last()
            assertTrue(third.getJSONArray("confirmedMemories").length()==1)
            assertFalse(third.getJSONArray("confirmedMemories").toString().contains("不喜欢跑步"))
            screenshot("05-third-turn-after-delete")

            val reopened=HealthViewModel(app,databaseName=name)
            compose.waitUntil(15000){reopened.state.value.loaded}
            assertEquals(profile,reopened.state.value.coachProfile)
            assertEquals(1,reopened.state.value.coachScheduleChanges.size)
            assertTrue(reopened.state.value.coachMemories.none {it.text=="不喜欢跑步"})
            assertEquals(3,reopened.state.value.coachConversation.messages.count {it.role=="assistant"})
            compose.runOnIdle {vm.cancel();reopened.cancel()}
        }
    }
}
