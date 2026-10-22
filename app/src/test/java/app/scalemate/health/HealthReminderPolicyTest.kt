package app.scalemate.health

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HealthReminderPolicyTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val now=LocalDate.of(2026,10,18).atTime(11,0).atZone(zone).toInstant().toEpochMilli()
    private val settings=HealthReminderSettings(enabled=true)
    private fun snapshot(days:List<String> = listOf("2026-10-15","2026-10-16","2026-10-17"),coverage:Double=1.0)=HealthSnapshot(readAt=now,window=HealthWindow("2026-10-11","2026-10-18",zone.id),categories=setOf("SleepSession"),evidence=days.map {HealthEvidence(it,"SleepSession",it,"watch","sleepMinutes",350.0,"min",coverage=coverage)},coverage=listOf(HealthCoverage("SleepSession","available")))
    private fun event(s:HealthSnapshot?=snapshot(),config:HealthReminderSettings=settings,history:List<HealthReminder> = emptyList(),goals:HealthGoals=HealthGoals(450))=HealthReminderPolicy.evaluate(s,config,goals,history,now,zone)
    @Test fun threeComparableNightsBelowConfirmedGoalTrigger() {assertEquals(listOf("sleep"),event()!!.topics)}
    @Test fun noGoalNoDataOrLowCoverageStaysQuiet() {assertNull(event(goals=HealthGoals()));assertNull(event(null));assertNull(event(snapshot(coverage=.7)))}
    @Test fun missingNightIsNotTreatedAsConsecutiveLowSleep() {assertNull(event(snapshot(listOf("2026-10-14","2026-10-16","2026-10-17"))))}
    @Test fun staleSnapshotAndStaleRecordsDoNotTrigger() {assertNull(event(snapshot().copy(readAt=now-7*3600000)));assertNull(event(snapshot(listOf("2026-10-11","2026-10-12","2026-10-13"))))}
    @Test fun disabledQuietAndBeforeCheckTimeStayQuiet() {assertNull(event(config=settings.copy(enabled=false)));assertNull(event(config=settings.copy(quietStart=600,quietEnd=800)));assertNull(event(config=settings.copy(checkMinute=800)))}
    @Test fun onePerDayAndTopicCooldownSurviveRestarts() {
        val first=event()!!
        assertNull(event(history=listOf(first)));assertNull(event(history=listOf(first.copy(createdAt=now-2*86400000))))
        assertNotNull(event(history=listOf(first.copy(createdAt=now-4*86400000))))
    }
    @Test fun incompleteReadDoesNotTrigger() {assertNull(event(snapshot().copy(coverage=listOf(HealthCoverage("SleepSession","partial")))))}
    @Test fun differentSourcesAreNotCompared() {val s=snapshot();assertNull(event(s.copy(evidence=s.evidence.mapIndexed {i,e->e.copy(source="watch-$i")})))}
    @Test fun scheduledBedtimeDoesNotRequireDataOrAnAiService() {assertEquals(listOf("bedtime"),event(null,settings.copy(sleep=false,activity=false,bedtime=true,bedtimeMinute=650))!!.topics)}
    @Test fun quietHoursWrapMidnightAndEqualMeansNoQuietWindow() {assertTrue(HealthReminderPolicy.quiet(30,1320,540));assertTrue(HealthReminderPolicy.quiet(1380,1320,540));assertFalse(HealthReminderPolicy.quiet(720,1320,540));assertFalse(HealthReminderPolicy.quiet(720,0,0))}
    @Test fun recoveryNeedsTwoNightsAndHistoricalBaselineNotCurrentNight() {
        val rows=(1..17).flatMap {day->val recent=day>=16;val date=LocalDate.of(2026,10,day).toString();listOf(HealthEvidence("hrv-$day","HeartRateVariabilityRmssd",date,"watch","nightRmssd",if(recent)30.0 else 50.0,"ms",count=20,coverage=1.0),HealthEvidence("hr-$day","HeartRate",date,"watch","sleepBpm",if(recent)70.0 else 60.0,"bpm",count=400,coverage=.95))}
        val s=snapshot().copy(evidence=rows,coverage=listOf(HealthCoverage("HeartRate","available"),HealthCoverage("HeartRateVariabilityRmssd","available")))
        assertEquals(listOf("recovery"),event(s,settings.copy(sleep=false,recovery=true))!!.topics)
        assertNull(event(s.copy(evidence=rows.filter {it.date>="2026-10-14"}),settings.copy(sleep=false,recovery=true)))
    }
}
