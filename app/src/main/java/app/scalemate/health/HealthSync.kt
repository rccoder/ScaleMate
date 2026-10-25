package app.scalemate.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HealthSyncRead(val snapshot:HealthSnapshot,val records:List<HealthRecord>)
data class HealthChanges(val records:List<HealthRecord>,val deletedIds:List<String>,val nextToken:String)
data class HealthSyncProgress(val history:Boolean,val detail:String="")

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
    suspend fun totals(category:String,start:Instant,end:Instant,zone:ZoneId):List<HealthRecord>
}

class HealthSync(
    private val store:HealthStore,
    private val source:HealthSyncSource,
    private val clock:()->Long=System::currentTimeMillis
) {
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
    suspend fun refresh(window:HealthWindow,categories:Set<String>,sources:Map<String,String>,progress:(HealthSyncProgress)->Unit={}):HealthSyncRead {
        val now=clock();val zone=ZoneId.of(window.zone)
        val readable=categories.filter {source.status(it)=="ready"}.toSet()
        val states=readable.associateWith(store::syncState)
        val plan=HealthSyncPlan.plan(readable,states,{fingerprint(it,window)},window,readable.filter(source::incremental).toSet())
        val failures=mutableMapOf<String,String>();val warnings=mutableSetOf<String>()

        suspend fun full(selected:Set<String>) {
            if(selected.isEmpty())return
            try {
                // Bootstrap and recovery both reconcile the complete readable history.
                val request=HealthWindow.recent(7,Instant.ofEpochMilli(now),zone).copy(allHistory=true)
                progress(HealthSyncProgress(true))
                val result=source.full(request,selected,sources){progress(HealthSyncProgress(true,it))}
                warnings+=result.snapshot.warnings
                selected.forEach {category->
                    val status=result.snapshot.coverage.find {it.kind==category}?.status ?: "read_failed"
                    if(status !in setOf("available","no_records")) {
                        failures[category]=status
                    } else {
                        val state=HealthSyncState(category,result.snapshot.syncTokens[category].orEmpty(),now,fingerprint(category,window),result.snapshot.window.start,result.snapshot.window.end,true)
                        store.transaction {
                            store.replaceCategory(category,result.records.filter {it.kind==category})
                            store.syncState(state)
                        }
                    }
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){selected.forEach {failures[it]="read_failed"}}
        }

        full(plan.full)
        val fallback=healthParallelRead(plan.incremental) {category->
            try {
                progress(HealthSyncProgress(false,category))
                val batch=source.changes(category,states.getValue(category).token)
                check(batch.nextToken.isNotEmpty())
                val existing=store.platformRecords(category,batch.deletedIds).associateBy {it.platformId}
                val deletes=batch.deletedIds.mapNotNull(existing::get)
                val affected=store.affectedDates(batch.records,deletes,zone)[category].orEmpty()
                val aggregates=affected.associateWith {date->
                    val start=LocalDate.parse(date).atStartOfDay(zone).toInstant()
                    source.totals(category,start,start.atZone(zone).plusDays(1).toInstant(),zone)
                }
                // A failed aggregate or cancelled read must leave both rows and cursor retryable.
                store.transaction {
                    store.applyIncremental(batch.records,deletes,emptyMap(),zone)
                    aggregates.forEach {(date,rows)->
                        val start=LocalDate.parse(date).atStartOfDay(zone).toInstant()
                        store.replaceAggregates(category,start.toEpochMilli(),start.atZone(zone).plusDays(1).toInstant().toEpochMilli(),rows)
                    }
                    store.syncState(states.getValue(category).copy(token=batch.nextToken,syncedAt=now))
                }
                null
            }catch(e:CancellationException){throw e}catch(_:Exception){category}
        }.filterNotNull().toSet()
        full(fallback)

        return snapshot(window,categories,sources,now,failures,warnings.toList())
    }

    private fun snapshot(window:HealthWindow,categories:Set<String>,sources:Map<String,String>,readAt:Long,failures:Map<String,String> = emptyMap(),warnings:List<String> = emptyList()):HealthSyncRead {
        val now=clock();val zone=ZoneId.of(window.zone)
        val readable=categories.filter {source.status(it)=="ready"}.toSet()
        val actual=if(window.allHistory) {
            val first=store.earliest(readable)?.let {Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}
            window.copy(start=first?.takeIf {it<LocalDate.parse(window.end)}?.toString() ?: LocalDate.parse(window.end).minusDays(1).toString())
        } else window
        val begin=if(actual.allHistory)actual.startInstant else actual.startInstant.atZone(zone).minusDays(21).toInstant()
        val end=actual.endInstant.atZone(zone).plusDays(1).toInstant()
        val undated=store.records(readable.filter {it.startsWith("Medical_")}.toSet()).filter {it.start==0L}
        val local=(store.records(readable,begin.toEpochMilli(),end.toEpochMilli())+undated)
            .filter {it.start<now&&(!it.id.startsWith("aggregate:")||it.start>=begin.toEpochMilli())}
        val evidence=HealthMetrics.compute(local,zone,now,sources)
        val coverage=categories.map {category->
            val status=source.status(category)
            val cached=healthCoverage(category,local.filter {it.kind==category})
            cached.copy(status=failures[category] ?: if(status=="ready")cached.status else status)
        }
        val snapshot=HealthSnapshot(readAt=readAt,window=actual,categories=categories,evidence=evidence,coverage=coverage,warnings=warnings)
        return HealthSyncRead(snapshot,local)
    }
}
