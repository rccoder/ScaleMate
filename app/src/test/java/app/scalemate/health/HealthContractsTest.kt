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
        assertEquals("Hello",AiStream.decode("anthropic","{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}").text)
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
        assertEquals(setOf("Steps","Weight","BloodPressure"),input.facts.map {it.kind}.toSet());assertEquals(9,input.facts.size);assertEquals(3,input.grouped.size)
        val again=HealthPrompt.input(s.copy(evidence=s.evidence+input.facts),"Follow up")
        assertEquals(input.facts,again.facts)
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
