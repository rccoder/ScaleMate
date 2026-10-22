package app.scalemate.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object HealthReminderPolicy {
    fun quiet(minute:Int,start:Int,end:Int)=if(start==end)false else if(start<end)minute in start until end else minute>=start||minute<end
    fun evaluate(snapshot:HealthSnapshot?,settings:HealthReminderSettings,goals:HealthGoals,history:List<HealthReminder>,now:Long,zone:ZoneId):HealthReminder? {
        if(!settings.enabled)return null
        val local=Instant.ofEpochMilli(now).atZone(zone);val today=local.toLocalDate();val minute=local.hour*60+local.minute
        if(quiet(minute,settings.quietStart,settings.quietEnd))return null
        if(history.any {Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate()==today})return null
        val topics=mutableListOf<String>();val ids=mutableListOf<String>()
        fun allow(topic:String)=history.none {topic in it.topics&&now-it.createdAt<72L*3600000}
        if(settings.bedtime&&minute>=settings.bedtimeMinute&&minute-settings.bedtimeMinute<90&&allow("bedtime"))topics+="bedtime"
        if(snapshot!=null&&minute>=settings.checkMinute&&now-snapshot.readAt<=6*3600000) {
            val evidence=snapshot.evidence
            fun rows(kind:String,field:String)=evidence.filter {it.kind==kind&&it.field==field&&!it.partial&&snapshot.coverage.any {c->c.kind==kind&&c.status=="available"}}.sortedBy {it.date}
            fun consecutive(rs:List<HealthEvidence>,n:Int):List<HealthEvidence> {
                val last=rs.takeLast(n)
                if(last.size<n||LocalDate.parse(last.last().date)<today.minusDays(1))return emptyList()
                if(last.map {it.source}.distinct().size!=1)return emptyList()
                return if(last.zipWithNext().all {(a,b)->LocalDate.parse(a.date).plusDays(1)==LocalDate.parse(b.date)})last else emptyList()
            }
            if(settings.sleep&&goals.sleepMinutes!=null&&allow("sleep")) {
                val rs=consecutive(rows("SleepSession","sleepMinutes"),3)
                if(rs.size==3&&rs.all {it.coverage!=null&&it.coverage>=.9&&it.value<goals.sleepMinutes-30}){topics+="sleep";ids+=rs.map {it.id}}
            }
            if(settings.activity&&goals.steps!=null&&allow("activity")) {
                val rs=consecutive(rows("Steps","count").filter {it.algorithm=="platform-aggregate-v1"},3)
                val week=rows("Steps","count").filter {it.date>=today.minusDays(7).toString()&&it.date<today.toString()}
                if(week.map {it.date}.distinct().size>=5&&rs.size==3&&rs.all {it.value<goals.steps*.7}){topics+="activity";ids+=rs.map {it.id}}
            }
            if(settings.recovery&&allow("recovery")) {
                val hrv=rows("HeartRateVariabilityRmssd","nightRmssd").filter {it.coverage==1.0}
                val heart=rows("HeartRate","sleepBpm").filter {(it.coverage ?: 0.0)>=.8}
                val recent=consecutive(hrv,2)
                val matches=recent.mapNotNull {r->
                    val date=LocalDate.parse(r.date)
                    val h=heart.find {it.date==r.date}
                    val base=hrv.filter {it.source==r.source&&it.date>=date.minusDays(21).toString()&&it.date<r.date}
                    val hb=heart.filter {it.source==h?.source&&it.date>=date.minusDays(21).toString()&&it.date<r.date}
                    if(h!=null&&base.size>=7&&hb.size>=7&&r.value<=HealthMetrics.median(base.map {it.value})!!*.8&&h.value>=HealthMetrics.median(hb.map {it.value})!!+5)listOf(r.id,h.id) else null
                }
                if(recent.size==2&&matches.size==2){topics+="recovery";ids+=matches.flatten()}
            }
        }
        if(topics.isEmpty())return null
        val key=healthHash("reminder-v1|$today|${topics.sorted().joinToString()}|${ids.sorted().joinToString()}")
        return HealthReminder(topics=topics,evidenceIds=ids.distinct(),createdAt=now,snapshotId=snapshot?.id.orEmpty(),key=key)
    }
}
