package app.scalemate.health

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object HealthMetrics {
    fun median(values:List<Double>):Double?=values.sorted().takeIf {it.isNotEmpty()}?.let {if(it.size%2==1)it[it.size/2] else (it[it.size/2-1]+it[it.size/2])/2}
    fun unionMillis(intervals:List<Pair<Long,Long>>):Long {
        var total=0L;var end=Long.MIN_VALUE
        intervals.filter {it.second>it.first}.sortedBy {it.first}.forEach {(a,b)->total+=max(0,b-max(a,end));end=max(end,b)}
        return total
    }
    fun sleep(record:HealthRecord):Map<String,Double> {
        val valid=record.stages.filter {it.end>it.start&&it.start>=record.start&&it.end<=record.end}
        val session=(record.end-record.start).coerceAtLeast(0)
        val duration=unionMillis(valid.map {it.start to it.end})
        val overlap=valid.sumOf {it.end-it.start}>duration
        val asleep=valid.filter {it.stage in setOf(2,4,5,6)}
        val covered=valid.filter {it.stage!=0}
        val coverage=if(session>0)unionMillis(covered.map {it.start to it.end}).toDouble()/session else 0.0
        return buildMap {
            put("sessionMinutes",session/60000.0);put("stageCoverage",coverage)
            if(!overlap&&coverage>=.9)put("sleepMinutes",unionMillis(asleep.map {it.start to it.end})/60000.0)
            put("overlap",if(overlap)1.0 else 0.0)
        }
    }
    fun compute(records:List<HealthRecord>,zone:ZoneId,now:Long=System.currentTimeMillis(),mainSources:Map<String,String> = emptyMap()):List<HealthEvidence> {
        val unique=records.distinctBy {it.kind+":"+it.source+":"+it.id}
        val result=mutableListOf<HealthEvidence>()
        fun day(time:Long)=Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString()
        fun add(kind:String,date:String,source:String,field:String,value:Double,unit:String,count:Int=1,coverage:Double?=null,algorithm:String="daily-v1",complete:Boolean=false) {
            if(value.isFinite())result+=HealthEvidence(healthHash("$kind|$date|$source|$field|$algorithm").take(24),kind,date,source,field,value,unit,count,coverage,date==day(now)&&!complete,algorithm)
        }
        val chosen=unique.groupBy {it.kind}.mapValues {(kind,rs)->mainSources[kind]?.takeIf {s->rs.any {it.source==s}} ?: rs.groupBy {it.source}.entries.sortedWith(compareByDescending<Map.Entry<String,List<HealthRecord>>>{it.value.map {r->day(r.end)}.distinct().size}.thenBy {it.key}).first().key}
        unique.filter {it.kind!="HeartRate"&&it.kind!="SleepSession"&&(it.kind!="Weight"||it.source==chosen["Weight"])}.groupBy {Triple(it.kind,if(it.start==0L&&it.kind.startsWith("Medical_"))"undated" else day(it.start),it.source)}.forEach {(key,rs)->
            val (kind,date,source)=key
            val raw=rs.filterNot {it.id.startsWith("aggregate:")}
            if(raw.isNotEmpty())add(kind,date,source,"recordCount",raw.size.toDouble(),"records")
            raw.filter {it.kind.startsWith("Medical_")&&it.detail.isNotEmpty()}.forEach {r->
                result+=HealthEvidence(healthHash("clinical:${r.id}").take(24),kind,if(r.start==0L)"undated" else date,source,"clinicalRecord",1.0,"record",detail=r.detail,algorithm="clinical-redacted-v1")
            }
            rs.flatMap {r->r.values.keys}.distinct().forEach {field->
                val vals=rs.mapNotNull {it.values[field]}
                val units=vals.map {it.unit}.distinct()
                if(units.size==1&&vals.isNotEmpty()) {
                    if(units.single()=="enum") {
                        vals.groupingBy {it.value.toInt()}.eachCount().forEach {(code,n)->add(kind,date,source,"$field.code$code.count",n.toDouble(),"records")}
                        return@forEach
                    }
                    // Additive fields are provided by the platform aggregate, not raw multi-source sums.
                    val aggregate=rs.filter {it.id.startsWith("aggregate:")}.mapNotNull {it.values[field]}
                    val value=if(aggregate.isNotEmpty())aggregate.sumOf {it.value} else median(vals.map {it.value})!!
                    add(kind,date,source,field,value,units.single(),vals.size,algorithm=if(aggregate.isNotEmpty())"platform-aggregate-v1" else "daily-median-v1")
                }
            }
            val points=rs.flatMap {it.points}.filter {it.value.isFinite()}.groupBy {it.time/60000}.mapNotNull {(_,p)->median(p.map {it.value})}
            if(points.isNotEmpty())add(kind,date,source,"sampleMedian",median(points)!!,rs.first {it.points.isNotEmpty()}.points.first().unit,points.size,algorithm="minute-median-v1")
        }
        val heart=unique.filter {it.kind=="HeartRate"&&it.source==chosen["HeartRate"]}.flatMap {it.points}.filter {it.value.isFinite()&&it.value>0}.distinctBy {it.time to it.value}
        heart.groupBy {day(it.time)}.forEach {(date,points)->
            val minutes=points.groupBy {it.time/60000}.mapNotNull {(_,p)->median(p.map {it.value})}
            add("HeartRate",date,chosen["HeartRate"].orEmpty(),"bpm",median(minutes)!!,"bpm",points.size,algorithm="minute-median-v1")
            val ordered=minutes.sorted()
            add("HeartRate",date,chosen["HeartRate"].orEmpty(),"bpmP10",ordered[((ordered.size-1)*.1).toInt()],"bpm",points.size,algorithm="minute-percentile-v1")
            add("HeartRate",date,chosen["HeartRate"].orEmpty(),"bpmP90",ordered[((ordered.size-1)*.9).toInt()],"bpm",points.size,algorithm="minute-percentile-v1")
            add("HeartRate",date,chosen["HeartRate"].orEmpty(),"singleSamplePeakBpm",points.maxOf {it.value},"bpm",points.size,algorithm="instant-peak-v1")
        }
        unique.filter {it.kind=="SleepSession"&&it.source==chosen["SleepSession"]}.groupBy {day(it.end)}.forEach {(date,sessions)->
            val primary=sessions.maxBy {it.end-it.start}
            val stats=sleep(primary)
            val duplicates=sessions.filter {it.id!=primary.id&&it.start<primary.end&&it.end>primary.start}
            val localStart=Instant.ofEpochMilli(primary.start).atZone(zone);val localEnd=Instant.ofEpochMilli(primary.end).atZone(zone)
            add("SleepSession",date,primary.source,"sessionStartAfterLocalNoon",((localStart.hour*60+localStart.minute-720+1440)%1440).toDouble(),"min after noon",complete=primary.end<=now)
            add("SleepSession",date,primary.source,"sessionEndMinuteOfDay",(localEnd.hour*60+localEnd.minute).toDouble(),"min of day",complete=primary.end<=now)
            stats.filterKeys {it!="overlap"}.forEach {(field,value)->
                if(field!="sleepMinutes"||duplicates.isEmpty())add("SleepSession",date,primary.source,field,value,if(field=="stageCoverage")"ratio" else "min",coverage=stats["stageCoverage"],algorithm="sleep-stages-v1",complete=primary.end<=now)
            }
            val naps=sessions.filter {it.id!=primary.id&&it.start>=primary.end||it.id!=primary.id&&it.end<=primary.start}
            if(naps.isNotEmpty())add("SleepSession",date,primary.source,"additionalSessionMinutes",unionMillis(naps.map {it.start to it.end})/60000.0,"min",naps.size,complete=naps.all {it.end<=now})
            val sleepIntervals=primary.stages.filter {it.stage in setOf(2,4,5,6)}
            val samples=heart.filter {p->if(sleepIntervals.isEmpty())p.time in primary.start until primary.end else sleepIntervals.any {p.time in it.start until it.end}}
            val mins=samples.groupBy {it.time/60000}.mapNotNull {(_,ps)->median(ps.map {it.value})}
            val expected=ceil((stats["sleepMinutes"] ?: stats.getValue("sessionMinutes"))).coerceAtLeast(1.0)
            if(mins.isNotEmpty())add("HeartRate",date,chosen["HeartRate"].orEmpty(),if(stats.containsKey("sleepMinutes")&&duplicates.isEmpty())"sleepBpm" else "sessionBpm",median(mins)!!,"bpm",samples.size,(mins.size/expected).coerceAtMost(1.0),"sleep-minute-median-v1",complete=primary.end<=now)
            val hrv=unique.filter {it.kind=="HeartRateVariabilityRmssd"&&it.source==chosen[it.kind]&&it.start in primary.start until primary.end}.mapNotNull {r->r.values["heartRateVariabilityMillis"]?.let {r.start to it.value}}
            if(hrv.isNotEmpty()) {
                val eligible=hrv.size>=12&&(hrv.maxOf {it.first}-hrv.minOf {it.first})>=7200000
                add("HeartRateVariabilityRmssd",date,chosen["HeartRateVariabilityRmssd"].orEmpty(),"nightRmssd",median(hrv.map {it.second})!!,"ms",hrv.size,if(eligible)1.0 else 0.0,"night-rmssd-v1",complete=primary.end<=now)
            }
        }
        return result
    }
}
