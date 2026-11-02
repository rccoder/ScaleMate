package app.scalemate.health

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthCoachTest {
    @Test fun oneWeightReadingRemainsUsableWithoutInventingATrend() {
        val fact=row("Weight","weight","2026-11-01",71000.0,"g")
        val brief=HealthCoach.brief(snapshot(listOf(fact)),HealthGoals(),LocalDate.parse("2026-11-01"))!!
        assertEquals(1,brief.availableFactCount)
        assertTrue(brief.signals.filterIsInstance<HealthCoachSignal.Weight>().isEmpty())
    }
    @Test fun nutritionOnlyDataCanSupportRecordedIntakeCoachingWithoutLocalMealFallback() {
        val fact=row("Nutrition","energy","2026-11-01",1000.0,"kcal",partial=true)
        val brief=HealthCoach.brief(snapshot(listOf(fact)),HealthGoals(),LocalDate.parse("2026-11-01"))!!
        assertEquals(fact,brief.signals.filterIsInstance<HealthCoachSignal.Nutrition>().single().latest)
    }
    @Test fun comparisonsDoNotTreatASourceChangeAsAChangeInHealth() {
        val previous=row("SleepSession","sleepMinutes","2026-10-31",300.0,"min",source="Old source")
        val latest=row("SleepSession","sleepMinutes","2026-11-01",450.0,"min",source="New source")
        val brief=HealthCoach.brief(snapshot(listOf(previous,latest)),HealthGoals(),LocalDate.parse("2026-11-01"))!!
        assertEquals(null,brief.signals.filterIsInstance<HealthCoachSignal.Sleep>().single().baselineMedianMinutes)
    }
    private val window=HealthWindow("2026-10-25","2026-11-01","Asia/Shanghai")
    private fun row(
        kind:String,
        field:String,
        date:String,
        value:Double,
        unit:String,
        partial:Boolean=false,
        source:String="Synthetic source"
    )=HealthEvidence(healthHash("$kind|$field|$date|$value|$partial").take(24),kind,date,source,field,value,unit,partial=partial)
    private fun snapshot(evidence:List<HealthEvidence>)=HealthSnapshot(
        readAt=java.time.Instant.parse("2026-11-01T11:35:00Z").toEpochMilli(),
        window=window,
        categories=evidence.map {it.kind}.toSet(),
        evidence=evidence,
        coverage=evidence.map {it.kind}.distinct().map {
            HealthCoverage(it,"available",latest=java.time.Instant.parse("2026-11-01T10:03:00Z").toEpochMilli())
        }
    )

    @Test fun mainSleepAndSupplementalSleepRemainDistinct() {
        val primary=row("SleepSession","sleepMinutes","2026-11-01",479.0,"min")
        val supplement=row("SleepSession","additionalSleepMinutes","2026-11-01",41.0,"min")
        val previous=(1..7).map {row("SleepSession","sleepMinutes","2026-10-${(24+it).toString().padStart(2,'0')}",440.0+it,"min")}
        val brief=HealthCoach.brief(snapshot(listOf(primary,supplement)+previous),HealthGoals(sleepMinutes=480),LocalDate.parse("2026-11-01"))!!
        val sleep=brief.signals.filterIsInstance<HealthCoachSignal.Sleep>().single()
        assertEquals(primary.id,sleep.primary.id)
        assertEquals(supplement.id,sleep.supplemental.single().id)
        assertEquals(480,sleep.goalMinutes)
        assertTrue(sleep.baselineMedianMinutes!!>440)
    }

    @Test fun sleepSessionIsUsedWhenStageCoverageCannotProduceAsleepTime() {
        val session=row("SleepSession","sessionMinutes","2026-11-01",420.0,"min")
        val brief=HealthCoach.brief(snapshot(listOf(session)),HealthGoals(),LocalDate.parse("2026-11-01"))!!
        val sleep=brief.signals.filterIsInstance<HealthCoachSignal.Sleep>().single()
        assertEquals("sessionMinutes",sleep.primary.field)
        assertEquals(420.0,sleep.primary.value,0.0)
    }

    @Test fun trainingUsesOneDailyDurationWhenSourcesDuplicateTheSameDate() {
        val short=row("ExerciseSession","exerciseDuration","2026-11-01",35.0,"min")
        val long=row("ExerciseSession","exerciseDuration","2026-11-01",70.0,"min")
        val brief=HealthCoach.brief(snapshot(listOf(short,long)),HealthGoals(),LocalDate.parse("2026-11-01"))!!
        val training=brief.signals.filterIsInstance<HealthCoachSignal.Training>().single()
        assertEquals(1,training.activeDays)
        assertEquals(70.0,training.totalMinutes,0.0)
    }

    @Test fun partialActivityUsesOnlyCompletedDaysForComparison() {
        val completed=(25..31).map {day->row("Steps","count","2026-10-$day",5000.0+day*10,"steps")}
        val today=row("Steps","count","2026-11-01",345.0,"steps",partial=true)
        val brief=HealthCoach.brief(snapshot(completed+today),HealthGoals(steps=8000),LocalDate.parse("2026-11-01"))!!
        val activity=brief.signals.filterIsInstance<HealthCoachSignal.Activity>().single()
        assertEquals(today.id,activity.latest.id)
        assertEquals(8000L,activity.goalSteps)
        assertTrue(activity.baselineMedianSteps!!>5000)
    }

    @Test fun staleRealtimeCategoryIsReportedWithoutChangingMissingRecordsIntoZero() {
        val sleep=row("SleepSession","sleepMinutes","2026-11-01",479.0,"min")
        val old=snapshot(listOf(sleep)).copy(
            coverage=listOf(
                HealthCoverage("SleepSession","available",latest=java.time.Instant.parse("2026-11-01T02:00:00Z").toEpochMilli()),
                HealthCoverage("Steps","available",latest=java.time.Instant.parse("2026-11-01T02:00:00Z").toEpochMilli()),
                HealthCoverage("HeartRate","no_records")
            )
        )
        val brief=HealthCoach.brief(old,HealthGoals(),LocalDate.parse("2026-11-01"),java.time.Instant.parse("2026-11-01T11:35:00Z").toEpochMilli())!!
        assertEquals(setOf("Steps"),brief.oldCategories)
        assertTrue("HeartRate" in brief.missingCategories)
    }

    @Test fun sleepOlderThanSixHoursIsNotLabeledAsDelayed() {
        val sleep=row("SleepSession","sleepMinutes","2026-11-01",479.0,"min")
        val snapshot=snapshot(listOf(sleep)).copy(
            coverage=listOf(
                HealthCoverage("SleepSession","available",latest=java.time.Instant.parse("2026-11-01T00:30:00Z").toEpochMilli())
            )
        )
        val brief=HealthCoach.brief(snapshot,HealthGoals(),LocalDate.parse("2026-11-01"),java.time.Instant.parse("2026-11-01T11:35:00Z").toEpochMilli())!!
        assertTrue(brief.oldCategories.isEmpty())
    }

    @Test fun partialTodayCoverageIsNotReportedAsMissing() {
        val steps=row("Steps","count","2026-11-01",345.0,"steps",partial=true)
        val snapshot=snapshot(listOf(steps)).copy(
            coverage=listOf(HealthCoverage("Steps","partial",latest=java.time.Instant.parse("2026-11-01T10:00:00Z").toEpochMilli()))
        )
        val brief=HealthCoach.brief(snapshot,HealthGoals(),LocalDate.parse("2026-11-01"),java.time.Instant.parse("2026-11-01T11:35:00Z").toEpochMilli())!!
        assertTrue(brief.missingCategories.isEmpty())
    }
}
