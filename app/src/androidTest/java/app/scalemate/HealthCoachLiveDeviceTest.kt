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

/** Explicitly opted-in, real selected service and existing Health Connect snapshot; no backup or writes. */
class HealthCoachLiveDeviceTest {
    @Test fun selectedServiceRunsARealFeedbackLoop()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveHealthCoachChecks")=="true")
        val context=instrumentation.targetContext
        require(context.packageName=="app.scalemate.fooddev")
        Store(context).use {db->
            val food=FoodStore(db);val health=HealthStore(db)
            val services=food.services()
            val configured=services.find {it.id==food.setting("text")} ?: services.singleOrNull() ?: error("No selected text service")
            val model=InstrumentationRegistry.getArguments().getString("coachLiveModel").orEmpty()
            val service=if(model.isBlank())configured else configured.copy(model=model)
            // A loopback address can be a real authenticated service relay. Reject only the
            // explicit fixture endpoint/model instead of assuming every local service is a mock.
            require(java.net.URI(service.baseUrl).port!=8765&&!service.model.contains("mock",true))
            val snapshot=health.activeSnapshot() ?: error("No existing Health Connect snapshot")
            require(snapshot.evidence.isNotEmpty())
            val now=Instant.now();val today=now.atZone(ZoneId.of(snapshot.window.zone)).toLocalDate()
            val history=mutableListOf<HealthCoachPlan>();val results=JSONArray()
            for(round in 0..2) {
                val profile=if(round==0)HealthCoachProfile(HealthCoachFocus.ACTIVITY,15) else HealthCoachProfile(HealthCoachFocus.ACTIVITY,5,HealthCoachEnergy.TIRED,today.toString())
                val (messages,input)=HealthPrompt.coachMessages(snapshot,health.goals(),"zh",profile,history,now)
                val sentFeedback=JSONObject(messages.getJSONObject(1).getString("content")).getJSONArray("recentActions")
                assertEquals(round,sentFeedback.length())
                val started=System.nanoTime()
                val raw=AiClient().streamText(service,messages,{},disableReasoning=service.format=="openai"&&service.model.contains("deepseek",true))
                val answer=HealthPrompt.validateCoach(raw,input,today)
                assertTrue(answer.generalData||answer.references.isNotEmpty());assertTrue(answer.action.isNotBlank())
                results.put(JSONObject().put("round",round+1).put("elapsedMs",(System.nanoTime()-started)/1000000)
                    .put("facts",input.facts.size).put("payloadBytes",messages.toString().toByteArray().size)
                    .put("feedbackItems",sentFeedback.length())
                    .put("generalData",answer.generalData)
                    .put("observation",if(answer.generalData)context.getString(R.string.health_coach_no_recent_measurements) else HealthPrompt.renderCoach(answer.observation,input.facts,true).first).put("action",answer.action))
                val feedback=if(round==0)HealthCoachFeedback.TOO_HARD else HealthCoachFeedback.DONE
                history.add(0,HealthCoachPlan(HealthMessage(role="assistant",text=answer.observation),today.toString(),answer.action,profile,input.facts.map {it.kind}.toSet(),feedback))
            }
            // This private report must be reviewed locally then deleted, never committed.
            File(context.filesDir,"coach-live-private.json").writeText(JSONObject().put("model",service.model).put("window",HealthJson.window(snapshot.window)).put("results",results).toString(2))
            // Separate synthetic current-day data exercises the current-evidence path. These
            // measurements are invented fixtures, never imported or written to Health Connect.
            val zone=ZoneId.of(snapshot.window.zone)
            val morning=today.atTime(6,0).atZone(zone).toInstant()
            val facts=(1..7).map {offset->
                HealthEvidence(healthHash("synthetic-sleep-$offset").take(24),"SleepSession",today.minusDays(offset.toLong()).toString(),"Synthetic source","sleepMinutes",450.0,"min")
            }+listOf(
                HealthEvidence(healthHash("synthetic-sleep-today").take(24),"SleepSession",today.toString(),"Synthetic source","sleepMinutes",360.0,"min"),
                HealthEvidence(healthHash("synthetic-steps-today").take(24),"Steps",today.toString(),"Synthetic source","count",900.0,"steps",partial=true)
            )
            val current=HealthSnapshot(window=HealthWindow(today.minusDays(7).toString(),today.toString(),zone.id),categories=setOf("Steps","SleepSession"),evidence=facts,coverage=listOf(HealthCoverage("Steps","partial"),HealthCoverage("SleepSession","available")))
            val profile=HealthCoachProfile(HealthCoachFocus.ACTIVITY,5,HealthCoachEnergy.TIRED,today.toString())
            val (messages,input)=HealthPrompt.coachMessages(current,HealthGoals(steps=8000),"zh",profile,now=morning)
            val started=System.nanoTime()
            val raw=AiClient().streamText(service,messages,{},disableReasoning=service.model.contains("deepseek",true))
            val answer=HealthPrompt.validateCoach(raw,input,today)
            assertFalse(answer.generalData);assertTrue(answer.references.isNotEmpty())
            File(context.filesDir,"coach-synthetic-live.json").writeText(JSONObject().put("syntheticFacts",true).put("realAI",true).put("model",service.model)
                .put("elapsedMs",(System.nanoTime()-started)/1000000).put("observation",HealthPrompt.coachObservation(answer.observation)).put("action",answer.action).toString(2))
        }
    }
}
