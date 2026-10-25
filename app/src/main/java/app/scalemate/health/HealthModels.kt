package app.scalemate.health

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

fun healthId()=UUID.randomUUID().toString()
fun healthHash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
fun healthDaySlices(start:Instant,end:Instant,zone:ZoneId):List<Pair<Instant,Instant>> = buildList {
    var cursor=start
    while(cursor<end){val next=minOf(cursor.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant(),end);add(cursor to next);cursor=next}
}
data class HealthValue(val value:Double,val unit:String) {
    init {require(value.isFinite())}
}
data class HealthPoint(val time:Long,val value:Double,val unit:String)
data class HealthStage(val start:Long,val end:Long,val stage:Int)
data class HealthRecord(
    val id:String,val kind:String,val source:String,val start:Long,val end:Long=start,
    val values:Map<String,HealthValue> = emptyMap(),val points:List<HealthPoint> = emptyList(),
    val stages:List<HealthStage> = emptyList(),val detail:String="",val offsetSeconds:Int?=null
)
data class HealthEvidence(
    val id:String,val kind:String,val date:String,val source:String,val field:String,val value:Double,val unit:String,
    val count:Int=1,val coverage:Double?=null,val partial:Boolean=false,val algorithm:String="daily-v1",val detail:String=""
)
fun HealthEvidence.period(window:HealthWindow):String {
    if(date=="undated")return "undated"
    val nightly=kind=="SleepSession"||listOf("sleepBpm","sessionBpm","nightRmssd").any {field==it||field.startsWith("$it.")}
    return when {nightly&&date<=window.start->"baseline";nightly&&date<=window.end->"analysis";!nightly&&date<window.start->"baseline";!nightly&&date<window.end->"analysis";else->"partial"}
}
data class HealthCoverage(val kind:String,val status:String,val records:Long=0,val points:Long=0,val sources:List<String> = emptyList(),val latest:Long?=null)
data class HealthWindow(val start:String,val end:String,val zone:String=ZoneId.systemDefault().id,val allHistory:Boolean=false) {
    init {require(LocalDate.parse(start)<LocalDate.parse(end));ZoneId.of(zone)}
    val startInstant get()=LocalDate.parse(start).atStartOfDay(ZoneId.of(zone)).toInstant()
    val endInstant get()=LocalDate.parse(end).atStartOfDay(ZoneId.of(zone)).toInstant()
    companion object {
        fun recent(days:Long,now:Instant=Instant.now(),zone:ZoneId=ZoneId.systemDefault()):HealthWindow {
            val today=now.atZone(zone).toLocalDate()
            return HealthWindow(today.minusDays(days).toString(),today.toString(),zone.id)
        }
    }
}
data class HealthSnapshot(
    val id:String=healthId(),val readAt:Long=System.currentTimeMillis(),val window:HealthWindow,
    val categories:Set<String>,val evidence:List<HealthEvidence>,val coverage:List<HealthCoverage>,
    val warnings:List<String> = emptyList(),val rawFile:String="",val baselineStart:String=LocalDate.parse(window.start).minusDays(21).toString()
) {
    val dataAsOf get()=coverage.mapNotNull {it.latest}.maxOrNull()
    val hash get()=healthHash(HealthJson.snapshot(this).toString())
    fun analysisEvidence()=evidence.filter {it.period(window)=="analysis"&&!it.partial}
}
data class HealthSummaryMetric(
    val kind:String,val field:String,val label:String,val value:Double,val unit:String,val date:String,
    val partial:Boolean,val coverage:Double?,val evidenceId:String
)
data class HealthTrendPoint(val date:LocalDate,val value:Double,val partial:Boolean)
data class HealthTrendSeries(
    val kind:String,val field:String,val unit:String,val points:List<HealthTrendPoint>,val missingDates:List<LocalDate> = emptyList(),val goal:Double?=null
) {
    fun segments():List<List<HealthTrendPoint>> {
        val result=mutableListOf<List<HealthTrendPoint>>()
        var current=mutableListOf<HealthTrendPoint>()
        points.forEach {point->
            if(point.partial){if(current.isNotEmpty()){result+=current;current=mutableListOf()};return@forEach}
            if(current.isNotEmpty()&&current.last().date.plusDays(1)!=point.date){result+=current;current=mutableListOf()}
            current+=point
        }
        if(current.isNotEmpty())result+=current
        return result
    }
}
object HealthOverview {
    private val priority=listOf(
        "SleepSession" to listOf("sleepMinutes","sessionMinutes"),
        "HeartRate" to listOf("sleepBpm","bpm"),
        "HeartRateVariabilityRmssd" to listOf("nightRmssd","sampleMedian"),
        "Steps" to listOf("count"),
        "ExerciseSession" to listOf("recordCount"),
        "Weight" to listOf("weight"),
        "Nutrition" to listOf("energy"),
        "ActiveCaloriesBurned" to listOf("energy"),
        "TotalCaloriesBurned" to listOf("energy"),
        "Distance" to listOf("distance")
    )
    private val trendFields=mapOf(
        "SleepSession" to "sleepMinutes",
        "HeartRate" to "sleepBpm",
        "HeartRateVariabilityRmssd" to "nightRmssd",
        "Steps" to "count",
        "Weight" to "weight",
        "Nutrition" to "energy",
        "ActiveCaloriesBurned" to "energy"
    )
    fun today(snapshot:HealthSnapshot?,today:LocalDate):List<HealthSummaryMetric> {
        if(snapshot==null)return emptyList()
        return priority.mapNotNull {(kind,fields)->
            val candidates=fields.mapNotNull {field->
                snapshot.evidence.filter {it.kind==kind&&it.field==field&&it.date==today.toString()}
                    .maxByOrNull {it.id}?.let {field to it}
            }
            val (field,e)=candidates.firstOrNull() ?: return@mapNotNull null
            HealthSummaryMetric(kind,field,metricLabel(e),e.value,e.unit,e.date,e.partial,e.coverage,e.id)
        }
    }
    fun trend(snapshot:HealthSnapshot?,kind:String):HealthTrendSeries? {
        val fields=trendFields[kind]?.let(::listOf) ?: return null
        val field=fields.firstOrNull {candidate->snapshot?.evidence?.any {it.kind==kind&&it.field==candidate}==true} ?: return null
        val rows=snapshot?.evidence?.filter {it.kind==kind&&it.field==field&&it.period(snapshot.window) in setOf("analysis","partial")}.orEmpty()
        if(rows.isEmpty())return null
        val points=rows.filterNot {it.field=="recordCount"}.mapNotNull {e->
            runCatching {HealthTrendPoint(LocalDate.parse(e.date),e.value,e.partial)}.getOrNull()
        }.distinctBy {it.date}.sortedBy {it.date}
        if(points.isEmpty())return null
        val missing=if(points.isEmpty()||snapshot==null)emptyList() else buildList {
            val first=snapshot.window.startInstant.atZone(java.time.ZoneId.of(snapshot.window.zone)).toLocalDate()
            val last=points.last().date
            val byDate=points.associateBy {it.date}
            var date=first
            while(date<=last){if(date !in byDate)add(date);date=date.plusDays(1)}
        }
        return HealthTrendSeries(kind,field,rows.first().unit,points,missing)
    }
    fun metricLabel(e:HealthEvidence)=when {
        e.field=="sleepMinutes"->"sleep"
        e.field=="sessionMinutes"->"session"
        e.field=="sleepBpm"->"sleep_hr"
        e.field=="bpm"->"heart_rate"
        e.field=="nightRmssd"->"hrv"
        e.field=="count"&&e.kind=="Steps"->"steps"
        e.field=="weight"->"weight"
        e.field=="energy"->"energy"
        e.field=="distance"->"distance"
        e.field=="recordCount"->"records"
        e.algorithm=="platform-aggregate-v1"->"daily_total"
        else->"daily_median"
    }
}
data class HealthMessage(
    val id:String=healthId(),val role:String,val text:String,val createdAt:Long=System.currentTimeMillis(),
    val snapshotId:String="",val serviceId:String="",val serviceName:String="",val model:String="",val configVersion:Long=0,
    val status:String="complete",val evidenceIds:List<String> = emptyList()
)
data class HealthConversation(val id:String=healthId(),val title:String="",val messages:List<HealthMessage> = emptyList())
data class HealthReport(val id:String=healthId(),val snapshotId:String,val message:HealthMessage)
data class HealthGoals(val sleepMinutes:Int?=null,val steps:Long?=null) {
    init {require(sleepMinutes==null||sleepMinutes in 60..1440);require(steps==null||steps in 100..100000)}
}
data class HealthReminderSettings(
    val enabled:Boolean=false,val sleep:Boolean=true,val recovery:Boolean=false,val activity:Boolean=true,val bedtime:Boolean=false,
    val checkMinute:Int=630,val quietStart:Int=1320,val quietEnd:Int=540,val bedtimeMinute:Int=1260,val showDetails:Boolean=false
) {
    init {require(listOf(checkMinute,quietStart,quietEnd,bedtimeMinute).all {it in 0..1439})}
}
data class HealthReminder(val id:String=healthId(),val topics:List<String>,val evidenceIds:List<String>,val createdAt:Long,val snapshotId:String,val key:String,val feedback:String="")
data class HealthRouteRequest(val platformId:String,val start:Long,val end:Long,val source:String)
