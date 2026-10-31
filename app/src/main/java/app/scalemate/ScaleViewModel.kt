package app.scalemate

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.scalemate.core.*
import app.scalemate.data.*
import app.scalemate.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.time.Instant
import java.util.UUID

data class ScaleCandidate(val address: String, val modelId: String, val rssi: Int)

data class AppState(val loaded: Boolean = false, val scales: List<BoundScale> = emptyList(), val records: List<Measurement> = emptyList(), val activeId: String = "", val candidates: Map<String,ScaleCandidate> = emptyMap(), val discovering: Boolean = false, val weighing: Boolean = false, val listening: Boolean = false, val session: SessionState = SessionState(), val message: Int? = null, val automatic: Boolean = false, val dark: Boolean = false, val dynamic: Boolean = false, val onboarded: Boolean = false, val healthAllowed: Boolean = false, val lastSavedRecordId: String = "") {
    fun confirmedMeasurement(): Measurement? = if(session.phase==Phase.CONFIRMED)records.find {it.id==lastSavedRecordId} else null
}

class ScaleViewModel(application: Application): AndroidViewModel(application) {
    private val store = Store(application)
    private val prefs = application.getSharedPreferences("settings",0)
    private val ble: ScaleTransport = ScaleTransportProvider.create(application)
    private val health = HealthOutput(application)
    private val mutable = MutableStateFlow(AppState(activeId=prefs.getString("active","")!!,automatic=prefs.getBoolean("auto",false),dark=prefs.getBoolean("dark",false),dynamic=prefs.getBoolean("dynamic",false),onboarded=prefs.getBoolean("onboarded",false)))
    val state = mutable.asStateFlow()
    val requiresBluetoothPermission: Boolean get() = ble.requiresBluetoothPermission
    private var foreground = false
    private var foodPage = false
    fun foodPage(active:Boolean) {foodPage=active;if(active)stop() else listen()}
    private var listening = false
    private var engine: WeighingSession? = null
    private var timer: Job? = null
    private var sessionId = ""
    private var sessionScale: BoundScale? = null
    private val syncMutex = Mutex()
    init { viewModelScope.launch { reload(); retryPending() } }
    private fun update(block: (AppState)->AppState) { mutable.value=block(mutable.value) }
    private suspend fun reload() {
        val scales = withContext(Dispatchers.IO) { store.scales() }
        val records = withContext(Dispatchers.IO) { store.records() }
        val id = mutable.value.activeId.takeIf { x -> scales.any { it.id==x } } ?: scales.firstOrNull()?.id.orEmpty()
        prefs.edit().putString("active",id).apply()
        update { it.copy(loaded=true,scales=scales,records=records,activeId=id,healthAllowed=health.permitted,session=if(id!=it.activeId)SessionState() else it.session,lastSavedRecordId=if(id!=it.activeId)"" else it.lastSavedRecordId) }
        listen()
    }
    fun foreground(value: Boolean) { foreground=value;if(value)listen() else stop() }
    fun refreshPermissions() { update { it.copy(healthAllowed=health.permitted) };viewModelScope.launch { retryPending() } }
    fun onboardingDone() { prefs.edit().putBoolean("onboarded",true).apply();update { it.copy(onboarded=true) };listen() }
    fun automatic(value: Boolean) { stop();prefs.edit().putBoolean("auto",value).apply();update { it.copy(automatic=value) };listen() }
    fun dark(value: Boolean) { prefs.edit().putBoolean("dark",value).apply();update { it.copy(dark=value) } }
    fun dynamic(value: Boolean) { prefs.edit().putBoolean("dynamic",value).apply();update { it.copy(dynamic=value) } }
    fun select(id: String) { stop();prefs.edit().putString("active",id).apply();update { it.copy(activeId=id,session=SessionState(),lastSavedRecordId="") };listen() }
    fun message(resource: Int) { update { it.copy(message=resource) } }
    fun discover() {
        stop();update { it.copy(candidates=emptyMap(),discovering=true,message=null) }
        ble.discover({ discovered ->
            val candidate=ScaleCandidate(discovered.address,discovered.model.id,discovered.rssi)
            update { it.copy(candidates=it.candidates+(candidate.address to candidate)) }
        },{ message(R.string.bluetooth_error);stop() })
        if(mutable.value.discovering) timer=viewModelScope.launch { delay(30000);ble.stop();update { it.copy(discovering=false,message=if(it.candidates.isEmpty())R.string.no_scales_found else null) };timer=null;listen() }
    }
    fun bind(candidate: ScaleCandidate, name: String, replacing: String? = null) {
        stop()
        val model=ScaleCatalog.forId(candidate.modelId) ?: ScaleCatalog.yunmai3Se
        val storedName=name.takeUnless {ScaleCatalog.forStoredName(it)!=null} ?: model.id
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if(replacing!=null) store.rebind(replacing,candidate.address,model.id,storedName.take(60))
                    else store.bind(BoundScale(UUID.randomUUID().toString(),storedName.take(60).ifBlank { model.id },candidate.address,false,model.id))
                }
                reload()
                mutable.value.scales.find { it.address==candidate.address }?.let { select(it.id) }
            } catch(_: Exception) { message(R.string.storage_error) }
        }
    }
    fun rename(id: String, name: String) { if(name.isBlank()) return;viewModelScope.launch { withContext(Dispatchers.IO) { store.rename(id,name.take(60)) };reload() } }
    fun unbind(id: String) { stop();viewModelScope.launch { withContext(Dispatchers.IO) { store.unbind(id) };reload() } }
    fun completeScaleSetup(automatic: Boolean) {
        val scale = sessionScale ?: return
        if (scale.verified || engine?.confirm(SystemClock.elapsedRealtime()) != true) return
        rememberToken()
        stop()
        update { it.copy(session=SessionState()) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.verify(scale.id) }
                prefs.edit().putBoolean("auto",automatic).apply()
                update { it.copy(automatic=automatic) }
                reload()
                message(R.string.setup_complete)
            } catch (_: Exception) { message(R.string.storage_error) }
        }
    }
    fun start() { stop();listen(true) }
    private fun listen(manual: Boolean = false) {
        if(foodPage || listening || !foreground || !mutable.value.loaded || !mutable.value.onboarded || mutable.value.discovering || mutable.value.session.phase in listOf(Phase.SAVING,Phase.SAVE_FAILED))return
        val permissions=listOf("android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT")
        if(ble.requiresBluetoothPermission && permissions.any { getApplication<Application>().checkSelfPermission(it)!=android.content.pm.PackageManager.PERMISSION_GRANTED })return
        if(!ble.available())return
        val s=mutable.value.scales.find { it.id==mutable.value.activeId } ?: return
        val model=ScaleCatalog.forId(s.modelId) ?: return
        sessionId=UUID.randomUUID().toString();sessionScale=s
        val previous=prefs.getInt("token-${s.id}",-1).takeIf { it>=0 }
        engine=WeighingSession(s.address,mutable.value.automatic && s.verified,previous)
        listening=true
        update { it.copy(listening=true,weighing=manual,session=if(manual)engine!!.state else it.session,message=null) }
        ble.start(s.address,model,{f,t ->
            val now=SystemClock.elapsedRealtime()
            if(now-t !in 0..2000)return@start
            engine?.frame(f,t,System.currentTimeMillis())
            if(engine?.state?.phase!=Phase.WAITING)update { it.copy(weighing=true,session=engine?.state ?: it.session) }
        }, { message(if(it==-3)R.string.invalid_packet else R.string.bluetooth_error);stop() })
        if(listening) timer=viewModelScope.launch {
            var start=0L
            while(isActive && listening) {
                delay(100)
                val now=SystemClock.elapsedRealtime()
                if(!ble.available()){stop();message(R.string.bluetooth_error);break}
                if(!mutable.value.weighing)continue
                if(start==0L)start=now
                if(now-start>120000) { stop();listen();message(R.string.scan_timeout);break }
                if(engine?.tick(now)==true) { persist();break }
                update { it.copy(session=engine?.state ?: it.session) }
                if(engine?.state?.phase==Phase.EXPIRED) { stop();message(R.string.stale_reading);listen();break }
            }
        }
    }
    fun confirm() { if(sessionScale?.verified==true && engine?.confirm(SystemClock.elapsedRealtime())==true) persist() }
    fun retrySave() {if(mutable.value.session.phase==Phase.SAVE_FAILED)persist()}
    private fun rememberToken() { val s=sessionScale;val token=engine?.state?.token;if(s!=null && token!=null)prefs.edit().putInt("token-${s.id}",token).apply() }
    private fun persist() {
        val s=sessionScale ?:return;val e=engine?.state ?:return;val kg=e.candidateKg ?:return
        val id=sessionId;rememberToken();ble.stop();listening=false;timer?.cancel();update { it.copy(listening=false,weighing=false,session=e.copy(phase=Phase.SAVING)) }
        val record=Measurement(id,s.id,s.name,kg,e.candidateAt,ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(e.candidateAt)).totalSeconds,modelId=s.modelId)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) {store.save(record)};reload();update {if(it.activeId==s.id)it.copy(session=e,lastSavedRecordId=id) else it};listen();sync(record.id) }
            catch(e: CancellationException) {throw e}
            catch(_: Exception) {update {it.copy(session=it.session.copy(phase=Phase.SAVE_FAILED))};message(R.string.storage_error) }
        }
    }
    fun stop() {
        ble.stop();listening=false;timer?.cancel();timer=null
        if(mutable.value.weighing) {rememberToken();engine?.cancel()}
        update { it.copy(discovering=false,listening=false,weighing=false,session=if(it.weighing)engine?.state ?: it.session else it.session) }
    }
    fun cancelWeighing() { stop();listen() }
    fun retry(id: String) { viewModelScope.launch { sync(id) } }
    private suspend fun retryPending() {
        val pending=withContext(Dispatchers.IO) {store.records().filter { it.status in listOf("pending","writing","permission") && it.attempts<3 }}
        for(m in pending)sync(m.id)
    }
    private suspend fun sync(id: String) = syncMutex.withLock {
        val m=withContext(Dispatchers.IO) {store.records().find {it.id==id}} ?:return@withLock
        if(m.status in listOf("synced","deleting","delete_failed"))return@withLock
        if(!health.permitted || !health.available) {
            withContext(Dispatchers.IO) {store.status(id,"permission")};reload();return@withLock
        }
        withContext(Dispatchers.IO) {store.status(id,"writing",increment=true)};reload()
        try { val platformId=health.write(m);withContext(Dispatchers.IO) {store.status(id,"synced",platformId)} }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { withContext(Dispatchers.IO) {store.status(id,if(health.permitted)"failed" else "permission")} }
        reload()
    }
    fun delete(id: String, both: Boolean) {
        viewModelScope.launch {
            syncMutex.withLock {
                val m=withContext(Dispatchers.IO) {store.records().find {it.id==id}} ?:return@withLock
                if(both) {
                    withContext(Dispatchers.IO) {store.status(id,"deleting")};reload()
                    try {health.delete(m)} catch(e: CancellationException){throw e} catch(_: Exception){withContext(Dispatchers.IO){store.status(id,"delete_failed")};reload();return@withLock}
                }
                withContext(Dispatchers.IO) {store.deleteLocal(id)};reload()
            }
        }
    }
    override fun onCleared() { ble.stop();store.close();super.onCleared() }
}
