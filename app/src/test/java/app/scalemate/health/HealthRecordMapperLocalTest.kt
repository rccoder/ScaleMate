package app.scalemate.health

import android.health.connect.datatypes.*
import android.health.connect.datatypes.units.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class HealthRecordMapperLocalTest {
    private val start=Instant.parse("2026-10-16T16:00:00Z")
    private val end=start.plusSeconds(8*3600)
    private fun metadata()=Metadata.Builder().setId("private-platform-id").setClientRecordId("private-client-id").setDataOrigin(DataOrigin.Builder().setPackageName("private.source.app").build()).build()
    @Test fun nativeSleepStagesUseAndroidTypeCodesAndDoNotExportNotesOrIdentifiers() {
        val record=SleepSessionRecord.Builder(metadata(),start,end).setTitle("private title").setNotes("private notes").setStages(listOf(SleepSessionRecord.Stage(start,start.plusSeconds(1800),1),SleepSessionRecord.Stage(start.plusSeconds(1800),end,2))).setStartZoneOffset(ZoneOffset.ofHours(8)).build()
        val mapped=HealthRecordMapper.map(record,HealthCatalog.kind("SleepSession"),"watch alias")
        assertEquals(450.0,HealthMetrics.sleep(mapped).getValue("sleepMinutes"),0.0)
        assertEquals(28800,mapped.offsetSeconds)
        val json=HealthJson.record(mapped).toString();assertFalse(json.contains("private"));assertFalse(json.contains("getMetadata"))
    }
    @Test fun nutritionEnergyBecomesKcalAndMissingNutrientsStayMissing() {
        val record=NutritionRecord.Builder(metadata(),start,end).setEnergy(Energy.fromCalories(250000.0)).setProtein(Mass.fromGrams(15.0)).build()
        val mapped=HealthRecordMapper.map(record,HealthCatalog.kind("Nutrition"),"source alias")
        assertEquals(250.0,mapped.values.getValue("energy").value,0.0);assertEquals("kcal",mapped.values.getValue("energy").unit)
        assertEquals(15.0,mapped.values.getValue("protein").value,0.0);assertFalse(mapped.values.containsKey("totalFat"))
    }
    @Test fun rawHeartSamplesPreserveTimestampsAndBpm() {
        val r=HeartRateRecord.Builder(metadata(),start,end,listOf(HeartRateRecord.HeartRateSample(60,start),HeartRateRecord.HeartRateSample(75,start.plusSeconds(60)))).build()
        val mapped=HealthRecordMapper.map(r,HealthCatalog.kind("HeartRate"),"watch alias")
        assertEquals(listOf(60.0,75.0),mapped.points.map {it.value});assertTrue(mapped.points.all {it.unit=="bpm"})
    }
    @Test fun nativeNumericTypesAreNotConfusedWithCategoricalEnums() {
        val r=StepsRecord.Builder(metadata(),start,end,7000).build()
        val mapped=HealthRecordMapper.map(r,HealthCatalog.kind("Steps"),"watch alias")
        assertEquals(7000.0,mapped.values.getValue("count").value,0.0);assertNotEquals("enum",mapped.values.getValue("count").unit)
    }
}
