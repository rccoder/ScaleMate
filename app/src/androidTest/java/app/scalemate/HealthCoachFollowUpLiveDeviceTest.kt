package app.scalemate

import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.data.Store
import app.scalemate.food.AiClient
import app.scalemate.food.FoodStore
import app.scalemate.health.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opted-in real model loop using synthetic goals/memories and an existing health snapshot. */
class HealthCoachFollowUpLiveDeviceTest {
    @Test fun realModelUsesConfirmedContextAndReturnsOnlyConfirmableCandidates()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveHealthCoachFollowUpChecks")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="app.scalemate.fooddev")
        Store(context).use {db->
            val food=FoodStore(db);val health=HealthStore(db)
            val services=food.services()
            val configured=services.find {it.id==food.setting("text")} ?: services.singleOrNull() ?: error("No selected text service")
            val model=InstrumentationRegistry.getArguments().getString("coachLiveModel").orEmpty()
            val service=if(model.isBlank())configured else configured.copy(model=model)
            require(java.net.URI(service.baseUrl).port!=8765&&!service.model.contains("mock",true))
            val snapshot=health.activeSnapshot() ?: error("No existing Health Connect snapshot")
            val now=Instant.now()
            val zone=ZoneId.of(snapshot.window.zone)
            val today=now.atZone(zone).toLocalDate()
            val profile=HealthCoachProfile(
                focus=HealthCoachFocus.ACTIVITY,minutes=30,goalType=HealthCoachGoalType.STRENGTH,
                target="12 周内提升力量，同时保持体重稳定",days=setOf(1,3,5),
                equipment="哑铃和弹力带",limitations="避免跳跃"
            )
            var memories=listOf(HealthCoachMemory(text="周一三五晚上有空",kind="schedule",createdAt=now.toEpochMilli()))
            var conversation=HealthConversation()
            val questions=listOf(
                "我的数据够支持这个目标吗？还缺什么？",
                "今天需要按周一三五的计划做吗？我只有二十分钟。",
                "这周运动量够不够？你能判断训练质量吗？",
                "周五没空，把周五改到周六，只改这一次。",
                "记住：我不喜欢跑步。"
            )
            val results=JSONArray();val failures=mutableListOf<String>()
            val client=AiClient()
            for((index,question) in questions.withIndex()) {
                val started=System.nanoTime()
                var raw=""
                try {
                    val (messages,input)=HealthPrompt.coachFollowUpMessages(
                        snapshot,health.goals(),"zh",profile,memories,emptyList(),conversation,question,now
                    )
                    raw=client.streamText(service,messages,{},disableReasoning=service.format=="openai"&&service.model.contains("deepseek",true))
                    val reply=HealthPrompt.validateCoachReply(raw,input,today)
                    val record=JSONObject().put("round",index+1).put("question",question)
                        .put("elapsedMs",(System.nanoTime()-started)/1000000)
                        .put("facts",input.facts.size).put("payloadBytes",messages.toString().toByteArray().size)
                        .put("references",JSONArray(reply.references))
                        .put("answer",reply.text)
                        .put("memoryCandidate",reply.memoryCandidate?.let(HealthCoachPlans::memoryCandidate) ?: JSONObject.NULL)
                        .put("planChangeCandidate",reply.planChangeCandidate?.let(HealthCoachPlans::planChangeCandidate) ?: JSONObject.NULL)
                    results.put(record)
                    val user=HealthMessage(role="user",text=question,snapshotId=snapshot.id)
                    val assistant=HealthMessage(
                        role="assistant",text=HealthPrompt.renderCoach(reply.text,input.facts,true).first,
                        snapshotId=snapshot.id,serviceId=service.id,serviceName=service.name,model=service.model,
                        evidenceIds=reply.references,memoryCandidate=reply.memoryCandidate,
                        planChangeCandidate=reply.planChangeCandidate
                    )
                    conversation=conversation.copy(messages=conversation.messages+user+assistant)
                    if(index==3)assertNotNull("Real model did not propose the requested schedule change",reply.planChangeCandidate)
                    if(index==4) {
                        assertNotNull("Real model did not propose a memory candidate",reply.memoryCandidate)
                        reply.memoryCandidate?.let {memories+=HealthCoachPlans.acceptedMemory(it,now.toEpochMilli())}
                    }
                }catch(e:Exception) {
                    failures+="round ${index+1}: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(500)}"
                    results.put(JSONObject().put("round",index+1).put("question",question).put("error",failures.last()).put("raw",raw))
                }
            }
            File(context.filesDir,"coach-follow-up-live-private.json").writeText(JSONObject()
                .put("syntheticProfileAndMemories",true).put("realAI",true).put("model",service.model)
                .put("snapshotFacts",snapshot.evidence.size).put("results",results)
                .put("failures",JSONArray(failures)).toString(2))
            // A deleted memory must not remain in the next request. This uses the same prompt builder
            // as production and records only aggregate context, not private health records.
            memories=memories.filterNot {it.text.contains("跑步")}
            val (messages,input)=HealthPrompt.coachFollowUpMessages(
                snapshot,health.goals(),"zh",profile,memories,emptyList(),conversation,
                "我现在还可以安排跑步吗？",now
            )
            val contextPayload=JSONObject(messages.getJSONObject(messages.length()-1).getString("content"))
            assertEquals(memories.size,contextPayload.getJSONArray("confirmedMemories").length())
            assertFalse(contextPayload.getJSONArray("confirmedMemories").toString().contains("不喜欢跑步"))
            assertTrue(failures.joinToString("\n"),failures.isEmpty())
        }
    }
}
