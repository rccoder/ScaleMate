package app.scalemate.food

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.scalemate.R
import app.scalemate.data.Store
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.ZoneId

class FoodViewModel(application: Application): AndroidViewModel(application) {
    private val database=Store(application)
    private val store=FoodStore(database)
    private val health=NutritionOutput(application)
    private val secrets=AiSecrets()
    private val ai=AiClient(secrets)
    private val syncLock=Mutex()
    private val draftLock=Mutex()
    private var aiJob: Job?=null
    private val mutable=MutableStateFlow(FoodState())
    val state=mutable.asStateFlow()
    init {viewModelScope.launch {try {
        val draft=withContext(Dispatchers.IO){store.setting("draft").takeIf {it.isNotBlank()}?.let {FoodJson.draft(it).takeIf {d->System.currentTimeMillis()-d.updatedAt<24*60*60*1000}}}
        mutable.update {it.copy(draft=draft)};reload()
        withContext(Dispatchers.IO){cleanupImages()}
        retryPending()
    }catch(_:Exception){fail(R.string.food_storage_error)}}}
    private suspend fun reload() {
        val data=withContext(Dispatchers.IO){store.migrateLibrary();FoodState(loaded=true,library=store.library(),meals=store.meals(true),templates=store.templates(),services=store.services(),defaultVision=store.setting("vision"),defaultText=store.setting("text"))}
        mutable.update {s->
            val visual=data.services.filter {it.images}
            val selected=s.selectedService.takeIf {id->visual.any {it.id==id}} ?: data.defaultVision.takeIf {id->visual.any {it.id==id}} ?: if(!s.loaded)visual.singleOrNull()?.id.orEmpty() else ""
            s.copy(loaded=true,library=data.library,meals=data.meals,templates=data.templates,services=data.services,defaultVision=data.defaultVision,defaultText=data.defaultText,selectedService=selected,permitted=health.permitted,healthAvailable=health.available)
        }
    }
    private fun fail(resource: Int){mutable.update {it.copy(error=resource)}}
    fun showError(resource:Int){fail(resource)}
    fun refreshServices(){task {reload()}}
    fun clearMessage(){mutable.update {it.copy(error=null,message=null)}}
    private fun task(block: suspend ()->Unit){viewModelScope.launch {try {block()}catch(e:CancellationException){throw e}catch(e:FoodFailure){fail(e.resource)}catch(_:Exception){fail(R.string.food_storage_error)}}}
    private fun persistDraft() {task {draftLock.withLock {
        val draft=mutable.value.draft
        // Keep the last valid snapshot while a numeric field contains an incomplete value.
        val items=draft?.meal?.items.orEmpty()+draft?.batch?.results.orEmpty()
        if(items.any {f->!f.base.valid()||f.grams?.isFinite()==false})return@withLock
        withContext(Dispatchers.IO){store.setting("draft",draft?.let {FoodJson.draft(it)}.orEmpty())}
    }}}
    fun newMeal() {cancelAi();mutable.update {it.copy(draft=FoodDraft(),error=null,message=null)};persistDraft()}
    fun updateDraft(change:(FoodDraft)->FoodDraft) {if(mutable.value.saving||mutable.value.busy)return;mutable.update {s->s.copy(draft=s.draft?.let {change(it).copy(updatedAt=System.currentTimeMillis())},error=null)};persistDraft()}
    fun discard() {if(mutable.value.saving)return;cancelAi();mutable.update {it.copy(draft=null,error=null)};persistDraft();task {withContext(Dispatchers.IO){cleanupImages()}}}
    fun add(item: FoodItem) {updateDraft {it.copy(meal=it.meal.copy(items=it.meal.items+item.reuse()))}}
    fun replace(item: FoodItem){updateDraft {it.copy(meal=it.meal.copy(items=it.meal.items.map {f->if(f.id==item.id)item else f}))}}
    fun removeItem(id: String){updateDraft {it.copy(meal=it.meal.copy(items=it.meal.items.filter {f->f.id!=id}))}}
    fun edit(meal: Meal) {cancelAi();mutable.update {it.copy(draft=FoodDraft(meal=meal,editing=true),error=null,message=null)};persistDraft()}
    fun repeat(meal: Meal) {newMeal();updateDraft {d->d.copy(meal=d.meal.copy(items=meal.items.map {it.reuse()}))}}
    private fun cleanupImages() {
        val d=mutable.value.draft
        val keep=(store.library().flatMap {it.item.photos}+d?.meal?.items.orEmpty().flatMap {it.photos}+d?.batch?.photos.orEmpty()+d?.batch?.results.orEmpty().flatMap {it.photos}).toSet()
        FoodImages.cleanup(getApplication(),keep)
    }
    fun beginPhoto() {if(mutable.value.draft?.batch==null)updateDraft {it.copy(batch=FoodBatch())}}
    fun beginManual() {updateDraft {it.copy(batch=FoodBatch(kind="manual",results=listOf(FoodItem()),status="review"))}}
    fun beginLibrary(entry:FoodLibraryEntry) {updateDraft {it.copy(batch=FoodBatch(kind="library",photos=entry.item.photos,results=listOf(entry.item.reuse().copy(libraryId=entry.id)),status="review"))}}
    fun reviewItem(item:FoodItem) {updateDraft {it.copy(batch=FoodBatch(kind="edit",photos=item.photos,results=listOf(item.reuse().copy(id=item.id)),status="review",editingItemId=item.id))}}
    fun updateBatch(change:(FoodBatch)->FoodBatch) {updateDraft {d->d.copy(batch=d.batch?.let(change))}}
    fun removeResult(id:String){updateDraft {it.removeBatchResult(id)}}
    fun updateResult(item:FoodItem) {updateBatch {b->b.copy(results=b.results.map {if(it.id==item.id)item else it})}}
    fun abandonBatch() {cancelAi();updateDraft {it.copy(batch=null)}}
    fun confirmBatch():Boolean {
        if(mutable.value.busy||mutable.value.saving)return false
        val d=mutable.value.draft ?: return false
        val b=d.batch ?: return false
        val confirmed=b.copy(results=b.results.map {it.copy(needsPortionReview=false)})
        if(!confirmed.validForCommit()){fail(R.string.food_review_required);return false}
        updateDraft {it.copy(batch=confirmed).commitBatch()};return true
    }
    fun removePhoto(path:String) {updateBatch {it.copy(photos=it.photos-path,results=emptyList(),status="idle")}}
    fun importPhoto(uri:Uri)=importPhotos(listOf(uri))
    fun importPhotos(uris:List<Uri>) {
        if(!mutable.value.loaded){task {state.first {it.loaded};importPhotos(uris)};return}
        if(mutable.value.busy||mutable.value.saving||uris.isEmpty())return
        beginPhoto();val batch=mutable.value.draft?.batch ?: return
        if(batch.photos.size+uris.size>FoodImages.MAX_PHOTOS){fail(R.string.food_photo_limit);return}
        val importId=newFoodId();activeRequest=importId
        mutable.update {it.copy(busy=true,error=null)}
        aiJob=viewModelScope.launch {try {
            val files=withContext(Dispatchers.IO){val added=mutableListOf<File>();try {uris.forEach {added+=FoodImages.import(getApplication(),it)};added.toList()}catch(e:Exception){added.forEach {it.delete()};throw e}}
            if(activeRequest==importId&&mutable.value.draft?.batch?.id==batch.id){mutable.update {it.copy(draft=it.draft?.let {d->d.copy(batch=d.batch?.copy(photos=batch.photos+files.map {it.absolutePath},results=emptyList(),status="idle"),updatedAt=System.currentTimeMillis())})};persistDraft()}
        }catch(e:CancellationException){throw e}catch(_:Exception){if(activeRequest==importId)fail(R.string.food_photo_error)}finally {if(activeRequest==importId){activeRequest=null;mutable.update {it.copy(busy=false)}}}}
    }
    fun selectService(id:String,default:Boolean) {mutable.update {it.copy(selectedService=id)};if(default)task {withContext(Dispatchers.IO){store.setting("vision",id)};reload()}}
    fun defaultService(id:String,vision:Boolean){task {withContext(Dispatchers.IO){store.setting(if(vision)"vision" else "text",id)};if(vision)mutable.update {it.copy(selectedService=id)};reload()}}
    fun consent(service: AiService,done:()->Unit) {task {withContext(Dispatchers.IO){val current=store.services().find {it.id==service.id};check(current?.version==service.version);store.service(service.copy(consentVersion=service.version))};reload();done()}}
    fun analyze() {
        val s=mutable.value;val draft=s.draft ?: return;val batch=draft.batch ?: return
        val service=s.services.find {it.id==s.selectedService&&it.images} ?: run {fail(R.string.food_no_visual);return}
        if(service.consentVersion!=service.version){fail(R.string.food_ai_consent_needed);return}
        if(s.busy||batch.photos.isEmpty())return
        val localeManager=getApplication<Application>().getSystemService(android.app.LocaleManager::class.java)
        val outputLanguage=FoodAiLanguage.outputLanguage(localeManager.applicationLocales.toLanguageTags(),localeManager.systemLocales.toLanguageTags())
        val requestId=newFoodId();activeRequest=requestId
        mutable.update {it.copy(busy=true,error=null,draft=it.draft?.copy(batch=batch.copy(status="analyzing")))};persistDraft()
        aiJob=viewModelScope.launch {try {
            val bytes=withContext(Dispatchers.IO){batch.photos.map {File(it).readBytes().also {bytes->require(bytes.size<=4_000_000)}}}
            val items=ai.recognize(service,bytes,batch.description,batch.tags,batch.shareCount,batch.photos,outputLanguage=outputLanguage)
            if(activeRequest==requestId&&mutable.value.draft?.batch?.id==batch.id) {
                mutable.update {it.copy(draft=it.draft?.let {d->d.copy(batch=d.batch?.copy(results=items,status="review"),updatedAt=System.currentTimeMillis())})};persistDraft()
            }
        }catch(e:CancellationException){throw e}catch(e:FoodFailure){if(activeRequest==requestId)fail(e.resource)}catch(_:Exception){if(activeRequest==requestId)fail(R.string.food_ai_invalid)}finally {
            if(activeRequest==requestId){activeRequest=null;mutable.update {it.copy(busy=false,draft=it.draft?.let {d->if(d.batch?.status=="analyzing")d.copy(batch=d.batch.copy(status="interrupted")) else d})};persistDraft()}
        }}
    }
    private var activeRequest:String?=null
    fun cancelAi(){activeRequest=null;aiJob?.cancel();aiJob=null;mutable.update {it.copy(busy=false,draft=it.draft?.let {d->if(d.batch?.status=="analyzing")d.copy(batch=d.batch.copy(status="interrupted")) else d})};persistDraft()}
    fun save(done:()->Unit) {
        val draft=mutable.value.draft ?: return
        if(mutable.value.saving)return
        if(draft.batch!=null||draft.meal.items.isEmpty()||draft.meal.items.any {!it.valid()}||!draft.meal.nutrients.valid()||!draft.meal.nutrients.hasValue()){fail(R.string.food_review_required);return}
        mutable.update {it.copy(saving=true,error=null)}
        task {
            try {
                val meal=syncLock.withLock {
                    draftLock.withLock {
                        val saved=withContext(Dispatchers.IO) {
                            store.transaction {
                                val prepared=draft.meal.copy(items=draft.meal.items.map {item->item.copy(photos=item.photos.map {FoodImages.thumbnail(getApplication(),it)}.filter {it.isNotBlank()})})
                                val result=if(draft.editing) {
                                    val previous=store.meals(true).find {it.id==draft.meal.id && !it.deleted} ?: error("Missing meal")
                                    prepared.copy(revision=previous.revision+1,status="pending",attempts=0,platformId=previous.platformId).also {store.update(it)}
                                } else prepared.copy(status="pending",attempts=0).also {store.save(it)}
                                store.setting("draft","")
                                result
                            }
                        }
                        mutable.update {it.copy(draft=null,message=R.string.food_saved)}
                        saved
                    }
                }
                reload()
                withContext(Dispatchers.IO){cleanupImages()}
                mutable.update {it.copy(saving=false)}
                done()
                task {sync(meal.id)}
            } finally {mutable.update {it.copy(saving=false)}}
        }
    }

