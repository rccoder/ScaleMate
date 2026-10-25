package app.scalemate.health

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.*
import android.health.connect.datatypes.AggregationType
import android.health.connect.datatypes.Record
import android.health.connect.changelog.ChangeLogTokenRequest
import android.health.connect.changelog.ChangeLogsRequest
import android.health.connect.changelog.ChangeLogsResponse
import android.os.OutcomeReceiver
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.time.*
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HealthReader(private val context:Context):HealthSyncSource {
    data class ChangeBatch(val records:List<HealthRecord>,val deletedPlatformIds:List<String>,val nextToken:String)
    private var cachedManager:HealthConnectManager?=null
    private var checkedAt=0L
    private fun connection():HealthConnectManager? {val now=System.currentTimeMillis();if(now-checkedAt>1000){cachedManager=context.getSystemService(HealthConnectManager::class.java);checkedAt=now};return cachedManager}
    private val manager get()=connection() ?: error("Health Connect unavailable")
    val available get()=connection()!=null
    fun granted(permission:String)=context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
    fun supports(permission:String)=try {context.packageManager.getPermissionInfo(permission,0);true}catch(_:PackageManager.NameNotFoundException){false}
    fun supports(kind:HealthKind)=available&&(supports(kind.permission)||kind.id=="ExerciseRoute"&&supports("android.permission.health.READ_EXERCISE"))&&try {if(kind.medicalType.isNotEmpty())Class.forName("android.health.connect.ReadMedicalResourcesRequest") else Class.forName(kind.className);true}catch(_:ClassNotFoundException){false}
    fun state(kind:HealthKind)=when {
        !supports(kind)->"unsupported"
        kind.id=="ExerciseRoute"&&!granted("android.permission.health.READ_EXERCISE")->"not_authorized"
        kind.id=="ExerciseRoute"&&!granted(kind.permission)->"route_consent_required"
        !granted(kind.permission)->"not_authorized"
        else->"ready"
    }
    fun canUseCached(kind:HealthKind)=state(kind) in setOf("ready","route_consent_required")
    override fun status(category:String)=state(HealthCatalog.kind(category))
    override fun fingerprint(category:String)="${status(category)}|history=${granted(HealthCatalog.HISTORY)}"
    override fun incremental(category:String)=category!="ExerciseRoute"&&HealthCatalog.kind(category).medicalType.isEmpty()
    override suspend fun full(window:HealthWindow,categories:Set<String>,sources:Map<String,String>,progress:(String)->Unit):HealthSyncRead {
        val snapshot=read(window,categories,sources,progress,captureTokenFirst=true)
        val records=File(context.cacheDir,"health_raw/${snapshot.rawFile}").useLines {lines->lines.map {HealthJson.cachedRecord(org.json.JSONObject(it))}.toList()}
        return HealthSyncRead(snapshot,records)
    }
    override suspend fun changes(category:String,token:String):HealthChanges {
        val batch=changes(token,setOf(category))
        return HealthChanges(batch.records,batch.deletedPlatformIds,batch.nextToken)
    }
    override suspend fun totals(category:String,start:Instant,end:Instant,zone:ZoneId)=totals(HealthCatalog.kind(category),start,end,zone)
    suspend fun routeRequests(window:HealthWindow):List<HealthRouteRequest> {
        if(!granted("android.permission.health.READ_EXERCISE"))return emptyList()
        var token:Long?=null;val seen=mutableSetOf<Long>();val requests=mutableListOf<HealthRouteRequest>()
        do {
            coroutineContext.ensureActive()
            val response=page(HealthCatalog.kind("ExerciseSession"),if(window.allHistory)null else window.startInstant,minOf(Instant.now(),window.endInstant.plus(Duration.ofDays(1))),token)
            response.records.filterIsInstance<android.health.connect.datatypes.ExerciseSessionRecord>().filter {it.hasRoute()&&it.route==null}.forEach {r->requests+=HealthRouteRequest(r.metadata.id,r.startTime.toEpochMilli(),r.endTime.toEpochMilli(),source(r.metadata.dataOrigin.packageName))}
            token=response.nextPageToken.takeIf {it!=-1L};check(token==null||seen.add(token!!))
        }while(token!=null)
        return requests.distinctBy {it.platformId}
    }
    @Suppress("UNCHECKED_CAST") private fun recordClass(kind:HealthKind)=Class.forName(kind.className) as Class<Record>
    fun syncKinds(kinds:Collection<HealthKind>)=kinds.filter {it.id!="ExerciseRoute"&&it.medicalType.isEmpty()&&state(it)=="ready"}.distinctBy {it.className}
    fun incrementalKinds(kinds:Collection<HealthKind>)=syncKinds(kinds).map {it.id}.toSet()
    suspend fun changeToken(kinds:Collection<HealthKind>):String? {
        val types=syncKinds(kinds)
        if(types.isEmpty())return null
        val request=ChangeLogTokenRequest.Builder().apply {types.forEach {addRecordType(recordClass(it))}}.build()
        return suspendCancellableCoroutine {c->
            try {manager.getChangeLogToken(request,context.mainExecutor,object:OutcomeReceiver<android.health.connect.changelog.ChangeLogTokenResponse,HealthConnectException>{
                override fun onResult(result:android.health.connect.changelog.ChangeLogTokenResponse){if(c.isActive)c.resume(result.token)}
                override fun onError(error:HealthConnectException){if(c.isActive)c.resumeWithException(error)}
            })}catch(e:Exception){if(c.isActive)c.resumeWithException(e)}
        }
    }
    suspend fun changeTokens(kinds:Collection<HealthKind>):Map<String,String> {
        val result=linkedMapOf<String,String>()
        syncKinds(kinds).forEach {kind->
            try {changeToken(listOf(kind))?.let {result[kind.id]=it}}
            catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){}
        }
        return result
    }
    suspend fun changes(token:String,types:Set<String>):ChangeBatch {
        val records=linkedMapOf<String,HealthRecord>();val deleted=linkedSetOf<String>();var cursor=token;var pages=0
        do {
            coroutineContext.ensureActive()
            val request=ChangeLogsRequest.Builder(cursor).setPageSize(1000).build()
            val response:ChangeLogsResponse=suspendCancellableCoroutine {c->
                try {manager.getChangeLogs(request,context.mainExecutor,object:OutcomeReceiver<ChangeLogsResponse,HealthConnectException>{
                    override fun onResult(value:ChangeLogsResponse){if(c.isActive)c.resume(value)}
                    override fun onError(error:HealthConnectException){if(c.isActive)c.resumeWithException(error)}
                })}catch(e:Exception){if(c.isActive)c.resumeWithException(e)}
            }
            response.upsertedRecords.forEach {native->
                val kind=HealthCatalog.kinds.firstOrNull {it.id in types&&it.id!="ExerciseRoute"&&it.medicalType.isEmpty()&&it.className==native.javaClass.name} ?: return@forEach
                records[native.metadata.id]=HealthRecordMapper.map(native,kind,source(native.metadata.dataOrigin.packageName))
                deleted-=native.metadata.id
            }
            response.deletedLogs.forEach {deleted+=it.deletedRecordId;records.remove(it.deletedRecordId)}
            cursor=response.nextChangesToken
            pages++
            check(cursor.isNotBlank()&&pages<10000){"Invalid Health Connect change log"}
        }while(response.hasMorePages())
        return ChangeBatch(records.values.toList(),deleted.toList(),cursor)
    }
    private fun filter(start:Instant?,end:Instant)=TimeInstantRangeFilter.Builder().apply {if(start!=null)setStartTime(start);setEndTime(end)}.build()
    private suspend fun page(kind:HealthKind,start:Instant?,end:Instant,token:Long?=null,size:Int=1000):ReadRecordsResponse<Record> = suspendCancellableCoroutine {c->
        try {
            val request=ReadRecordsRequestUsingFilters.Builder(recordClass(kind)).setTimeRangeFilter(filter(start,end)).setAscending(true).setPageSize(size).apply {if(token!=null)setPageToken(token)}.build()
            manager.readRecords(request,context.mainExecutor,object:OutcomeReceiver<ReadRecordsResponse<Record>,HealthConnectException> {
                override fun onResult(result:ReadRecordsResponse<Record>){if(c.isActive)c.resume(result)}
                override fun onError(error:HealthConnectException){if(c.isActive)c.resumeWithException(error)}
            })
        }catch(e:Exception){if(c.isActive)c.resumeWithException(e)}
    }
    private fun source(packageName:String):String=(try {context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName,0)).toString().take(80)}catch(_:PackageManager.NameNotFoundException){"Source"})+" · "+healthHash(packageName).take(8)
    private suspend fun raw(kind:HealthKind,start:Instant,end:Instant):List<HealthRecord> {
        var token:Long?=null;val seen=mutableSetOf<Long>();val result=mutableListOf<HealthRecord>();var points=0
        do {
            coroutineContext.ensureActive()
            val response=page(kind,start,end,token)
            response.records.forEach {record->
                if(kind.id=="ExerciseRoute"&&record is android.health.connect.datatypes.ExerciseSessionRecord&&!record.hasRoute())return@forEach
                val r=HealthRecordMapper.map(record,kind,source(record.metadata.dataOrigin.packageName));points+=r.points.size
                check(points<=1000000&&result.size<100000){"Health chunk too large"};result+=r
            }
            token=response.nextPageToken.takeIf {it!=-1L}
            check(token==null||seen.add(token!!)){"Repeated health page"}
        }while(token!=null)
        return result
    }
    @Suppress("UNCHECKED_CAST") private suspend fun totals(kind:HealthKind,start:Instant,end:Instant,zone:ZoneId):List<HealthRecord> {
        if(kind.id in setOf("ExerciseRoute","SleepSession"))return emptyList()
        val fields=recordClass(kind).fields.filter {it.type==AggregationType::class.java&&it.name.endsWith("_TOTAL")}
        if(fields.isEmpty())return emptyList()
        val types=fields.map {it.name to (it.get(null) as AggregationType<Any>)}
        val result=mutableListOf<HealthRecord>()
        for(slice in healthDaySlices(start,end,zone)) {
            coroutineContext.ensureActive()
            val request=AggregateRecordsRequest.Builder<Any>(filter(slice.first,slice.second)).apply {types.forEach {addAggregationType(it.second)}}.build()
            val response:AggregateRecordsResponse<Any> = suspendCancellableCoroutine {c->
                try {manager.aggregate(request,context.mainExecutor,object:OutcomeReceiver<AggregateRecordsResponse<Any>,HealthConnectException>{
                    override fun onResult(value:AggregateRecordsResponse<Any>){if(c.isActive)c.resume(value)}
                    override fun onError(error:HealthConnectException){if(c.isActive)c.resumeWithException(error)}
                })}catch(e:Exception){if(c.isActive)c.resumeWithException(e)}
            }
            val values=types.mapNotNull {(name,type)->HealthRecordMapper.value(response.get(type))?.let {v->
                val parts=name.removeSuffix("_TOTAL").lowercase().split('_')
                val field=when(kind.id){"ActiveCaloriesBurned","TotalCaloriesBurned"->"energy";"Distance"->"distance";"Hydration"->"volume";"FloorsClimbed"->"floors";"ElevationGained"->"elevation";"WheelchairPushes","Steps"->"count";else->parts.first()+parts.drop(1).joinToString(""){it.replaceFirstChar {ch->ch.uppercase()}}}
                field to when {name.contains("DURATION")->v.copy(value=v.value/60000,unit="min");kind.id=="Steps"->v.copy(unit="steps");kind.id=="WheelchairPushes"->v.copy(unit="pushes");else->v}
            }}.toMap()
            if(values.isNotEmpty())result+=HealthRecord("aggregate:${kind.id}:${slice.first}",kind.id,"Health Connect",slice.first.toEpochMilli(),slice.second.toEpochMilli(),values)
        }
        return result
    }
    suspend fun read(
        window:HealthWindow,
        categories:Set<String>,
        mainSources:Map<String,String> = emptyMap(),
        progress:(String)->Unit={},
        baselineDays:Long=21,
        discoverEarliest:Boolean=true,
        captureTokenFirst:Boolean=false
    ):HealthSnapshot {
        val now=Instant.now();val zone=ZoneId.of(window.zone);val kinds=HealthCatalog.kinds.filter {it.id in categories}
        val syncTokens=if(captureTokenFirst)changeTokens(kinds) else emptyMap()
        var actual=window
        val status=kinds.associate {it.id to HealthCoverage(it.id,state(it))}.toMutableMap()
        val earliestByKind=mutableMapOf<String,Instant>()
        if(discoverEarliest&&(window.allHistory||!granted(HealthCatalog.HISTORY))) {
            val earliest=mutableListOf<Instant>()
            for(kind in kinds.filter {state(it)=="ready"&&it.medicalType.isEmpty()}) {
                coroutineContext.ensureActive()
                try {page(kind,null,now,size=1).records.firstOrNull()?.let {r->val m=HealthRecordMapper.map(r,kind,source(r.metadata.dataOrigin.packageName));val first=Instant.ofEpochMilli(m.start);earliest+=first;earliestByKind[kind.id]=first}}
                catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){status[kind.id]=HealthCoverage(kind.id,"read_failed")}
            }
            if(window.allHistory)earliest.minOrNull()?.let {actual=window.copy(start=it.atZone(zone).toLocalDate().toString().let {d->if(d<window.end)d else LocalDate.parse(window.end).minusDays(1).toString()})}
        }
        val cache=File(context.cacheDir,"health_raw").apply {mkdirs()}
        cache.listFiles()?.filter {it.lastModified()<System.currentTimeMillis()-86400000}?.forEach {it.delete()}
        val rawFile=File.createTempFile("snapshot-",".jsonl",cache)
        val evidence=mutableListOf<HealthEvidence>();val warnings=mutableListOf<String>();val rawSeen=mutableSetOf<String>();val selectedSources=mainSources.toMutableMap()
        val begin=if(actual.allHistory)actual.startInstant else actual.startInstant.atZone(zone).minusDays(baselineDays).toInstant()
        val end=minOf(actual.endInstant.plus(Duration.ofDays(1)),now)
        try {
            rawFile.bufferedWriter().use {writer->
                var cursor=begin
                while(cursor<end) {
                    coroutineContext.ensureActive()
                    val next=minOf(cursor.atZone(zone).plusDays(3).toInstant(),end)
                    val chunk=mutableListOf<HealthRecord>()
                    for(kind in kinds.filter {it.medicalType.isEmpty()&&state(it)=="ready"&&status[it.id]?.status!="unsupported"}) {
                        progress("${kind.label(context.resources.configuration.locales[0].language=="zh")} · ${cursor.atZone(zone).toLocalDate()}")
                        try {
                            val first=earliestByKind[kind.id]
                            if(first!=null&&first>=next)continue
                            val queryStart=maxOf(cursor.atZone(zone).minusDays(1).toInstant(),first ?: Instant.MIN)
                            val rows=raw(kind,queryStart,next)
                            if(kind.id=="ExerciseRoute"&&rows.any {it.values.containsKey("routeRequiresConsent")})warnings+="Exercise routes require additional system consent; missing locations are not an empty route."
                            for(r in rows)if(rawSeen.add(r.id)) {
                                if(r.end<=begin.toEpochMilli()&&r.start<begin.toEpochMilli()||r.start>=end.toEpochMilli())continue
                                val old=status.getValue(kind.id)
                                status[kind.id]=old.copy(status=if(old.status in setOf("read_failed","partial"))"partial" else "available",records=old.records+1,points=old.points+r.points.size,sources=(old.sources+r.source).distinct(),latest=maxOf(old.latest ?: 0,minOf(r.end,now.toEpochMilli())))
                                writer.write(HealthJson.cacheRecord(r).toString());writer.newLine()
                            }
                            val aggregated=if(rows.isEmpty())emptyList() else totals(kind,maxOf(cursor,first ?: Instant.MIN),next,zone)
                            // Keep raw rows for detail export, but use platform totals for cumulative metrics.
                            chunk+=rows
                            chunk+=aggregated
                            aggregated.forEach {r->writer.write(HealthJson.cacheRecord(r).toString());writer.newLine()}
                            if(rows.isEmpty()&&status.getValue(kind.id).status=="ready")status[kind.id]=HealthCoverage(kind.id,"no_records")
                        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){
                            val old=status.getValue(kind.id);status[kind.id]=old.copy(status=if(e is HealthConnectException&&e.errorCode==HealthConnectException.ERROR_UNSUPPORTED_OPERATION)"unsupported" else if(old.records>0)"partial" else "read_failed")
                        }
                    }
                    for(kind in listOf("SleepSession","HeartRate","HeartRateVariabilityRmssd","Weight"))if(kind !in selectedSources)chunk.filter {it.kind==kind}.groupBy {it.source}.maxByOrNull {it.value.size}?.key?.let {selectedSources[kind]=it}
                    evidence+=HealthMetrics.compute(chunk,zone,now.toEpochMilli(),selectedSources).filter {e->val d=LocalDate.parse(e.date);d>=cursor.atZone(zone).toLocalDate()&&d<next.atZone(zone).toLocalDate().let {if(next==now)it.plusDays(1) else it}}
                    cursor=next
                }
                for(kind in kinds.filter {it.medicalType.isNotEmpty()&&state(it)=="ready"}) {
                    coroutineContext.ensureActive();progress(kind.id)
                    try {
                        val rows=HealthMedicalReader(context).read(kind)
                        val selected=if(actual.allHistory)rows else rows.filter {it.start==0L||it.start>=begin.toEpochMilli()&&it.start<end.toEpochMilli()}
                        if(actual.allHistory)selected.filter {it.start!=0L}.minOfOrNull {it.start}?.let {first->val day=Instant.ofEpochMilli(first).atZone(zone).toLocalDate().toString();if(day<actual.start)actual=actual.copy(start=day)}
                        selected.forEach {writer.write(HealthJson.record(it).toString());writer.newLine()}
                        evidence+=HealthMetrics.compute(selected,zone,now.toEpochMilli(),mainSources)
                        if(selected.any {it.start==0L})warnings+="${kind.id}: undated records are detail-only; no date-based conclusion."
                        status[kind.id]=HealthCoverage(kind.id,if(rows.isEmpty())"no_records" else "available",selected.size.toLong(),sources=selected.map {it.source}.distinct(),latest=selected.map {it.start}.filter {it!=0L}.maxOrNull())
                    }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){status[kind.id]=HealthCoverage(kind.id,"read_failed")}catch(_:LinkageError){status[kind.id]=HealthCoverage(kind.id,"unsupported")}
                }
            }
            if(!granted(HealthCatalog.HISTORY))warnings+="History access is limited by Health Connect; this is not a cloud archive."
            if(kinds.any {it.id=="Nutrition"})warnings+="Nutrition represents recorded intake, not complete actual intake."
            warnings+="Sleep/HRV coverage gates are product quality rules, not medical thresholds."
            val baselineStart=LocalDate.parse(actual.start).minusDays(21).toString()
            val bounded=evidence.distinctBy {it.id}.filterNot {e->e.date==baselineStart&&(e.kind=="SleepSession"||e.field in setOf("sleepBpm","sessionBpm","nightRmssd"))}
            return HealthSnapshot(window=actual,categories=categories,evidence=bounded,coverage=status.values.toList(),warnings=warnings,rawFile=rawFile.name,syncTokens=syncTokens)
        }catch(e:Throwable){rawFile.delete();throw e}
    }
}
