package app.scalemate.health

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HealthMetricsTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun at(s:String)=Instant.parse(s).toEpochMilli()
    private val start=at("2026-10-16T16:00:00Z")
    private val end=start+8*3600000
    private fun sleep(stages:List<HealthStage>)=HealthRecord("sleep","SleepSession","watch",start,end,stages=stages)
    @Test fun awakeStagesAreNotSleepAndUnspecifiedSleepIsIncluded() {
        val s=sleep(listOf(HealthStage(start,start+30*60000,1),HealthStage(start+30*60000,end,2)))
        assertEquals(450.0,HealthMetrics.sleep(s).getValue("sleepMinutes"),0.0)
    }
    @Test fun noStagesOnlyReportsSessionDuration() {
        val stats=HealthMetrics.sleep(sleep(emptyList()))
        assertEquals(480.0,stats.getValue("sessionMinutes"),0.0);assertFalse(stats.containsKey("sleepMinutes"))
    }
    @Test fun unknownStagesAndGapsAreNotFilledAsSleep() {
        val stats=HealthMetrics.sleep(sleep(listOf(HealthStage(start,start+4*3600000,4),HealthStage(start+4*3600000,end,0))))
        assertFalse(stats.containsKey("sleepMinutes"));assertEquals(.5,stats.getValue("stageCoverage"),0.0)
    }
    @Test fun overlappingStagesCannotDoubleCountSleep() {
        val stats=HealthMetrics.sleep(sleep(listOf(HealthStage(start,end,4),HealthStage(start+3600000,end,5))))
        assertFalse(stats.containsKey("sleepMinutes"));assertEquals(1.0,stats.getValue("overlap"),0.0)
    }
    @Test fun intervalUnionHandlesNestedAdjacentAndDisjointIntervals() {assertEquals(35,HealthMetrics.unionMillis(listOf(0L to 10L,5L to 20L,20L to 30L,32L to 37L,8L to 9L)))}
    @Test fun duplicatesAreDeduplicatedAndNapsAreSeparate() {
        val night=sleep(listOf(HealthStage(start,end,4)))
        val nap=night.copy(id="nap",start=end+3600000,end=end+7200000,stages=emptyList())
        val rows=HealthMetrics.compute(listOf(night,night,nap),zone,end+86400000)
        assertEquals(480.0,rows.single {it.field=="sleepMinutes"}.value,0.0)
        assertEquals(60.0,rows.single {it.field=="additionalSessionMinutes"}.value,0.0)
        assertEquals("2026-10-17",rows.single {it.field=="sleepMinutes"}.date)
    }
    @Test fun duplicateSleepSessionsSuppressUnsupportedActualSleepConclusion() {
        val a=sleep(listOf(HealthStage(start,end,4)))
        val rows=HealthMetrics.compute(listOf(a,a.copy(id="overlap",start=start+1000)),zone,end+86400000)
        assertFalse(rows.any {it.field=="sleepMinutes"})
    }
    @Test fun minuteMediansDoNotGiveDenseMinutesExtraWeight() {
        val hr=HealthRecord("hr","HeartRate","watch",start,end,points=listOf(HealthPoint(start,60.0,"bpm"),HealthPoint(start+60000,100.0,"bpm")))
        val dense=hr.copy(points=hr.points+List(100){HealthPoint(start+it+1,60.0,"bpm")})
        fun value(record:HealthRecord)=HealthMetrics.compute(listOf(record),zone,end+86400000).single {it.field=="bpm"}.value
        assertEquals(value(hr),value(dense),0.0)
    }
    @Test fun explicitHeartSourceAndSleepStageIntervalsAreRespected() {
        val night=sleep(listOf(HealthStage(start,start+3600000,1),HealthStage(start+3600000,end,4)))
        val hr=HealthRecord("hr","HeartRate","chosen",start,end,points=listOf(HealthPoint(start+5000,180.0,"bpm"),HealthPoint(start+3600000,60.0,"bpm")))
        val other=hr.copy(id="other",source="other",points=listOf(HealthPoint(start+3600000,150.0,"bpm")))
        val rows=HealthMetrics.compute(listOf(night,hr,other),zone,end+86400000,mapOf("HeartRate" to "chosen"))
        assertEquals(60.0,rows.single {it.field=="sleepBpm"}.value,0.0)
    }
    @Test fun sparseHrvRemainsVisibleButIsNotReminderEligible() {
        val night=sleep(listOf(HealthStage(start,end,4)))
        val hrv=HealthRecord("hrv","HeartRateVariabilityRmssd","watch",start+3600000,values=mapOf("heartRateVariabilityMillis" to HealthValue(40.0,"ms")))
        val result=HealthMetrics.compute(listOf(night,hrv),zone,end+86400000).single {it.field=="nightRmssd"}
        assertEquals(0.0,result.coverage!!,0.0)
    }
    @Test fun categoricalCodesAreCountedNotAveraged() {
        val a=HealthRecord("a","ExerciseSession","watch",start,values=mapOf("exerciseType" to HealthValue(1.0,"enum")))
        val b=a.copy(id="b",values=mapOf("exerciseType" to HealthValue(3.0,"enum")))
        val rows=HealthMetrics.compute(listOf(a,b),zone,end+86400000)
        assertFalse(rows.any {it.field=="exerciseType"});assertTrue(rows.any {it.field=="exerciseType.code1.count"&&it.value==1.0})
    }
    @Test fun exerciseCardsUseTotalSessionDurationInsteadOfRecordCount() {
        val a=HealthRecord("a","ExerciseSession","watch",start,start+20*60000,values=mapOf("exerciseType" to HealthValue(53.0,"enum")))
        val b=a.copy(id="b",start=start+3600000,end=start+3630000,values=mapOf("exerciseType" to HealthValue(45.0,"enum")))
        val rows=HealthMetrics.compute(listOf(a,b),zone,end+86400000)
        assertEquals(20.5,rows.single {it.field=="exerciseDuration"}.value,0.0)
        assertEquals("session-duration-v1",rows.single {it.field=="exerciseDuration"}.algorithm)
    }
    @Test fun stepsUsePlatformTotalRatherThanSummingRawOrigins() {
        val a=HealthRecord("a","Steps","watch",start,values=emptyMap())
        val total=a.copy(id="aggregate:Steps",source="Health Connect",values=mapOf("count" to HealthValue(7000.0,"steps")))
        val rows=HealthMetrics.compute(listOf(a,a.copy(id="b",source="phone"),total),zone,end+86400000)
        assertEquals(7000.0,rows.single {it.field=="count"}.value,0.0)
        assertEquals("platform-aggregate-v1",rows.single {it.field=="count"}.algorithm)
        assertFalse(rows.any {it.field=="recordCount"&&it.source=="Health Connect"})
    }
    @Test fun cachedNutritionAggregateSurvivesIncrementalRecompute() {
        val first=HealthRecord("first","Nutrition","source",start,start+60000,mapOf("energy" to HealthValue(500.0,"kcal")))
        val second=first.copy(id="second",start=start+120000,end=start+180000,values=mapOf("energy" to HealthValue(1000.0,"kcal")))
        val aggregate=HealthRecord("aggregate:Nutrition:$start","Nutrition","Health Connect",start,end,mapOf("energy" to HealthValue(1500.0,"kcal")))
        val energy=HealthMetrics.compute(listOf(first,second,aggregate),zone,end+86400000)
            .single {it.kind=="Nutrition"&&it.field=="energy"}
        assertEquals(1500.0,energy.value,0.0)
        assertEquals("platform-aggregate-v1",energy.algorithm)
    }
    @Test fun todayIsMarkedPartialAndTimeZoneControlsDate() {
        val r=HealthRecord("w","Weight","scale",at("2026-10-16T17:00:00Z"),values=mapOf("weight" to HealthValue(71000.0,"g")))
        val rows=HealthMetrics.compute(listOf(r),zone,r.start+1000)
        assertTrue(rows.all {it.partial&&it.date=="2026-10-17"})
    }
    @Test fun completedSleepThisMorningIsAvailableForMorningReminders() {
        val night=sleep(listOf(HealthStage(start,end,2)))
        val rows=HealthMetrics.compute(listOf(night),zone,end+3600000)
        val completed=rows.single {it.field=="sleepMinutes"}
        assertFalse(completed.partial)
        assertEquals("analysis",completed.period(HealthWindow("2026-10-10","2026-10-17",zone.id)))
    }
    @Test fun dayRangesUseCalendarDaysAcrossDst() {
        val w=HealthWindow.recent(7,Instant.parse("2026-03-10T12:00:00Z"),ZoneId.of("America/New_York"))
        assertEquals("2026-03-03",w.start);assertEquals("2026-03-10",w.end)
        assertEquals(167,java.time.Duration.between(w.startInstant,w.endInstant).toHours())
    }
    @Test fun platformAggregateSlicesFollowReportingZoneAcrossDst() {
        val z=ZoneId.of("America/New_York")
        val begin=java.time.LocalDate.of(2026,3,7).atStartOfDay(z).toInstant()
        val finish=java.time.LocalDate.of(2026,3,10).atStartOfDay(z).toInstant()
        val slices=healthDaySlices(begin,finish,z)
        assertEquals(listOf(24L,23L,24L),slices.map {java.time.Duration.between(it.first,it.second).toHours()})
        assertEquals(begin,slices.first().first);assertEquals(finish,slices.last().second)
        assertTrue(slices.zipWithNext().all {(a,b)->a.second==b.first})
    }
    @Test fun aggregateBatchesPreservePartialDaysAndBothDstTransitions() {
        val z=ZoneId.of("America/New_York")
        for((begin,end) in listOf("2026-03-06T17:00:00Z" to "2026-03-11T17:00:00Z","2026-10-30T16:00:00Z" to "2026-11-04T17:00:00Z")) {
            val start=Instant.parse(begin);val finish=Instant.parse(end)
            val batches=healthDayBatches(start,finish,z)
            assertEquals(healthDaySlices(start,finish,z),batches.flatten())
            assertTrue(batches.all {batch->batch.map {java.time.Duration.between(it.first,it.second)}.distinct().size==1})
            assertTrue(batches.flatten().any {java.time.Duration.between(it.first,it.second).toHours() in setOf(23L,25L)})
        }
        val start=Instant.parse("2026-06-01T00:00:00Z")
        val batches=healthDayBatches(start,start.plusSeconds(185*86400),ZoneId.of("UTC"))
        assertEquals(listOf(90,90,5),batches.map {it.size})
    }
    @Test fun healthHashesKeepStableSha256IdsAfterHexEncodingOptimization() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",healthHash(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",healthHash("abc"))
    }
    @Test fun unsetNutritionDenormalsAreRejectedAtTheNativeUnitBoundary() {
        val stored=HealthRecordMapper.value(HealthValue(Double.MIN_VALUE,"g"))
        assertNull(stored)
    }
}