    fun refreshPermissions() {mutable.update {it.copy(permitted=health.permitted,healthAvailable=health.available)};if(mutable.value.loaded)task {retryPending()}}
    private suspend fun retryPending() {val pending=withContext(Dispatchers.IO){store.meals(true).filter {it.status in listOf("pending","writing","permission","unavailable","failed","deleting","delete_failed") && it.attempts<3}};pending.forEach {sync(it.id)}}
    fun retry(id:String){task {sync(id)}}
    private suspend fun sync(id:String) = syncLock.withLock {
        val meal=withContext(Dispatchers.IO){store.meals(true).find {it.id==id}} ?: return@withLock
        if(meal.status=="synced" && !meal.deleted)return@withLock
        if(!health.available){withContext(Dispatchers.IO){store.status(id,if(meal.deleted)"delete_failed" else "unavailable")};reload();return@withLock}
        if(!health.permitted){withContext(Dispatchers.IO){store.status(id,if(meal.deleted)"delete_failed" else "permission")};reload();return@withLock}
        withContext(Dispatchers.IO){store.status(id,if(meal.deleted)"deleting" else "writing",attempt=true)};reload()
        try {
            if(meal.deleted){health.delete(id);withContext(Dispatchers.IO){store.remove(id)}}
            else {val platformId=health.write(meal);withContext(Dispatchers.IO){store.status(id,"synced",platformId)}}
        }catch(e:CancellationException){throw e}catch(_:Exception){withContext(Dispatchers.IO){store.status(id,if(meal.deleted)"delete_failed" else if(health.permitted)"failed" else "permission")}}
        reload()
    }
    fun delete(meal:Meal,both:Boolean) {task {
        syncLock.withLock {withContext(Dispatchers.IO){if(both)store.tombstone(meal.id) else store.remove(meal.id)}}
        reload();if(both)sync(meal.id)
    }}
    fun favorite(item:FoodItem,value:Boolean) {val confirmed=item.copy(needsPortionReview=false);if(!confirmed.valid()){fail(R.string.food_review_required);return};task {
        withContext(Dispatchers.IO){val snapshot=confirmed.reuse().copy(photos=confirmed.photos.map {FoodImages.thumbnail(getApplication(),it)}.filter {it.isNotBlank()});store.favorite(snapshot,value)};reload()
    }}
    fun updateLibrary(id:String,item:FoodItem){if(!item.valid()){fail(R.string.food_review_required);return};task {withContext(Dispatchers.IO){store.editLibrary(id,item)};reload()}}
    fun saveService(input:AiService,key:String,done:()->Unit) {task {
        try {AiEndpoint.requestUrl(input.baseUrl,input.format);require(input.name.isNotBlank() && input.model.isNotBlank());require(!input.name.contains('\n') && input.name.length<=80)}catch(_:Exception){throw FoodFailure(R.string.food_service_invalid)}
        withContext(Dispatchers.IO){
            val old=store.services().find {it.id==input.id}
            val encrypted=serviceWithKey(input,key).encryptedKey
            val saved=input.copy(name=input.name.trim(),baseUrl=input.baseUrl.trim().trimEnd('/'),model=input.model.trim(),encryptedKey=encrypted,version=(old?.version ?: 0)+1,consentVersion=0)
            store.transaction {store.service(saved);if(!saved.images && store.setting("vision")==saved.id)store.setting("vision","")}
        };reload();done()
    }}
    fun deleteService(id:String){task {withContext(Dispatchers.IO){store.deleteService(id)};reload()}}
    fun testService(input:AiService,key:String) {
        if(mutable.value.busy)return
        try {AiEndpoint.requestUrl(input.baseUrl,input.format);require(input.name.isNotBlank()&&input.model.isNotBlank())}catch(_:Exception){fail(R.string.food_service_invalid);return}
        val existing=mutable.value.services.find {it.id==input.id}
        if(key.isBlank()&&existing==null&&input.encryptedKey.isBlank()){fail(R.string.food_key_required);return}
        mutable.update {it.copy(busy=true,error=null,message=null)}
        aiJob=viewModelScope.launch {try {
            val service=withContext(Dispatchers.IO){serviceWithKey(input,key)}
            ai.test(service);mutable.update {it.copy(message=R.string.food_connection_ok)}
        }catch(e:CancellationException){throw e}catch(e:FoodFailure){fail(e.resource)}catch(_:Exception){fail(R.string.food_key_error)}finally {mutable.update {it.copy(busy=false)}}}
    }

