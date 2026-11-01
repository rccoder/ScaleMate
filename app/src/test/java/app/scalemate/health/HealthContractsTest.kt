package app.scalemate.health

import app.scalemate.food.AiStream
import app.scalemate.food.AiUsage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HealthContractsTest {
    private val fact=HealthEvidence("1234567890abcdef12345678","Steps","2026-10-16","Health Connect","count",7000.0,"steps")
    private fun snapshot(evidence:List<HealthEvidence> = listOf(fact))=HealthSnapshot(window=HealthWindow("2026-10-10","2026-10-17"),categories=setOf("Steps"),evidence=evidence,coverage=listOf(HealthCoverage("Steps","available",records=2)),rawFile="snapshot-synthetic.jsonl")
    private fun answer(value:Double=7000.0,id:String=fact.id,text:String="Recorded activity [$id].")=JSONObject().put("text",text).put("claims",JSONArray().put(JSONObject().put("evidenceId",id).put("value",value).put("unit","steps"))).put("suggestions",JSONArray().put("Consider a comfortable walk.")).toString()
    @Test fun validAnswerReferencesKnownFacts() {assertEquals(listOf(fact.id),HealthPrompt.validate(answer(),HealthPrompt.input(snapshot(),"Activity? ")).second)}
    @Test(expected=IllegalArgumentException::class) fun fabricatedClaimValueIsRejected(){HealthPrompt.validate(answer(value=9000.0),HealthPrompt.input(snapshot(),"Activity?"))}
    @Test(expected=IllegalStateException::class) fun fabricatedEvidenceIsRejected(){HealthPrompt.validate(answer(id="ffffffffffffffffffffffff"),HealthPrompt.input(snapshot(),"Activity?"))}
    @Test(expected=IllegalArgumentException::class) fun inventedNumberBesideAReferenceIsRejected(){HealthPrompt.validate(answer(text="You walked 9000 steps [${fact.id}]."),HealthPrompt.input(snapshot(),"Activity?"))}
    @Test fun evidenceOnlyClaimsAndSurroundingTextAreAccepted() {
        val raw="```json\n"+JSONObject().put("text","Activity is recorded [${fact.id}], and the trend is unclear.").put("claims",JSONArray().put(JSONObject().put("evidenceId",fact.id))).put("suggestions",JSONArray()).toString()+"\n```"
        assertEquals(listOf(fact.id),HealthPrompt.validate(raw,HealthPrompt.input(snapshot(),"Activity?")).second)
        assertEquals("Activity is recorded 7,000 steps.",HealthPrompt.render("Activity is recorded [${fact.id}]",listOf(fact.id),listOf(fact),false)+".")
    }
    @Test fun inlineEvidenceReferencesDoNotNeedToBeRepeatedInClaims() {
        val raw=JSONObject().put("text","Activity is recorded [${fact.id}].").put("claims",JSONArray()).put("suggestions",JSONArray()).toString()
        assertEquals(listOf(fact.id),HealthPrompt.validate(raw,HealthPrompt.input(snapshot(),"Activity?")).second)
    }
    @Test fun coachRenderingOnlyShowsReferencedFactsAndLocalizesUnits() {
        val secondary=fact.copy(id="abcdefabcdefabcdefabcdef",kind="HeartRateVariabilityRmssd",field="nightRmssd",value=46.0,unit="ms")
        val input=HealthPrompt.input(snapshot(listOf(fact,secondary)),"Coach")
        val (text,ids)=HealthPrompt.renderCoach("今日已记录 [${fact.id}]。",input.facts,true)
        assertEquals("今日已记录 7,000 步。",text)
        assertEquals(listOf(fact.id),ids)
    }
    @Test fun coachRenderingTurnsRawSleepAndStepFieldsIntoReadableText() {
        val bedtime=fact.copy(id="111111111111111111111111",kind="SleepSession",field="sessionStartAfterLocalNoon",value=878.0,unit="min after noon")
        val wake=fact.copy(id="222222222222222222222222",kind="SleepSession",field="sessionEndMinuteOfDay",value=610.0,unit="min of day")
        val steps=fact.copy(id="333333333333333333333333",kind="Steps",field="count",value=1198.0,unit="value")
        val medianBedtime=bedtime.copy(id="444444444444444444444444",field="sessionStartAfterLocalNoon.median",value=872.0)
        val medianSteps=steps.copy(id="555555555555555555555555",field="count.median",value=8123.0)
        val input=HealthPrompt.input(snapshot(listOf(bedtime,wake,steps,medianBedtime,medianSteps)),"Coach")
        val (text,_)=HealthPrompt.renderCoach(
            "区间入睡 [${medianBedtime.id}]，昨晚入睡 [${bedtime.id}]，起床 [${wake.id}]，今天 [${steps.id}]，中位 [${medianSteps.id}]。",
            input.facts,
            true
        )
        assertEquals("区间入睡 02:32 入睡，昨晚入睡 02:38 入睡，起床 10:10 起床，今天 1,198 步，中位 8,123 步。",text)
    }
    @Test fun generalDurationRangesInSuggestionsAreAccepted() {
        val raw=JSONObject().put("text","Activity is recorded [${fact.id}].").put("claims",JSONArray()).put("suggestions",JSONArray().put("Aim for 7–9 hours of sleep and about 150 minutes of activity per week.")).toString()
        assertEquals(listOf(fact.id),HealthPrompt.validate(raw,HealthPrompt.input(snapshot(),"Activity?")).second)
    }
    @Test(expected=IllegalArgumentException::class) fun inventedMeasurementInSuggestionIsRejected() {
        val raw=JSONObject().put("text","Activity is recorded [${fact.id}].").put("claims",JSONArray()).put("suggestions",JSONArray().put("Your body fat is 18 percent.")).toString()
        HealthPrompt.validate(raw,HealthPrompt.input(snapshot(),"Activity?"))
    }
    @Test fun streamPreviewHandlesEscapesAndPartialJson() {assertEquals("Hello\nworld",HealthPrompt.preview("{\"text\":\"Hello\\nworld"));assertEquals("",HealthPrompt.preview("{\"claims\":["))}
    @Test fun openAiAndAnthropicSseDeltasAreDecoded() {
        assertEquals("Hello",AiStream.decode("openai","{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}").text)
        assertEquals("Hello",AiStream.decode("openai","{\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}}]}").text)
        assertEquals("",AiStream.decode("openai","{\"choices\":[{\"delta\":{\"reasoning_content\":\"internal thought\"}}]}").text)
        assertEquals("internal thought",AiStream.decode("openai","{\"choices\":[{\"delta\":{\"reasoning_content\":\"internal thought\"}}]}").reasoning)
        assertEquals("reasoning alias",AiStream.decode("openai","{\"choices\":[{\"delta\":{\"reasoning\":\"reasoning alias\"}}]}").reasoning)
        assertEquals("Hello",AiStream.decode("anthropic","{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}").text)
        assertEquals("thinking",AiStream.decode("anthropic","{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"thinking\"}}").reasoning)
        assertTrue(AiStream.decode("openai","[DONE]").finished);assertTrue(AiStream.decode("anthropic","{\"type\":\"message_stop\"}").finished)
    }
    @Test fun streamUsageKeepsProviderCountsWhenAvailable() {
        val openAi=AiStream.decode("openai","{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}").usage
        assertEquals(AiUsage(10,5,15),openAi)
        val start=AiStream.decode("anthropic","{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":20,\"output_tokens\":1}}}").usage
        val delta=AiStream.decode("anthropic","{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":8}}").usage
        assertEquals(AiUsage(20,8,null),AiStream.merge(start,delta))
    }
    @Test fun streamProgressUsesARoughMixedLanguageTokenEstimate() {
        assertEquals(0L,AiStream.estimateOutputTokens(""))
        assertTrue(AiStream.estimateOutputTokens("hello world") in 2L..5L)
        assertEquals(4L,AiStream.estimateOutputTokens("香煎鸡胸"))
        assertTrue(AiStream.estimateOutputTokens("{\"name\":\"香煎鸡胸\"}") in 5L..12L)
    }
    @Test(expected=app.scalemate.food.AiWire.IncompleteResponse::class) fun truncatedOpenAiStreamIsRejected(){AiStream.decode("openai","{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}")}
    @Test(expected=app.scalemate.food.AiWire.IncompleteResponse::class) fun truncatedAnthropicStreamIsRejected(){AiStream.decode("anthropic","{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}")}
    @Test fun longHistoryIsSummarizedWithoutDroppingAnyCategory() {
        val rows=(1..90).flatMap {day->listOf("Steps","Weight","BloodPressure").map {kind->fact.copy(id="$kind-$day",kind=kind,date=java.time.LocalDate.of(2026,1,1).plusDays(day.toLong()).toString(),value=day.toDouble())}}
        val s=snapshot(rows).copy(window=HealthWindow("2026-01-01","2026-05-01"))
        val input=HealthPrompt.input(s,"Overall report")
        assertEquals(setOf("Steps","Weight","BloodPressure"),input.facts.map {it.kind}.toSet());assertEquals(12,input.facts.size);assertEquals(3,input.grouped.size)
        val again=HealthPrompt.input(s.copy(evidence=s.evidence+input.facts),"Follow up")
        assertEquals(input.facts,again.facts)
    }
    @Test fun newestFactSurvivesLongRangeSummarization() {
        val rows=(25..31).map {day->fact.copy(id="sleep-$day",kind="SleepSession",field="sleepMinutes",date="2026-10-$day",value=430.0+day)}
        val latest=fact.copy(id="sleep-latest",kind="SleepSession",field="sleepMinutes",date="2026-11-01",value=479.0)
        val s=snapshot(rows+latest).copy(window=HealthWindow("2026-10-25","2026-11-01"))
        val input=HealthPrompt.input(s,"Coach")
        assertTrue(input.facts.any {it.id==latest.id&&it.value==479.0})
        assertEquals(4,input.facts.size)
        assertEquals(latest.date,input.grouped.single().getString("latestDate"))
    }
    @Test fun coachPromptKeepsOnlyDecisionRelevantFacts() {
        val relevant=fact.copy(id="steps-coach",kind="Steps",field="count",date="2026-10-24",value=8123.0)
        val irrelevant=(1..200).map {i->fact.copy(id="records-$i",kind="SleepSession",field="recordCount",date="2026-10-24",value=i.toDouble())}
        val calories=fact.copy(id="calories-coach",kind="TotalCaloriesBurned",field="energy",date="2026-10-24",value=1954.0,unit="kcal")
        val nutrition=fact.copy(id="nutrition-coach",kind="Nutrition",field="energy",date="2026-10-24",value=1800.0,unit="kcal")
        val s=snapshot(listOf(relevant,calories,nutrition)+irrelevant).copy(categories=setOf("SleepSession","Steps","TotalCaloriesBurned","Nutrition"))
        val (messages,input)=HealthPrompt.coachMessages(s,HealthGoals(),"zh")
        assertEquals(1,input.facts.size)
        assertEquals(relevant.id,input.sourceId(input.facts.single().id))
        assertTrue(input.facts.single().id.matches(Regex("[a-f0-9]{8}")))
        assertTrue(messages.getJSONObject(1).getString("content").toByteArray().size<160000)
    }
    @Test fun coachPromptPrefersMeasuredSleepOverSessionFallback() {
        val measured=fact.copy(id="measured-sleep",kind="SleepSession",field="sleepMinutes",date="2026-10-24",value=438.0,unit="min")
        val session=fact.copy(id="session-fallback",kind="SleepSession",field="sessionMinutes",date="2026-10-24",value=450.0,unit="min")
        val fallbackOnly=fact.copy(id="session-only",kind="SleepSession",field="sessionMinutes",date="2026-10-23",value=420.0,unit="min")
        val s=snapshot(listOf(measured,session,fallbackOnly)).copy(categories=setOf("SleepSession"))
        val (_,input)=HealthPrompt.coachMessages(s,HealthGoals(),"zh")
        val sourceIds=input.facts.map {input.sourceId(it.id)}.toSet()
        assertTrue(measured.id in sourceIds)
        assertFalse(session.id in sourceIds)
        assertTrue(fallbackOnly.id in sourceIds)
    }
    @Test fun coachPromptKeepsLatestAndTypicalSleepDistinct() {
        val rows=(16..25).map {day->
            fact.copy(
                id="sleep-$day".padEnd(24,'0'),
                kind="SleepSession",
                field="sleepMinutes",
                date="2026-10-$day",
                value=420.0+day,
                unit="min"
            )
        }
        val s=snapshot(rows).copy(window=HealthWindow("2026-10-18","2026-10-25"),categories=setOf("SleepSession"))
        val (_,input)=HealthPrompt.coachMessages(s,HealthGoals(),"zh")
        val latest=input.facts.filter {it.kind=="SleepSession"&&it.date=="2026-10-25"}
        val typical=input.facts.filter {it.field.startsWith("recentTypicalSleepMinutes")}
        assertEquals(1,latest.size)
        assertEquals(1,typical.size)
        assertTrue(input.facts.none {it.field.endsWith(".min")||it.field.endsWith(".max")})
        assertTrue(input.facts.none {it.field.contains("sessionEndMinuteOfDay")})
    }
    @Test(expected=HealthInputTooLarge::class) fun oversizedClinicalSummaryIsRejectedBeforeSending() {
        val row=fact.copy(kind="Medical_CONDITIONS",field="clinicalRecord",detail=JSONObject().put("synthetic", "x".repeat(170000)).toString())
        HealthPrompt.messages(snapshot(listOf(row)),"Overall",HealthConversation(),HealthGoals(),"en")
    }
    @Test fun fhirRemovesPersonalIdentifiersNarrativeReferencesAndUnknownUrls() {
        val raw="""{"resourceType":"Observation","id":"secret-id","subject":{"reference":"Patient/private"},"identifier":[{"value":"patient-id"}],"name":[{"text":"Private Name"}],"text":{"div":"private narrative"},"effectiveDateTime":"2026-10-16T12:00:00Z","valueQuantity":{"value":65,"unit":"bpm"},"code":{"coding":[{"system":"http://loinc.org","code":"8867-4","display":"heart rate"}]},"performer":[{"reference":"Practitioner/private"}]}"""
        val clean=HealthFhir.sanitize(raw).toString()
        assertFalse(clean.contains("secret-id"));assertFalse(clean.contains("Private"));assertFalse(clean.contains("Patient"));assertFalse(clean.contains("performer"));assertTrue(clean.contains("8867-4"))
        assertEquals(65.0,HealthFhir.numbers(JSONObject(clean)).values.single().value,0.0)
    }
    @Test fun undatedClinicalRecordsUseNullRatherThanInventingAnEpochDate() {
        val record=HealthRecord("synthetic","Medical_CONDITIONS","Clinical source",0L,detail="{}")
        val json=HealthJson.record(record)
        assertTrue(json.isNull("start"));assertTrue(json.isNull("end"));assertEquals(record,HealthJson.record(json))
    }
    @Test fun snapshotRoundTripPreservesMissingReasonsAndUnknownValues() {val s=snapshot().copy(coverage=listOf(HealthCoverage("Steps","not_authorized")));assertEquals(s,HealthJson.snapshot(HealthJson.snapshot(s)))}
    @Test fun exportFilesParseAndPromptReferencesSameSnapshotWithoutCredentials()=runBlocking {
        val root=Files.createTempDirectory("health-export-test").toFile()
        try {
            val s=snapshot();val files=HealthExport.write(root.resolve("exports"),root.resolve("raw"),s,"Review activity",false)
            val json=JSONObject(files.single {it.extension=="json"}.readText());val prompt=files.single {it.extension=="txt"}.readText()
            assertEquals("1.0",json.getString("schemaVersion"));assertTrue(prompt.contains(json.getString("exportId")));assertTrue(prompt.contains(files.first().name))
            assertFalse(json.toString().contains("encryptedKey"));assertFalse(json.toString().contains("baseUrl"));assertEquals(fact.value,json.getJSONArray("metrics").getJSONObject(0).getDouble("value"),0.0)
        }finally {root.deleteRecursively()}
    }
    @Test fun detailExportUsesNewAnonymousIdsAndManifestCounts()=runBlocking {
        val root=Files.createTempDirectory("health-detail-test").toFile()
        try {
            val raw=root.resolve("raw").apply {mkdirs()};val record=HealthRecord("internal-record","Steps","Source alias",java.time.Instant.parse("2026-10-16T12:00:00Z").toEpochMilli(),platformId="private-platform-id")
            raw.resolve("snapshot-synthetic.jsonl").writeText(HealthJson.cacheRecord(record).toString()+"\n")
            val files=HealthExport.write(root.resolve("exports"),raw,snapshot(),"Review",true)
            val manifest=JSONObject(files.single {it.name=="manifest.json"}.readText());assertEquals(1,manifest.getInt("recordCount"));assertEquals(1,manifest.getInt("partCount"))
            val part=JSONObject(files.first().readText());assertNotEquals("internal-record",part.getJSONArray("records").getJSONObject(0).getString("recordId"))
            assertFalse(files.first().readText().contains("private-platform-id"));assertFalse(part.getJSONArray("records").getJSONObject(0).has("platformId"))
        }finally {root.deleteRecursively()}
    }
    @Test fun expiredRawExportLeavesNoShareablePartialDirectory()=runBlocking {
        val root=Files.createTempDirectory("health-expired-test").toFile()
        try {
            try {HealthExport.write(root.resolve("exports"),root.resolve("raw"),snapshot(),"Review",true);fail("Missing cache must fail")}catch(_:IllegalStateException){}
            assertEquals(0,root.resolve("exports").listFiles()!!.size)
        }finally {root.deleteRecursively()}
    }
    @Test fun splitDetailExportKeepsEveryRecordAndManifestCounts()=runBlocking {
        val root=Files.createTempDirectory("health-split-test").toFile()
        try {
            val raw=root.resolve("raw").apply {mkdirs()}
            raw.resolve("snapshot-synthetic.jsonl").bufferedWriter().use {out->repeat(50){i->out.write(HealthJson.record(HealthRecord("record-$i","Steps","Source",java.time.Instant.parse("2026-10-16T12:00:00Z").toEpochMilli())).toString());out.newLine()}}
            val files=HealthExport.write(root.resolve("exports"),raw,snapshot(),"Review",true,maxPartBytes=1024)
            val manifest=JSONObject(files.single {it.name=="manifest.json"}.readText())
            assertEquals(50,manifest.getInt("recordCount"));assertTrue(manifest.getInt("partCount")>1)
            val parts=files.filter {it.name.contains("-part-")};assertEquals(50,parts.sumOf {JSONObject(it.readText()).getJSONArray("records").length()})
            assertEquals(manifest.getInt("partCount"),parts.size)
        }finally {root.deleteRecursively()}
    }
}
