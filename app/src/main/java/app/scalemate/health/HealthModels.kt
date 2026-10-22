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
