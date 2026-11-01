package app.scalemate.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HealthSyncRead(val snapshot:HealthSnapshot,val records:List<HealthRecord>,val unchanged:Boolean=false)
data class HealthChanges(val records:List<HealthRecord>,val deletedIds:List<String>,val nextToken:String,val pages:Int=1)
data class HealthSyncProgress(val history:Boolean,val detail:String="")
data class HealthSyncTrace(val category:String,val stage:String,val elapsedMs:Long,val detail:String="")
class HealthChangeTokenInvalid(cause:Throwable?=null):Exception("Health Connect change token is invalid",cause)

internal suspend fun <T,R> healthParallelRead(items:Collection<T>,read:suspend (T)->R):List<R> = coroutineScope {
    val permits=Semaphore(3)
    items.map {item->async {permits.withPermit {read(item)}}}.awaitAll()
}

interface HealthSyncSource {
    fun status(category:String):String
    fun fingerprint(category:String):String
    fun incremental(category:String):Boolean
    suspend fun full(window:HealthWindow,categories:Set<String>,sources:Map<String,String>,progress:(String)->Unit):HealthSyncRead
    suspend fun changes(category:String,token:String):HealthChanges
    suspend fun reconcileRecent(category:String,start:Instant,end:Instant):List<HealthRecord> = emptyList()
    suspend fun totals(category:String,start:Instant,end:Instant,zone:ZoneId):List<HealthRecord>
}

