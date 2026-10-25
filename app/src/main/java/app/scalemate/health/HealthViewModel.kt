package app.scalemate.health

import android.app.Application
import android.app.LocaleManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.scalemate.BuildConfig
import app.scalemate.R
import app.scalemate.data.Store
import app.scalemate.food.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PendingHealthRequest(val question:String,val report:Boolean,val service:AiService,val categories:Set<String>,val window:HealthWindow)
data class HealthState(
    val loaded:Boolean=false,val available:Boolean=true,val categories:Set<String> = emptySet(),val permissions:Map<String,String> = emptyMap(),
    val historySupported:Boolean=false,val historyGranted:Boolean=false,val backgroundSupported:Boolean=false,val backgroundGranted:Boolean=false,
    val window:HealthWindow=HealthWindow.recent(7),val snapshot:HealthSnapshot?=null,val services:List<AiService> = emptyList(),val serviceId:String="",
    val goals:HealthGoals=HealthGoals(),val reminderSettings:HealthReminderSettings=HealthReminderSettings(),val reminders:List<HealthReminder> = emptyList(),
    val conversations:List<HealthConversation> = emptyList(),val conversation:HealthConversation=HealthConversation(),val reports:List<HealthReport> = emptyList(),
    val busy:Boolean=false,val generating:Boolean=false,val progress:String="",val error:Int?=null,val consent:PendingHealthRequest?=null,
    val exports:List<File> = emptyList(),val selectedEvidence:HealthEvidence?=null,val selectedEvidencePeriod:String="",val lastCheck:Long?=null,val sources:Map<String,String> = emptyMap(),val routeRequests:List<HealthRouteRequest> = emptyList(),val pendingRoute:HealthRouteRequest?=null
)

