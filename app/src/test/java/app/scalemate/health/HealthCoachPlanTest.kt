package app.scalemate.health

import java.time.Instant
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HealthCoachPlanTest {
    private val today=LocalDate.parse("2026-11-02")
    private val now=Instant.parse("2026-11-02T02:00:00Z")
    private fun plan(day:String="2026-11-02",feedback:HealthCoachFeedback=HealthCoachFeedback.PROPOSED,id:String=healthId())=HealthCoachPlan(
        HealthMessage(id=id,role="assistant",text="Recorded activity.",createdAt=now.toEpochMilli()),day,
        "After lunch, take a comfortable walk.",HealthCoachProfile(),setOf("Steps"),feedback
    )
    private fun snapshot():HealthSnapshot {
        val rows=(1..31).map {day->HealthEvidence(healthHash("steps-$day").take(24),"Steps","2026-10-${day.toString().padStart(2,'0')}","Source","count",5000.0,"steps")}
        return HealthSnapshot(window=HealthWindow("2026-10-26","2026-11-02","Asia/Shanghai"),categories=setOf("Steps"),evidence=rows,coverage=listOf(HealthCoverage("Steps","available")))
    }
    @Test fun completedDaysAreSelfReportedUniqueDatesNotGenerationsOrAcceptedPlans() {
        val plans=listOf(plan(feedback=HealthCoachFeedback.DONE),plan(feedback=HealthCoachFeedback.DONE),
            plan("2026-11-01",HealthCoachFeedback.ACCEPTED),plan("2026-10-31",HealthCoachFeedback.DONE),
            plan("2026-10-25",HealthCoachFeedback.DONE),plan("2026-11-03",HealthCoachFeedback.DONE))
        assertEquals(2,HealthCoachPlans.completedDays(plans,today))
        assertEquals(4,HealthCoachPlans.recent(plans,today).size)
    }
    @Test fun tiredContextExpiresRatherThanInventingLongTermFatigue() {
        val profile=HealthCoachProfile(HealthCoachFocus.ACTIVITY,5,HealthCoachEnergy.TIRED,today.toString())
        assertEquals(HealthCoachEnergy.TIRED,profile.forDay(today).energy)
        assertEquals(HealthCoachEnergy.NORMAL,profile.forDay(today.plusDays(1)).energy)
        assertEquals(5,profile.forDay(today.plusDays(1)).minutes)
    }
    @Test fun planRoundTripPreservesActionEvidenceProfileAndFeedback() {
        val plan=plan(feedback=HealthCoachFeedback.TOO_HARD).copy(profile=HealthCoachProfile(HealthCoachFocus.SLEEP,5),evidence=snapshot().evidence.take(1))
        assertEquals(plan,HealthCoachPlans.plan(HealthCoachPlans.json(plan)))
    }
    @Test fun payloadIncludesUserContextButOmitsExpiredAndRevokedFeedback() {
        val profile=HealthCoachProfile(HealthCoachFocus.ACTIVITY,5,HealthCoachEnergy.TIRED,today.toString())
        val history=listOf(plan(feedback=HealthCoachFeedback.TOO_HARD),plan("2026-10-25"),plan().copy(categories=setOf("Weight"),action="Private revoked context"))
        val (messages,_)=HealthPrompt.coachMessages(snapshot(),HealthGoals(),"en",profile,history,now)
        val payload=JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals("2026-11-02",payload.getString("today"))
        assertEquals("ACTIVITY",payload.getJSONObject("userReportedContext").getString("focus"))
        assertEquals(5,payload.getJSONObject("userReportedContext").getInt("minutes"))
        assertEquals(1,payload.getJSONArray("recentActions").length())
        assertEquals("TOO_HARD",payload.getJSONArray("recentActions").getJSONObject(0).getString("feedback"))
        assertFalse(payload.toString().contains("Private revoked context"))
    }
    @Test fun compactSegmentReferencesAlwaysResolveToSentAliases() {
        val (_,input)=HealthPrompt.coachMessages(snapshot(),HealthGoals(),"en",now=now)
        assertTrue(input.grouped.isNotEmpty())
        val ids=input.facts.map {it.id}.toSet()
        assertTrue(input.grouped.flatMap {HealthJson.strings(it.getJSONArray("factIds"))}.all {it in ids})
    }
    @Test fun actionIsSeparateFromObservationAndUnreferencedMeasurementIsRejected() {
        val (_,input)=HealthPrompt.coachMessages(snapshot(),HealthGoals(),"en",now=now)
        val id=input.facts.first().id
        val raw=JSONObject().put("text","Recent activity is recorded [$id].").put("claims",JSONArray())
            .put("suggestions",JSONArray().put("After lunch, walk comfortably for 5 minutes."))
        val result=HealthPrompt.validateCoach(raw.toString(),input)
        assertFalse(result.observation.contains("After lunch"))
        assertTrue(result.action.contains("5 minutes"))
        raw.put("suggestions",JSONArray().put("You need 9000 steps today."))
        assertThrows(IllegalArgumentException::class.java){HealthPrompt.validateCoach(raw.toString(),input)}
    }
    @Test fun coachRejectsMissingOrMultipleActionsInsteadOfSavingAnUntrackableReport() {
        val (_,input)=HealthPrompt.coachMessages(snapshot(),HealthGoals(),"en",now=now)
        val id=input.facts.first().id
        for(actions in listOf(JSONArray(),JSONArray().put("Walk comfortably.").put("Go to bed."))) {
            val raw=JSONObject().put("text","Recent activity [$id].").put("suggestions",actions).toString()
            assertThrows(IllegalArgumentException::class.java){HealthPrompt.validateCoach(raw,input)}
        }
    }
    @Test fun nutritionMediansCannotBeMisrepresentedAsDailyRecordedIntake() {
        val s=snapshot().copy(categories=setOf("Nutrition"),evidence=listOf(HealthEvidence(
            "abcdef123456abcdef123456","Nutrition","2026-11-01","Source","energy",600.0,"kcal",algorithm="daily-median-v1"
        )))
        val (_,input)=HealthPrompt.coachMessages(s,HealthGoals(),"en",now=now)
        assertTrue(input.facts.isEmpty())
    }
    @Test fun staleFatigueAndInventedActionReferencesCannotLeakIntoTheNextGeneration() {
        val profile=HealthCoachProfile(energy=HealthCoachEnergy.TIRED,energyDate=today.minusDays(1).toString())
        val (messages,input)=HealthPrompt.coachMessages(snapshot(),HealthGoals(),"en",profile,now=now)
        assertEquals("NORMAL",JSONObject(messages.getJSONObject(1).getString("content")).getJSONObject("userReportedContext").getString("energy"))
        val raw=JSONObject().put("text","Activity is recorded [${input.facts.first().id}].")
            .put("suggestions",JSONArray().put("Take a comfortable walk [ffffffff].")).toString()
        assertThrows(IllegalArgumentException::class.java){HealthPrompt.validateCoach(raw,input)}
    }
    @Test fun historicalMeasurementRecencyAndRotatedSleepClockAreExplicit() {
        val fact=HealthEvidence("abcdef123456abcdef123456","SleepSession","2026-10-31","Source","sessionStartAfterLocalNoon",960.0,"min after noon")
        val snapshot=snapshot().copy(categories=setOf("SleepSession"),evidence=listOf(fact))
        val (messages,input)=HealthPrompt.coachMessages(snapshot,HealthGoals(),"en",now=now)
        val payload=JSONObject(messages.getJSONObject(1).getString("content"))
        assertFalse(payload.getBoolean("hasRecentMeasurements"))
        assertEquals(2,payload.getJSONObject("measurementRecency").getJSONObject("SleepSession").getInt("daysOld"))
        assertEquals("04:00",JSONObject(input.facts.single().detail).getString("sleepStartLocalClock"))
        assertEquals("early_morning",JSONObject(input.facts.single().detail).getString("sleepStartPartOfDay"))
    }
    @Test fun inlineReferencesRenderOneAuthoritativeUnit() {
        val fact=HealthEvidence("abcdef123456abcdef123456","Steps","2026-11-02","Source","count",6800.0,"steps")
        assertEquals("已记录 6,800 步。",HealthPrompt.renderCoach("已记录 [${fact.id}]步。",listOf(fact),true).first)
        assertEquals("这是部分记录，不能据此判断全天活动不足。",HealthPrompt.coachObservation("这是部分记录，不能据此判断全天活动不足 [${fact.id}]步。"))
    }
    @Test fun historicalOnlyDataUsesATrustedLocalObservationRatherThanRejectingAGeneralHabit() {
        val (_,input)=HealthPrompt.coachMessages(snapshot(),HealthGoals(),"en",now=now)
        val raw=JSONObject().put("text","No recent measurements are available.").put("suggestions",JSONArray().put("After lunch, take a comfortable walk.")).toString()
        assertTrue(HealthPrompt.validateCoach(raw,input,today).generalData)
        val recent=input.copy(facts=input.facts.map {it.copy(date=today.toString())})
        assertThrows(IllegalArgumentException::class.java){HealthPrompt.validateCoach(raw,recent,today)}
        val untrusted=JSONObject(raw).put("text","Your activity today is 9000 steps.").toString()
        assertEquals("No recent measurements are available.",HealthPrompt.validateCoach(untrusted,input,today).observation)
    }
    @Test fun updatingAnInsightDoesNotReplaceACompletedActionOrInflateProgress() {
        val done=plan(feedback=HealthCoachFeedback.DONE)
        val next=HealthCoachPlans.generated(HealthMessage(role="assistant",text="Updated observation."),today,
            "Do more exercise.",done.profile,done.categories,listOf(done))
        assertEquals(done.action,next.action)
        assertEquals(done.message.id,next.message.id)
        assertEquals(HealthCoachFeedback.DONE,next.feedback)
        assertEquals(1,HealthCoachPlans.recent(listOf(next,done),today).size)
        val newGoal=HealthCoachPlans.generated(next.message,today,"Prepare for sleep.",HealthCoachProfile(HealthCoachFocus.SLEEP),done.categories,listOf(done))
        assertEquals(HealthCoachFeedback.PROPOSED,newGoal.feedback)
    }
}