class HealthSync(
    private val store:HealthStore,
    private val source:HealthSyncSource,
    private val traceEnabled:Boolean=false,
    private val trace:(HealthSyncTrace)->Unit={},
    private val baselineDays:Int=21,
    private val clock:()->Long=System::currentTimeMillis
) {
    private inline fun emitTrace(category:String,stage:String,started:Long,detail:()->String={""}) {
        if(traceEnabled)trace(HealthSyncTrace(category,stage,(System.nanoTime()-started)/1000000,detail()))
    }
    private fun fingerprint(category:String,window:HealthWindow)=source.fingerprint(category)+"|cache=2|zone=${window.zone}"
    fun canUseCache(window:HealthWindow,categories:Set<String>):Boolean = categories.filter {source.status(it)=="ready"}.all {category->
        val state=store.syncState(category)
        state.syncedAt>0&&state.permissionFingerprint==fingerprint(category,window)&&
            (state.allHistory||!window.allHistory&&state.rangeStart<=window.start&&state.rangeEnd>=window.end)
    }
    fun cached(window:HealthWindow,categories:Set<String>,sources:Map<String,String>):HealthSyncRead {
        require(canUseCache(window,categories))
        val readAt=categories.filter {source.status(it)=="ready"}.maxOfOrNull {store.syncState(it).syncedAt} ?: clock()
        return snapshot(window,categories,sources,readAt)
    }
    suspend fun refresh(window:HealthWindow,categories:Set<String>,sources:Map<String,String>,previous:HealthSnapshot?=null,progress:(HealthSyncProgress)->Unit={}):HealthSyncRead {
        val refreshStarted=System.nanoTime()
        val now=clock();val zone=ZoneId.of(window.zone)
        val readable=categories.filter {source.status(it)=="ready"}.toSet()
        val states=readable.associateWith(store::syncState)
        val plan=HealthSyncPlan.plan(readable,states,{fingerprint(it,window)},window,readable.filter(source::incremental).toSet(),now=now)
        emitTrace("all","plan",refreshStarted){"full=${plan.full.size} incremental=${plan.incremental.size}"}
        val failures=ConcurrentHashMap<String,String>();val warnings=mutableSetOf<String>()
        val changed=ConcurrentHashMap.newKeySet<String>()

        suspend fun full(selected:Set<String>) {
            if(selected.isEmpty())return
            val fullStarted=System.nanoTime()
            try {
                // Bootstrap and recovery both reconcile the complete readable history.
                val request=HealthWindow.recent(7,Instant.ofEpochMilli(now),zone).copy(allHistory=true)
                progress(HealthSyncProgress(true))
                val result=source.full(request,selected,sources){progress(HealthSyncProgress(true,it))}
                emitTrace("all","full_read",fullStarted){"categories=${selected.size} records=${result.records.size}"}
                warnings+=result.snapshot.warnings
                selected.forEach {category->
                    val status=result.snapshot.coverage.find {it.kind==category}?.status ?: "read_failed"
                    if(status !in setOf("available","no_records")) {
                        failures[category]=status
                    } else {
                        val state=HealthSyncState(category,result.snapshot.syncTokens[category].orEmpty(),now,fingerprint(category,window),result.snapshot.window.start,result.snapshot.window.end,true)
                        val writeStarted=System.nanoTime()
                        store.transaction {
                            store.replaceCategory(category,result.records.filter {it.kind==category})
                            store.syncState(state)
                            store.markCacheChanged()
                        }
                        emitTrace(category,"full_commit",writeStarted){"records=${result.records.count {it.kind==category}}"}
                        changed+=category
                    }
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){selected.forEach {failures[it]="read_failed"};emitTrace("all","full_failed",fullStarted)}
        }

        full(plan.full)
        val fallback=healthParallelRead(plan.incremental) {category->
            val categoryStarted=System.nanoTime()
            try {
                progress(HealthSyncProgress(false,category))
                val changesStarted=System.nanoTime()
                val batch=source.changes(category,states.getValue(category).token)
                emitTrace(category,"changes",changesStarted){"records=${batch.records.size} deleted=${batch.deletedIds.size} pages=${batch.pages}"}
                check(batch.nextToken.isNotEmpty())
                val recentStarted=System.nanoTime()
                val recentStart=if(category=="SleepSession")Instant.ofEpochMilli(now).minus(java.time.Duration.ofDays(7)) else null
                val recent=recentStart?.let {start->
                    val rows=source.reconcileRecent(category,start,Instant.ofEpochMilli(now))
                    val cached=store.records(setOf(category),start.toEpochMilli(),now).filterNot {it.id.startsWith("aggregate:")}.associateBy {it.id}
                    rows.filter {cached[it.id]!=it}
                }.orEmpty()
                val upserts=(batch.records+recent).distinctBy {it.id}
                emitTrace(category,"recent_reconcile",recentStarted){"recent=${recent.size} upserts=${upserts.size}"}
                val existingStarted=System.nanoTime()
                val existing=store.platformRecords(category,batch.deletedIds).associateBy {it.platformId}
                val deletes=batch.deletedIds.mapNotNull(existing::get)
                emitTrace(category,"deleted_lookup",existingStarted){"ids=${batch.deletedIds.size}"}
                val affectedStarted=System.nanoTime()
                val affected=store.affectedDates(upserts,deletes,zone)[category].orEmpty()
                emitTrace(category,"affected_dates",affectedStarted){"records=${upserts.size} deleted=${deletes.size} dates=${affected.size}"}
                val aggregatesStarted=System.nanoTime()
                val aggregates=affected.associateWith {date->
                    val start=LocalDate.parse(date).atStartOfDay(zone).toInstant()
                    source.totals(category,start,start.atZone(zone).plusDays(1).toInstant(),zone)
                }
                emitTrace(category,"aggregates_total",aggregatesStarted){"dates=${affected.size} rows=${aggregates.values.sumOf {it.size}}"}
                // A failed aggregate or cancelled read must leave both rows and cursor retryable.
                val commitStarted=System.nanoTime()
                store.transaction {
                    store.applyIncremental(upserts,deletes,emptyMap(),zone,mapOf(category to affected))
                    aggregates.forEach {(date,rows)->
                        val start=LocalDate.parse(date).atStartOfDay(zone).toInstant()
                        store.replaceAggregates(category,start.toEpochMilli(),start.atZone(zone).plusDays(1).toInstant().toEpochMilli(),rows)
                    }
                    store.syncState(states.getValue(category).copy(token=batch.nextToken,syncedAt=now))
                    if(upserts.isNotEmpty()||deletes.isNotEmpty())store.markCacheChanged()
                }
                emitTrace(category,"commit",commitStarted){"records=${upserts.size} deleted=${deletes.size} aggregateRows=${aggregates.values.sumOf {it.size}}"}
                if(upserts.isNotEmpty()||deletes.isNotEmpty())changed+=category
                null
            }catch(e:CancellationException){throw e}catch(_:HealthChangeTokenInvalid){emitTrace(category,"token_invalid",categoryStarted);category}catch(_:Exception){
                // Transient reads, aggregate failures and storage errors must retry the same cursor.
                failures[category]="read_failed"
                emitTrace(category,"incremental_failed",categoryStarted)
                null
            }
        }.filterNotNull().toSet()
        full(fallback)

        // A cancelled run may have committed rows without publishing its snapshot. Reuse must
        // match the durable cache revision, not just this run's empty change log or wall clock.
        if(previous!=null&&previous.cacheRevision.isNotEmpty()&&previous.cacheRevision==store.cacheRevision()&&
            changed.isEmpty()&&failures.isEmpty()&&previous.window==window&&previous.categories==categories&&
            Instant.ofEpochMilli(previous.readAt).atZone(zone).toLocalDate()==Instant.ofEpochMilli(now).atZone(zone).toLocalDate()&&
            previous.coverage.size==categories.size&&previous.coverage.all {coverage->
                val status=source.status(coverage.kind)
                if(status=="ready")coverage.status in setOf("available","no_records") else coverage.status==status
            }) {
            emitTrace("all","refresh",refreshStarted){"unchanged=true"}
            return HealthSyncRead(previous.copy(id=healthId(),readAt=now),emptyList(),unchanged=true)
        }
        val result=snapshot(window,categories,sources,now,failures,warnings.toList())
        emitTrace("all","refresh",refreshStarted){"unchanged=false records=${result.records.size}"}
        return result
    }

    private fun snapshot(window:HealthWindow,categories:Set<String>,sources:Map<String,String>,readAt:Long,failures:Map<String,String> = emptyMap(),warnings:List<String> = emptyList()):HealthSyncRead {
        val snapshotStarted=System.nanoTime()
        val now=clock();val zone=ZoneId.of(window.zone)
        val revision=store.cacheRevision().ifEmpty {store.transaction {
            if(store.cacheRevision().isEmpty())store.markCacheChanged()
            store.cacheRevision()
        }}
        val readable=categories.filter {source.status(it)=="ready"}.toSet()
        val actual=if(window.allHistory) {
            val first=store.earliest(readable)?.let {Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}
            window.copy(start=first?.takeIf {it<LocalDate.parse(window.end)}?.toString() ?: LocalDate.parse(window.end).minusDays(1).toString())
        } else window
        val begin=if(actual.allHistory||baselineDays<=0)actual.startInstant else actual.startInstant.atZone(zone).minusDays(baselineDays.toLong()).toInstant()
        val end=actual.endInstant.atZone(zone).plusDays(1).toInstant()
        val recordsStarted=System.nanoTime()
        val undated=store.records(readable.filter {it.startsWith("Medical_")}.toSet()).filter {it.start==0L}
        val local=(store.records(readable,begin.toEpochMilli(),end.toEpochMilli())+undated)
            .filter {it.start<now&&(!it.id.startsWith("aggregate:")||it.start>=begin.toEpochMilli())}
        emitTrace("all","local_records",recordsStarted){
            val largest=local.groupingBy {it.kind}.eachCount().entries.sortedByDescending {it.value}.take(5).joinToString(","){"${it.key}:${it.value}"}
            "rows=${local.size} largest=$largest"
        }
        val metricsStarted=System.nanoTime()
        val evidence=HealthMetrics.compute(local,zone,now,sources)
        emitTrace("all","metrics",metricsStarted){"records=${local.size} evidence=${evidence.size}"}
        val coverage=categories.map {category->
            val status=source.status(category)
            val cached=healthCoverage(category,local.filter {it.kind==category})
            cached.copy(status=failures[category] ?: if(status=="ready")cached.status else status)
        }
        val snapshot=HealthSnapshot(readAt=readAt,window=actual,categories=categories,evidence=evidence,coverage=coverage,warnings=warnings,cacheRevision=revision)
        emitTrace("all","snapshot",snapshotStarted){"records=${local.size} evidence=${evidence.size}"}
        return HealthSyncRead(snapshot,local)
    }
}