class HealthViewModel(application:Application):AndroidViewModel(application) {
    private val db=Store(application);private val store=HealthStore(db);private val food=FoodStore(db)
    private val reader=HealthReader(application);private val ai=AiClient()
    private val mutable=MutableStateFlow(HealthState());val state=mutable.asStateFlow()
    private var operation:Job?=null;private var generationId:String?=null
    private val settingsMutex=Mutex()
    private fun task(block:suspend ()->Unit) {viewModelScope.launch {try {settingsMutex.withLock {block()}}catch(e:CancellationException){throw e}catch(_:Exception){mutable.update {it.copy(error=R.string.health_storage_error)}}}}
    init {reload()}
    fun reload()=task {
        val data=withContext(Dispatchers.IO) {
            store.prune()
            listOf("health_raw","health_exports").forEach {name->File(getApplication<Application>().cacheDir,name).listFiles()?.filter {it.lastModified()<System.currentTimeMillis()-86400000}?.forEach {it.deleteRecursively()}}
            val selected=store.categories();val latest=store.setting("activeSnapshot").takeIf {it.isNotEmpty()}?.let(store::snapshot)
            val services=food.services();val current=mutable.value
            if(!current.loaded)store.conversations().filter {c->c.messages.any {it.status=="generating"}}.forEach {c->store.conversation(c.copy(messages=c.messages.map {if(it.status=="generating")it.copy(text="",status="cancelled") else it}))}
            val cached=latest?.takeIf {it.categories==selected&&it.categories.all {id->reader.canUseCached(HealthCatalog.kind(id))}&&(!current.loaded||it.window==current.window)}
            HealthState(loaded=true,available=reader.available,categories=selected,permissions=HealthCatalog.kinds.associate {it.id to reader.state(it)},
                historySupported=reader.supports(HealthCatalog.HISTORY),historyGranted=reader.granted(HealthCatalog.HISTORY),backgroundSupported=reader.supports(HealthCatalog.BACKGROUND),backgroundGranted=reader.granted(HealthCatalog.BACKGROUND),
                window=if(!current.loaded)cached?.window ?: current.window else current.window,snapshot=cached,services=services,serviceId=current.serviceId.takeIf {id->services.any {it.id==id}} ?: food.setting("text").takeIf {id->services.any {it.id==id}} ?: services.singleOrNull()?.id.orEmpty(),
                goals=store.goals(),reminderSettings=store.reminderSettings(),reminders=store.reminders(),conversations=store.conversations(),conversation=current.conversation,reports=store.reports(),lastCheck=store.setting("lastBackgroundCheck").toLongOrNull(),sources=store.sources())
        }
        mutable.update {s->val revoked=s.permissions.any {(kind,status)->status=="ready"&&data.permissions[kind]!="ready"};s.copy(loaded=true,available=data.available,categories=data.categories,permissions=data.permissions,window=data.window,historySupported=data.historySupported,historyGranted=data.historyGranted,backgroundSupported=data.backgroundSupported,backgroundGranted=data.backgroundGranted,snapshot=if(s.busy)s.snapshot else data.snapshot,services=data.services,serviceId=data.serviceId,goals=data.goals,reminderSettings=data.reminderSettings,reminders=data.reminders,conversations=data.conversations,conversation=if(revoked)HealthConversation() else s.conversation,reports=data.reports,lastCheck=data.lastCheck,sources=data.sources)}
        if(BuildConfig.HEALTH_AI_ENABLED&&data.reminderSettings.enabled)HealthReminders.schedule(getApplication(),true)
    }
    fun clearError(){mutable.update {it.copy(error=null)}}
    fun categories(ids:Set<String>) {if(mutable.value.busy)return;val selected=ids.intersect(HealthCatalog.kinds.map {it.id}.toSet());mutable.update {it.copy(categories=selected,snapshot=null,exports=emptyList())};task {withContext(Dispatchers.IO){store.categories(selected);store.setting("activeSnapshot","")}}}
    fun window(w:HealthWindow){if(mutable.value.busy)return;mutable.update {it.copy(window=w,snapshot=null,exports=emptyList())};task {withContext(Dispatchers.IO){store.setting("activeSnapshot","")}}}
    fun selectService(id:String,makeDefault:Boolean=false){if(mutable.value.busy)return;mutable.update {it.copy(serviceId=id,conversation=if(it.serviceId!=id)HealthConversation() else it.conversation,consent=null)};if(makeDefault)task {withContext(Dispatchers.IO){food.setting("text",id)}}}
    fun goals(g:HealthGoals){mutable.update {it.copy(goals=g)};task {withContext(Dispatchers.IO){store.goals(g)}}}
    fun reminderSettings(settings:HealthReminderSettings){mutable.update {it.copy(reminderSettings=settings)};task {withContext(Dispatchers.IO){store.reminderSettings(settings)};HealthReminders.schedule(getApplication(),BuildConfig.HEALTH_AI_ENABLED&&settings.enabled)}}
    fun source(kind:String,source:String){if(mutable.value.busy)return;val sources=mutable.value.sources+(kind to source);mutable.update {it.copy(sources=sources,snapshot=null)};task {withContext(Dispatchers.IO){store.sources(sources);store.setting("activeSnapshot","")}}}
    fun clearSource(kind:String){if(mutable.value.busy)return;val sources=mutable.value.sources-kind;mutable.update {it.copy(sources=sources,snapshot=null)};task {withContext(Dispatchers.IO){store.sources(sources);store.setting("activeSnapshot","")}}}
    fun refresh() {
        if(mutable.value.busy)return
        mutable.update {it.copy(busy=true,error=null,exports=emptyList())}
        operation=viewModelScope.launch {try {refreshNow()}catch(e:CancellationException){throw e}catch(_:Exception){mutable.update {it.copy(error=R.string.health_read_error)}}finally {mutable.update {it.copy(busy=false,progress="")}}}
    }
    private suspend fun refreshNow():HealthSnapshot {
        val current=mutable.value
        val snapshot=withContext(Dispatchers.IO){reader.read(current.window,current.categories,current.sources){p->mutable.update {it.copy(progress=p)}}}
        withContext(Dispatchers.IO){store.snapshot(snapshot);store.setting("activeSnapshot",snapshot.id)}
        mutable.update {it.copy(snapshot=snapshot,window=snapshot.window,permissions=HealthCatalog.kinds.associate {k->k.id to reader.state(k)})}
        val routes=if("ExerciseRoute" in current.categories)withContext(Dispatchers.IO){reader.routeRequests(snapshot.window)} else emptyList();mutable.update {it.copy(routeRequests=routes)}
        val event=withContext(Dispatchers.IO){HealthReminderPolicy.evaluate(snapshot,store.reminderSettings(),store.goals(),store.reminders(),System.currentTimeMillis(),java.time.ZoneId.systemDefault())}
        if(event!=null)withContext(Dispatchers.IO){if(store.reminder(event))HealthReminders.notify(getApplication(),event,store.reminderSettings().showDetails)}
        val reminders=withContext(Dispatchers.IO){store.reminders()};mutable.update {it.copy(reminders=reminders)}
        return snapshot
    }
    fun ask(question:String,report:Boolean=false) {
        if(mutable.value.busy||question.isBlank())return
        if(question.length>6000){mutable.update {it.copy(error=R.string.health_question_long)};return}
        val service=mutable.value.services.find {it.id==mutable.value.serviceId} ?: run {mutable.update {it.copy(error=R.string.health_no_service)};return}
        val pending=PendingHealthRequest(question.trim(),report,service,mutable.value.categories,mutable.value.window)
        task {val allowed=withContext(Dispatchers.IO){store.consented(service,pending.categories)};if(allowed)generate(pending) else mutable.update {it.copy(consent=pending)}}
    }
    fun cancelConsent(){mutable.update {it.copy(consent=null)}}
    fun consent() {val pending=mutable.value.consent ?: return;mutable.update {it.copy(consent=null)};task {withContext(Dispatchers.IO){store.consent(pending.service,pending.categories)};generate(pending)}}
    private fun generate(pending:PendingHealthRequest) {
        if(mutable.value.busy)return
        mutable.update {it.copy(busy=true,generating=true,error=null)}
        val assistantId=healthId();generationId=assistantId
        operation=viewModelScope.launch {
            var conversation=mutable.value.conversation
            try {
                val current=withContext(Dispatchers.IO){food.services().find {it.id==pending.service.id}}
                check(current?.version==pending.service.version&&pending.categories==mutable.value.categories&&pending.window==mutable.value.window)
                var snapshot=mutable.value.snapshot
                val revoked=snapshot?.categories?.any {!reader.canUseCached(HealthCatalog.kind(it))}==true
                if(revoked||snapshot==null||System.currentTimeMillis()-snapshot.readAt>6*3600000) {
                    snapshot=if(pending.categories.any {reader.canUseCached(HealthCatalog.kind(it))})refreshNow() else null
                }
                val appLocales=getApplication<Application>().getSystemService(LocaleManager::class.java).applicationLocales
                val language=(if(appLocales.isEmpty)java.util.Locale.getDefault() else appLocales[0]).toLanguageTag()
                val (messages,input)=HealthPrompt.messages(snapshot,pending.question,conversation,mutable.value.goals,language)
                if(snapshot!=null) {
                    snapshot=snapshot.copy(evidence=(snapshot.evidence+input.facts).distinctBy {it.id})
                    withContext(Dispatchers.IO){store.snapshot(snapshot)};mutable.update {it.copy(snapshot=snapshot)}
                }
                val user=HealthMessage(role="user",text=pending.question,snapshotId=snapshot?.id.orEmpty())
                val assistant=HealthMessage(id=assistantId,role="assistant",text="",snapshotId=snapshot?.id.orEmpty(),serviceId=pending.service.id,serviceName=pending.service.name,model=pending.service.model,configVersion=pending.service.version,status="generating")
                conversation=conversation.copy(title=conversation.title.ifBlank {pending.question.take(60)},messages=conversation.messages+user+assistant)
                withContext(Dispatchers.IO){store.conversation(conversation)};mutable.update {it.copy(conversation=conversation)}
                val raw=ai.streamText(pending.service,messages){text->if(generationId==assistantId){val preview=HealthPrompt.preview(text);mutable.update {it.copy(conversation=conversation.copy(messages=conversation.messages.map {m->if(m.id==assistantId)m.copy(text=preview) else m}))}}}
                val (answer,refs)=HealthPrompt.validate(raw,input)
                val chinese=(getApplication<Application>().resources.configuration.locales[0].language=="zh")
                val complete=assistant.copy(text=HealthPrompt.render(answer,refs,input.facts,chinese),status="complete",evidenceIds=refs)
                conversation=conversation.copy(messages=conversation.messages.map {if(it.id==assistantId)complete else it})
                withContext(Dispatchers.IO){store.conversation(conversation);if(pending.report&&snapshot!=null)store.report(HealthReport(snapshotId=snapshot.id,message=complete))}
                val history=withContext(Dispatchers.IO){store.reports() to store.conversations()};mutable.update {it.copy(conversation=conversation,reports=history.first,conversations=history.second)}
            }catch(e:CancellationException){
                withContext(NonCancellable+Dispatchers.IO){if(conversation.messages.isNotEmpty())store.conversation(conversation.copy(messages=conversation.messages.map {if(it.id==assistantId)it.copy(text="",status="cancelled") else it}))}
                throw e
            }catch(e:Exception){
                val error=when(e){is FoodFailure->when(e.resource){R.string.food_ai_invalid->R.string.health_invalid_response;R.string.food_ai_timeout->R.string.health_ai_timeout;R.string.food_ai_network->R.string.health_ai_network;else->e.resource};is HealthInputTooLarge->R.string.health_input_too_large;else->R.string.health_invalid_response}
                val failed=conversation.copy(messages=conversation.messages.map {if(it.id==assistantId)it.copy(text=getApplication<Application>().getString(error),status="failed") else it})
                withContext(Dispatchers.IO){if(failed.messages.isNotEmpty())store.conversation(failed)}
                mutable.update {it.copy(conversation=failed,error=error)}
            }finally {generationId=null;mutable.update {it.copy(busy=false,generating=false,progress="",conversation=it.conversation.copy(messages=it.conversation.messages.map {m->if(m.id==assistantId&&m.status=="generating")m.copy(text="",status="cancelled") else m}))}}
        }
    }
    fun cancel(){generationId=null;operation?.cancel();operation=null;mutable.update {it.copy(consent=null)}}
    fun newConversation(){if(!mutable.value.busy)mutable.update {it.copy(conversation=HealthConversation())}}
    fun openConversation(c:HealthConversation){if(!mutable.value.busy)mutable.update {it.copy(conversation=c)}}
    fun deleteConversation(id:String){if(mutable.value.busy)return;task {withContext(Dispatchers.IO){store.deleteConversation(id)};mutable.update {it.copy(conversations=it.conversations.filterNot {c->c.id==id},conversation=if(it.conversation.id==id)HealthConversation() else it.conversation)}}}
    fun deleteReport(id:String){if(mutable.value.busy)return;task {withContext(Dispatchers.IO){store.deleteReport(id)};mutable.update {it.copy(reports=it.reports.filterNot {r->r.id==id})}}}
    fun openSnapshot(id:String){if(mutable.value.busy)return;task {val snapshot=withContext(Dispatchers.IO){store.snapshot(id)};mutable.update {it.copy(snapshot=snapshot,window=snapshot?.window ?: it.window,categories=snapshot?.categories ?: it.categories)}}}
    fun evidence(id:String,snapshotId:String=""){task {val snapshot=if(snapshotId.isNotEmpty())withContext(Dispatchers.IO){store.snapshot(snapshotId)} else mutable.value.snapshot;val e=snapshot?.evidence?.find {it.id==id};mutable.update {it.copy(selectedEvidence=e,selectedEvidencePeriod=if(e!=null)e.period(snapshot!!.window) else "")}}}
    fun closeEvidence(){mutable.update {it.copy(selectedEvidence=null)}}
    fun beginRoute(request:HealthRouteRequest){mutable.update {it.copy(pendingRoute=request)}}
    fun acceptRoute(route:android.health.connect.datatypes.ExerciseRoute?) {
        val request=mutable.value.pendingRoute ?: return
        mutable.update {it.copy(pendingRoute=null)}
        if(route==null||mutable.value.busy)return
        val snapshot=mutable.value.snapshot ?: return
        task {
            val result=withContext(Dispatchers.IO) {
                val metadata=android.health.connect.datatypes.Metadata.Builder().setId(request.platformId).build()
                val native=android.health.connect.datatypes.ExerciseSessionRecord.Builder(metadata,java.time.Instant.ofEpochMilli(request.start),java.time.Instant.ofEpochMilli(request.end),android.health.connect.datatypes.ExerciseSessionType.EXERCISE_SESSION_TYPE_UNKNOWN).setRoute(route).build()
                val mapped=HealthRecordMapper.map(native,HealthCatalog.kind("ExerciseRoute"),request.source)
                val rawRoot=File(getApplication<Application>().cacheDir,"health_raw").apply {mkdirs()}
                val nextRaw=File.createTempFile("snapshot-",".jsonl",rawRoot)
                if(snapshot.rawFile.matches(Regex("snapshot-[A-Za-z0-9-]+\\.jsonl")))File(rawRoot,snapshot.rawFile).takeIf {it.exists()&&System.currentTimeMillis()-it.lastModified()<86400000}?.copyTo(nextRaw,true)
                nextRaw.appendText(HealthJson.record(mapped).toString()+"\n")
                val routeRecords=nextRaw.useLines {lines->lines.map {org.json.JSONObject(it)}.filter {it.optString("category")=="ExerciseRoute"}.map {HealthJson.record(it)}.filterNot {it.values.containsKey("routeRequiresConsent")}.toList()}
                val evidence=HealthMetrics.compute(routeRecords,java.time.ZoneId.of(snapshot.window.zone))
                val remaining=mutable.value.routeRequests.filterNot {it.platformId==request.platformId}
                val coverage=snapshot.coverage.filterNot {it.kind=="ExerciseRoute"}+HealthCoverage("ExerciseRoute",if(remaining.isEmpty())"available" else "partial",records=routeRecords.size.toLong(),sources=routeRecords.map {it.source}.distinct(),latest=routeRecords.maxOfOrNull {it.end})
                val next=snapshot.copy(id=healthId(),readAt=System.currentTimeMillis(),evidence=snapshot.evidence.filterNot {it.kind=="ExerciseRoute"}+evidence,coverage=coverage,rawFile=nextRaw.name)
                store.snapshot(next);store.setting("activeSnapshot",next.id)
                next to remaining
            }
            mutable.update {it.copy(snapshot=result.first,routeRequests=result.second,exports=emptyList())}
        }
    }
    fun export(question:String,raw:Boolean){if(mutable.value.busy)return;val s=mutable.value.snapshot ?: run {mutable.update {it.copy(error=R.string.health_refresh_first)};return};mutable.update {it.copy(busy=true,error=null)}
        operation=viewModelScope.launch {try {val files=withContext(Dispatchers.IO){HealthExport.write(File(getApplication<Application>().cacheDir,"health_exports"),File(getApplication<Application>().cacheDir,"health_raw"),s,question,raw)};mutable.update {it.copy(exports=files)}}catch(e:CancellationException){throw e}catch(_:Exception){mutable.update {it.copy(error=R.string.health_export_error)}}finally {mutable.update {it.copy(busy=false)}}}
    }
    fun feedback(id:String,value:String)=task {withContext(Dispatchers.IO){store.feedback(id,value)};val reminders=withContext(Dispatchers.IO){store.reminders()};mutable.update {it.copy(reminders=reminders)}}
    fun clearHistory(){if(mutable.value.busy)return;task {withContext(Dispatchers.IO){store.clear();File(getApplication<Application>().cacheDir,"health_raw").deleteRecursively();File(getApplication<Application>().cacheDir,"health_exports").deleteRecursively()};mutable.update {it.copy(snapshot=null,conversation=HealthConversation(),conversations=emptyList(),reports=emptyList(),reminders=emptyList(),exports=emptyList())}}}
    override fun onCleared(){operation?.cancel();db.close();super.onCleared()}
}
