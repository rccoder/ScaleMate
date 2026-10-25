package app.scalemate.health

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthOverviewTest {
    private val window=HealthWindow("2026-10-18","2026-10-25")
    private fun row(kind:String,field:String,date:String,value:Double,unit:String,partial:Boolean=false)=
        HealthEvidence(healthHash("$kind|$field|$date").take(24),kind,date,"Synthetic source",field,value,unit,partial=partial)
    private fun snapshot(vararg evidence:HealthEvidence)=HealthSnapshot(
        window=window,categories=evidence.map {it.kind}.toSet(),evidence=evidence.toList(),
        coverage=evidence.map {HealthCoverage(it.kind,"available")}.distinctBy {it.kind}
    )

    @Test fun todayCardsUseOnePreferredMetricPerCategoryAndIgnoreCompletedDay() {
        val snapshot=snapshot(
            row("SleepSession","sessionMinutes","2026-10-25",480.0,"min"),
            row("SleepSession","sleepMinutes","2026-10-25",450.0,"min"),
            row("Steps","count","2026-10-25",6800.0,"steps",partial=true),
            row("ExerciseSession","exerciseDuration","2026-10-25",35.0,"min",partial=true),
            row("Steps","count","2026-10-24",9000.0,"steps")
        )
        val cards=HealthOverview.today(snapshot,LocalDate.parse("2026-10-25"))
        assertEquals(listOf("SleepSession","Steps","ExerciseSession"),cards.map {it.kind})
        assertEquals("sleepMinutes",cards.first().field)
        assertEquals("count",cards.single {it.kind=="Steps"}.field)
        assertEquals("exerciseDuration",cards.single {it.kind=="ExerciseSession"}.field)
    }

    @Test fun missingCategoriesAreNotInventedAsZeroCards() {
        val cards=HealthOverview.today(snapshot(row("Weight","weight","2026-10-25",72_400.0,"g")),LocalDate.parse("2026-10-25"))
        assertEquals(listOf("Weight"),cards.map {it.kind})
        assertNull(HealthOverview.trend(snapshot(),"SleepSession"))
    }

    @Test fun trendKeepsMissingDatesAndSeparatesThemIntoDrawableSegments() {
        val snapshot=snapshot(
            row("Steps","count","2026-10-18",7000.0,"steps"),
            row("Steps","count","2026-10-19",8000.0,"steps"),
            row("Steps","count","2026-10-22",6000.0,"steps"),
            row("Steps","count","2026-10-23",6500.0,"steps"),
            row("Steps","count","2026-10-25",3000.0,"steps",partial=true)
        )
        val trend=HealthOverview.trend(snapshot,"Steps")!!
        assertEquals(listOf("2026-10-20","2026-10-21","2026-10-24").map(LocalDate::parse),trend.missingDates)
        assertEquals(2,trend.segments().size)
        assertTrue(trend.points.last().partial)
        assertFalse(trend.segments().flatten().any {it.partial})
    }

    @Test fun clinicalAndCycleCategoriesAreClassifiedAsSensitive() {
        listOf("Medical_CONDITIONS","ExerciseRoute","MenstruationPeriod","SexualActivity").forEach {id->
            assertEquals(id,HealthPrivacyGroup.CLINICAL,HealthCatalog.privacyGroup(HealthCatalog.kind(id)))
        }
        assertEquals(HealthPrivacyGroup.BODY,HealthCatalog.privacyGroup(HealthCatalog.kind("Weight")))
        assertEquals(HealthPrivacyGroup.ACTIVITY,HealthCatalog.privacyGroup(HealthCatalog.kind("Steps")))
    }

    @Test fun stackedMetricsUseBarsWhileMeasurementsAndSleepUseLines() {
        val snapshot=snapshot(
            row("Steps","count","2026-10-24",9000.0,"steps"),
            row("Steps","count","2026-10-25",6800.0,"steps",partial=true),
            row("SleepSession","sleepMinutes","2026-10-24",450.0,"min"),
            row("ExerciseSession","exerciseDuration","2026-10-24",35.0,"min"),
            row("Weight","weight","2026-10-24",72_000.0,"g")
        )
        assertEquals(HealthChartKind.BAR,HealthOverview.trend(snapshot,"Steps")!!.chartKind)
        assertEquals(HealthChartKind.BAR,HealthOverview.trend(snapshot,"ExerciseSession")!!.chartKind)
        assertEquals(HealthChartKind.LINE,HealthOverview.trend(snapshot,"SleepSession")!!.chartKind)
        assertEquals(HealthChartKind.LINE,HealthOverview.trend(snapshot,"Weight")!!.chartKind)
    }

    @Test fun skinTemperatureStartsWithRelativeDeltaNotBaselineTemperature() {
        val snapshot=snapshot(
            row("SkinTemperature","skinTemperatureBaseline","2026-10-25",33.3,"C"),
            row("SkinTemperature","skinTemperatureDelta","2026-10-25",-.97,"C")
        )
        val card=HealthOverview.today(snapshot,LocalDate.parse("2026-10-25")).single {it.kind=="SkinTemperature"}
        assertEquals("skinTemperatureDelta",card.field)
        assertEquals(-.97,card.value,0.0)
        assertEquals("C",card.unit)
    }

    @Test fun recentTrendsWithoutTodayIncludeWeightAndTotalEnergy() {
        val snapshot=snapshot(
            row("Weight","weight","2026-10-22",72_000.0,"g"),
            row("TotalCaloriesBurned","energy","2026-10-22",2200.0,"kcal"),
            row("Weight","weight","2026-10-24",71_800.0,"g"),
            row("TotalCaloriesBurned","energy","2026-10-24",2400.0,"kcal")
        )
        val trends=HealthOverview.trendKinds.mapNotNull {kind->HealthOverview.trend(snapshot,kind)?.let {kind to it}}
        assertTrue(trends.any {it.first=="Weight"})
        assertTrue(trends.any {it.first=="TotalCaloriesBurned"})
        assertTrue(HealthOverview.today(snapshot,LocalDate.parse("2026-10-25")).isEmpty())
    }
}
