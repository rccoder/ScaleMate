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
            plan("2026-10-30",HealthCoachFeedback.PARTIAL),
            plan("2026-10-25",HealthCoachFeedback.DONE),plan("2026-11-03",HealthCoachFeedback.DONE))
        assertEquals(2,HealthCoachPlans.completedDays(plans,today))
        assertEquals(5,HealthCoachPlans.recent(plans,today).size)
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
    @Test fun followUpPayloadMakesTodayScheduleDeterministic() {
        val (messages,_)=HealthPrompt.coachFollowUpMessages(
            snapshot(),HealthGoals(),"zh",HealthCoachProfile(days=setOf(1,3,5)),emptyList(),emptyList(),
            HealthConversation(),"今天按计划吗",now
        )
        val payload=JSONObject(messages.getJSONObject(messages.length()-1).getString("content"))
        assertEquals(1,payload.getInt("todayWeekday"))
        assertTrue(payload.getBoolean("todayScheduled"))
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
    @Test fun profileRoundTripKeepsGoalScheduleEquipmentAndLimits() {
        val profile=HealthCoachProfile(
            HealthCoachFocus.ACTIVITY,30,HealthCoachEnergy.NORMAL,"",
            HealthCoachGoalType.STRENGTH,"12 周力量目标",setOf(1,3,5),"哑铃、弹力带","避免跳跃",
            baseline="每周 1 次",deadline="2027-01-31",successCriteria="每周稳定完成 3 次"
        )
        assertEquals(profile,HealthCoachPlans.profile(HealthCoachPlans.profile(profile)))
    }
    @Test fun localActionRespectsTheGoalInsteadOfForcingMinutesOntoEveryHabit() {
        val sleep=HealthCoachPlans.localAction(HealthCoachProfile(goalType=HealthCoachGoalType.SLEEP),true)
        assertEquals("SLEEP",sleep.kind)
        assertTrue(sleep.action.contains("上床"))
        assertFalse(sleep.action.contains("分钟"))
        val strength=HealthCoachPlans.localAction(HealthCoachProfile(minutes=30,goalType=HealthCoachGoalType.STRENGTH),true)
        assertEquals("STRENGTH",strength.kind)
        assertTrue(strength.action.contains("30 分钟"))
        assertTrue(strength.fallback.isNotBlank())
    }
    @Test fun weekReviewUsesScheduledDatesAndCanRecommendAnEasierWeek() {
        val profile=HealthCoachProfile(days=setOf(1,3,5),minutes=30)
        val plans=listOf(
            plan("2026-10-26",HealthCoachFeedback.DONE),
            plan("2026-10-28",HealthCoachFeedback.TOO_HARD),
            plan("2026-10-30",HealthCoachFeedback.MISSED)
        )
        val review=HealthCoachPlans.weekReview(profile,plans,today)
        assertEquals("2026-10-26",review.weekStart)
        assertEquals("2026-11-01",review.weekEnd)
        assertEquals(3,review.scheduledDays)
        assertEquals(1,review.completedDays)
        assertEquals(1,review.tooHardDays)
        assertEquals(1,review.missedDays)
        assertEquals(HealthCoachReviewAction.EASE,review.action)
        assertEquals(setOf(1,3),review.nextDays)
    }
    @Test fun todayProgressRemainsVisibleWhenMondayShowsLastWeekReview() {
        val profile=HealthCoachProfile(days=setOf(1,3,5),minutes=30)
        val current=plan("2026-11-02",HealthCoachFeedback.DONE)
        val previous=listOf(
            plan("2026-10-26",HealthCoachFeedback.DONE),
            plan("2026-10-28",HealthCoachFeedback.PARTIAL),
            plan("2026-10-30",HealthCoachFeedback.MISSED)
        )
        val all=listOf(current)+previous
        assertEquals(4,HealthCoachPlans.history(all,today).size)
        val progress=HealthCoachPlans.weekProgress(profile,all,today)
        val review=HealthCoachPlans.weekReview(profile,all,today)
        assertEquals("2026-11-02",progress.weekStart)
        assertEquals(1,progress.completedDays)
        assertEquals(3,progress.scheduledDays)
        assertEquals("2026-10-26",review.weekStart)
        assertEquals(1,review.completedDays)
        assertEquals(1,review.partialDays)
        assertEquals(1,review.missedDays)
        assertEquals(3,review.scheduledDays)
    }
    @Test fun planRoundTripKeepsActionContractAndLocalSource() {
        val local=plan(feedback=HealthCoachFeedback.PROPOSED).copy(
            kind="SLEEP",successCriteria="按计划上床",fallback="加班时只保留关屏步骤",source="local"
        )
        assertEquals(local,HealthCoachPlans.plan(HealthCoachPlans.json(local)))
    }
    @Test fun temporaryMemoriesExpireAndConfirmedMemoriesReachFollowUpContext() {
        val active=HealthCoachMemory(text="周一三五晚上有空",kind="schedule",createdAt=now.toEpochMilli())
        val expired=HealthCoachMemory(text="这周出差",kind="temporary",createdAt=now.toEpochMilli(),expiresAt="2026-11-01")
        assertTrue(active.active(today))
        assertFalse(expired.active(today))
        val profile=HealthCoachProfile(
            focus=HealthCoachFocus.WEIGHT,goalType=HealthCoachGoalType.WEIGHT,
            target="目标 70 kg",days=setOf(1,3,5)
        )
        val (messages,input)=HealthPrompt.coachFollowUpMessages(
            snapshot(),HealthGoals(),"zh",profile,listOf(active,expired),emptyList(),HealthConversation(),
            "我的数据够支持目标吗？",now
        )
        val payload=JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals(1,payload.getJSONArray("confirmedMemories").length())
        assertEquals("目标 70 kg",payload.getJSONObject("userReportedContext").getString("target"))
        assertEquals(3,payload.getJSONObject("weeklyPlan").getJSONArray("days").length())
        val id=input.facts.first().id
        val reply=HealthPrompt.validateCoachReply(
            JSONObject().put("text","目前有活动记录 [$id]，目标 70 kg，周计划为 3 天。")
                .put("suggestions",JSONArray().put("先按当前 3 天安排继续记录。")).toString(),
            input
        )
        assertTrue(reply.references.contains(id))
    }
    @Test fun followUpReturnsOnlyUserConfirmableMemoryAndPlanCandidates() {
        val profile=HealthCoachProfile(goalType=HealthCoachGoalType.STRENGTH,target="12 周力量目标",days=setOf(1,3,5),minutes=30)
        val (_,input)=HealthPrompt.coachFollowUpMessages(
            snapshot(),HealthGoals(),"zh",profile,emptyList(),emptyList(),HealthConversation(),
            "周五没空，改到周六；以后我不喜欢跑步。",now
        )
        val id=input.facts.first().id
        val raw=JSONObject()
            .put("text","可以，先按调整后的安排执行 [$id]。")
            .put("suggestions",JSONArray().put("周六安排一次力量训练。"))
            .put("memoryCandidate",JSONObject().put("text","不喜欢跑步").put("kind","preference"))
            .put("proposedPlanChange",JSONObject().put("date","2026-11-07")
                .put("action","把周五力量训练改到周六，做 30 分钟。").put("minutes",30)
                .put("days",JSONArray()).put("temporary",true).put("reason","周五没空"))
        val reply=HealthPrompt.validateCoachReply(raw.toString(),input,today)
        assertEquals("不喜欢跑步",reply.memoryCandidate!!.text)
        assertEquals("proposed",reply.memoryCandidate.status)
        assertEquals("2026-11-07",reply.planChangeCandidate!!.date)
        assertTrue(reply.planChangeCandidate.temporary)
        assertTrue(reply.references.contains(id))
    }
    @Test fun candidateConfirmationChangesAPlanOnlyWhenTheUserAppliesIt() {
        val profile=HealthCoachProfile(goalType=HealthCoachGoalType.STRENGTH,days=setOf(1,3,5),minutes=30)
        val permanent=HealthCoachPlanChangeCandidate(
            "2026-11-07","周六 30 分钟力量训练",30,setOf(2,4,6),temporary=false,reason="周五没空"
        )
        assertEquals(setOf(1,3,5),profile.days)
        val changed=HealthCoachPlans.applyPermanentSchedule(profile,permanent)
        assertEquals(setOf(2,4,6),changed.days)
        assertEquals(30,changed.minutes)
        val temporary=permanent.copy(temporary=true,days=emptySet())
        val change=HealthCoachPlans.acceptedScheduleChange(temporary)
        assertTrue(change.active(today))
        assertEquals("2026-11-07",change.date)
    }
    @Test fun messageRoundTripKeepsCandidateReviewState() {
        val memory=HealthCoachMemoryCandidate("周一三五晚上有空","schedule")
        val plan=HealthCoachPlanChangeCandidate("2026-11-07","周六散步 20 分钟",20,temporary=true,reason="周五没空")
        val message=HealthMessage(role="assistant",text="Synthetic",memoryCandidate=memory,planChangeCandidate=plan)
        val restored=HealthJson.message(HealthJson.message(message))
        assertEquals(memory,restored.memoryCandidate)
        assertEquals(plan,restored.planChangeCandidate)
    }
    @Test fun malformedNullableCandidateAndEvidenceAliasesAreRepairedBeforeValidation() {
        val (_,input)=HealthPrompt.coachFollowUpMessages(
            snapshot(),HealthGoals(),"zh",HealthCoachProfile(),emptyList(),emptyList(),
            HealthConversation(),"记住我不喜欢跑步",now
        )
        val id=input.facts.first().id
        val malformed="""{"text":"可以[$id]","claims":[],"suggestions":["先记录。"],"memoryCandidate":, "proposedPlanChange":null,}"""
            .replace("[$id]","[evidenceId:$id]")
        val reply=HealthPrompt.validateCoachReply(malformed,input,today)
        assertEquals("我不喜欢跑步",reply.memoryCandidate?.text)
        assertTrue(reply.references.contains(id))
        assertTrue(HealthPrompt.normalizeModelJson(malformed).contains("\"memoryCandidate\":null"))
    }
    @Test fun followUpDropsUnknownEvidenceAliasesButGeneralAnswersStillRejectThem() {
        val (_,input)=HealthPrompt.coachFollowUpMessages(
            snapshot(),HealthGoals(),"zh",HealthCoachProfile(),emptyList(),emptyList(),
            HealthConversation(),"今天按计划吗",now
        )
        val id=input.facts.first().id
        val raw=JSONObject().put("text","按计划可以继续做 [$id]，另一个引用 [deadbeef] 不应显示。")
            .put("claims",JSONArray().put(JSONObject().put("evidenceId","deadbeef")))
            .put("suggestions",JSONArray().put("先做几分钟热身。")).toString()
        val reply=HealthPrompt.validateCoachReply(raw,input,today)
        assertTrue(reply.references.contains(id))
        assertFalse(reply.text.contains("deadbeef"))
        assertThrows(IllegalStateException::class.java){HealthPrompt.validate(raw,input)}
    }
    @Test fun followUpDropsShortMalformedReferencesAndDoesNotAppendDuplicateSuggestions() {
        val (_,input)=HealthPrompt.coachFollowUpMessages(
            snapshot(),HealthGoals(),"zh",HealthCoachProfile(),emptyList(),emptyList(),
            HealthConversation(),"我的数据够吗",now
        )
        val id=input.facts.first().id
        val suggestion="先记录一次状态。"
        val raw=JSONObject().put("text","目前只能看趋势 [$id]，计划约半刻钟，另一个短引用 [312]、长引用 [7d6521d9-39cf-4f32-ad68-9d5be9f4da50] 和内部字段 [hasRecentMeasurements] 不应显示，『evidenceId』显示内容也要清理。\n\n• $suggestion")
            .put("claims",JSONArray().put(JSONObject().put("value",5000)))
            .put("suggestions",JSONArray().put(suggestion)).toString()
        val reply=HealthPrompt.validateCoachReply(raw,input,today)
        assertTrue(reply.references.contains(id))
        assertFalse(reply.text.contains("[312]"))
        assertFalse(reply.text.contains("7d6521d9"))
        assertFalse(reply.text.contains("hasRecentMeasurements"))
        assertFalse(reply.text.contains("evidenceId"))
        assertFalse(reply.text.contains("半刻钟"))
        assertTrue(reply.text.contains("计划时长"))
        assertEquals(1,Regex("先记录一次状态。").findAll(reply.text).count())
    }
    @Test fun followUpKeepsOnlyMemoryWordsTheUserExplicitlySaid() {
        val (_,input)=HealthPrompt.coachFollowUpMessages(
            snapshot(),HealthGoals(),"zh",HealthCoachProfile(),emptyList(),emptyList(),
            HealthConversation(),"记住：我不喜欢跑步。",now
        )
        val raw=JSONObject().put("text","可以把这条作为待确认记忆。").put("claims",JSONArray())
            .put("suggestions",JSONArray())
            .put("memoryCandidate",JSONObject().put("text","我不喜欢跑步，运动安排尽量不用跑步").put("kind","preference"))
            .toString()
        val reply=HealthPrompt.validateCoachReply(raw,input,today)
        assertEquals("我不喜欢跑步",reply.memoryCandidate?.text)
    }
    @Test fun explicitRememberCommandFallsBackWhenModelOmitsMemoryCandidate() {
        val (_,input)=HealthPrompt.coachFollowUpMessages(
            snapshot(),HealthGoals(),"zh",HealthCoachProfile(),emptyList(),emptyList(),
            HealthConversation(),"记住：我不喜欢跑步。",now
        )
        val raw=JSONObject().put("text","好的。").put("claims",JSONArray())
            .put("suggestions",JSONArray()).toString()
        val reply=HealthPrompt.validateCoachReply(raw,input,today)
        assertEquals("我不喜欢跑步",reply.memoryCandidate?.text)
        assertEquals("preference",reply.memoryCandidate?.kind)
    }
    @Test fun questionAboutExistingMemoryDoesNotCreateANewCandidate() {
        val (_,input)=HealthPrompt.coachFollowUpMessages(
            snapshot(),HealthGoals(),"zh",HealthCoachProfile(),emptyList(),emptyList(),
            HealthConversation(),"你还记住什么？",now
        )
        val raw=JSONObject().put("text","目前没有已确认记忆。").put("claims",JSONArray())
            .put("suggestions",JSONArray()).toString()
        assertNull(HealthPrompt.validateCoachReply(raw,input,today).memoryCandidate)
    }
    @Test fun renderedCoachTextUsesExactValuesAndSourceIdsRemainStable() {
        val (_,input)=HealthPrompt.coachFollowUpMessages(
            snapshot(),HealthGoals(),"zh",HealthCoachProfile(),emptyList(),emptyList(),
            HealthConversation(),"最近怎么样",now
        )
        val fact=input.facts.first()
        val formatted=java.text.NumberFormat.getNumberInstance().format(fact.value)
        val (rendered,sourceIds)=HealthPrompt.renderCoachForStorage(
            "最近记录 [${fact.id}]。",listOf(fact.id),input,true
        )
        assertEquals("最近记录 $formatted 步。",rendered)
        assertEquals(listOf(input.sourceId(fact.id)),sourceIds)
        assertTrue(input.sourceIds.containsKey(fact.id))
    }
    @Test fun renderedCoachTextDoesNotRepeatApproximateAndExactMeasurements() {
        val exercise=HealthEvidence(
            "abcdef123456","ExerciseSession",today.toString(),"Synthetic source",
            "exerciseDuration",36.84,"min"
        )
        val input=HealthPromptInput(listOf(exercise),emptyList(),"这周运动量够吗")
        val (rendered,_)=HealthPrompt.renderCoachForStorage(
            "可以看到约三十七分钟的运动记录 [${exercise.id}]。",listOf(exercise.id),input,true
        )
        assertEquals("可以看到记录 36.84 分钟。",rendered)
    }
}