    private fun serviceWithKey(input:AiService,key:String):AiService {
        val encrypted=if(key.isNotBlank())secrets.encrypt(input.id,key.trim()) else
            mutable.value.services.find {it.id==input.id}?.encryptedKey ?: input.encryptedKey.takeIf {it.isNotBlank()} ?: throw FoodFailure(R.string.food_key_required)
        return input.copy(encryptedKey=encrypted).also {try {secrets.decrypt(it)}catch(_:Exception){throw FoodFailure(R.string.food_key_error)}}
    }
    fun copyService(source:AiService,name:String,done:(AiService)->Unit) {
        if(mutable.value.busy)return
        mutable.update {it.copy(busy=true,error=null,message=null)}
        aiJob=viewModelScope.launch {try {
            val copied=withContext(Dispatchers.IO){try {secrets.duplicate(source,name)}catch(_:Exception){throw FoodFailure(R.string.food_key_error)}}
            done(copied)
        }catch(e:CancellationException){throw e}catch(e:FoodFailure){fail(e.resource)}finally {mutable.update {it.copy(busy=false)}}}
    }
    fun fetchModels(input:AiService,key:String,done:(AiModelCatalog)->Unit) {
        if(mutable.value.busy)return
        try {AiEndpoint.modelsUrl(input.baseUrl,input.format)}catch(_:Exception){fail(R.string.food_service_invalid);return}
        mutable.update {it.copy(busy=true,error=null,message=null)}
        aiJob=viewModelScope.launch {try {
            val service=withContext(Dispatchers.IO){serviceWithKey(input,key)}
            val result=ai.listModels(service);done(result)
        }catch(e:CancellationException){throw e}catch(e:FoodFailure){fail(e.resource)}catch(_:Exception){fail(R.string.ai_models_invalid)}finally {mutable.update {it.copy(busy=false)}}}
    }

    override fun onCleared(){aiJob?.cancel();database.close();super.onCleared()}
}
